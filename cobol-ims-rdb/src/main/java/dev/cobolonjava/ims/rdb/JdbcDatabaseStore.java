package dev.cobolonjava.ims.rdb;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.ims.store.DatabaseStoreException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * IMS のデータベースを RDB の表に生バイトで置く (設計 78 §3.2、ADR-0013、暫定判断 P-160)。
 *
 * <h2>行の形</h2>
 * <p>セグメント 1 つが 1 行である。値はセグメントの生バイト (可変長なら LL を外した本体で、長さは {@code SEG_LEN})。
 * {@code HIERARCHY_PATH} は根が {@code /}、その下が {@code /TT-NNNNNN} をつないだもので、{@code TT} は子の型の順
 * (DBD に書いた順、1 から)、{@code NNNNNN} は兄弟の並び (0 から) である。固定幅で数字と {@code -} だけなので、
 * バイト値の順が階層の順になる。
 *
 * <h2>確定</h2>
 * <p>同期点では、前の同期点から変わった根のキーについて、そのキーの根の行をすべて消し、いまの木を振り直して書く。
 * 振り直すので、中間挿入で番号の隙間が尽きることはない (P-101)。書き直すのは変わった根だけで、ほかの根の行には
 * 触らない。すべて 1 つの JDBC のトランザクションで確定する。
 *
 * <h2>まだ持たないもの</h2>
 * <p>開くときはデータベース全体をメモリに読む。根ごとの遅延読み込み、ルートアンカーロック (ADR-0015)、
 * 他の領域との同時更新は無い。
 */
public final class JdbcDatabaseStore implements DatabaseStore {

    private static final int LL = 2;
    private static final int MAX_LEVELS = 15;
    private static final int MAX_TWINS = 999_999;

    private final Connection connection;

    public JdbcDatabaseStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        try {
            connection.setAutoCommit(false);
            ImsSchema.ensure(connection);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot prepare the IMS tables", e);
        }
    }

    private record Row(byte[] rootKey, int rootSeq, String path, String segment, int level, byte[] data) {
    }

    @Override
    public HierarchicalDatabase open(DatabaseDefinition dbd) {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT ROOT_KEY_RAW, ROOT_SEQ, HIERARCHY_PATH, SEG_NAME, SEG_LEVEL, SEG_LEN, SEG_DATA"
                        + " FROM IMS_SEGMENT_STORE WHERE DBD_NAME = ?")) {
            select.setString(1, dbd.name());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(4);
                    SegmentDefinition type = dbd.segment(name);
                    byte[] body = result.getBytes(7);
                    int length = result.getInt(6);
                    if (type == null || body.length != length) {
                        throw inconsistent(dbd, "segment " + name + " is not in the DBD or its SEG_LEN is wrong");
                    }
                    rows.add(new Row(result.getBytes(1), result.getInt(2), result.getString(3), name,
                            result.getInt(5), type.variableLength() ? withLength(body) : body));
                }
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot read DBD " + dbd.name() + " from the IMS tables", e);
        }
        // 置き場の照合順序に頼らず、根のキー (符号なし)、同じキーの根の並び、道の順に並べる
        rows.sort(Comparator.comparing(Row::rootKey, Arrays::compareUnsigned)
                .thenComparingInt(Row::rootSeq)
                .thenComparing(Row::path));
        HierarchicalDatabase database = new HierarchicalDatabase(dbd);
        Segment[] open = new Segment[MAX_LEVELS + 1];
        for (Row row : rows) {
            SegmentDefinition type = dbd.segment(row.segment());
            if (type.level() != row.level() || row.level() > MAX_LEVELS) {
                throw inconsistent(dbd, "segment " + row.segment() + " is stored at level " + row.level());
            }
            Segment parent = row.level() == 1 ? null : open[row.level() - 1];
            try {
                open[row.level()] = database.restore(parent, type, row.data());
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw inconsistent(dbd, e.getMessage());
            }
            Arrays.fill(open, row.level() + 1, open.length, null);
        }
        return database;
    }

    @Override
    public void commit(Collection<HierarchicalDatabase> databases) {
        try (PreparedStatement deleteSegments = connection.prepareStatement(
                "DELETE FROM IMS_SEGMENT_STORE WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ?");
             PreparedStatement deleteRoots = connection.prepareStatement(
                     "DELETE FROM IMS_ROOT_INDEX WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ?");
             PreparedStatement insertRoot = connection.prepareStatement(
                     "INSERT INTO IMS_ROOT_INDEX (DBD_NAME, ROOT_KEY_RAW, ROOT_SEQ) VALUES (?, ?, ?)");
             PreparedStatement insertSegment = connection.prepareStatement(
                     "INSERT INTO IMS_SEGMENT_STORE (DBD_NAME, ROOT_KEY_RAW, ROOT_SEQ, HIERARCHY_PATH, SEG_NAME,"
                             + " SEG_LEVEL, PARENT_PATH, SEQ_KEY_RAW, SEG_LEN, SEG_DATA)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (HierarchicalDatabase database : databases) {
                DatabaseDefinition dbd = database.definition();
                for (ByteBuffer changed : database.changedRootKeys()) {
                    byte[] key = new byte[changed.remaining()];
                    changed.duplicate().get(key);
                    for (PreparedStatement delete : List.of(deleteSegments, deleteRoots)) {
                        delete.setString(1, dbd.name());
                        delete.setBytes(2, key);
                        delete.executeUpdate();
                    }
                    int sequence = 0;
                    for (Segment root : database.roots()) {
                        if (!Arrays.equals(root.key(), key)) {
                            continue;
                        }
                        insertRoot.setString(1, dbd.name());
                        insertRoot.setBytes(2, key);
                        insertRoot.setInt(3, sequence);
                        insertRoot.addBatch();
                        write(insertSegment, dbd, key, sequence, root, "/", "");
                        sequence++;
                    }
                }
            }
            insertRoot.executeBatch();
            insertSegment.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot commit to the IMS tables", e);
        }
    }

    /** セグメントと、その子孫を先行順に積む。 */
    private static void write(PreparedStatement insert, DatabaseDefinition dbd, byte[] rootKey, int rootSequence,
                              Segment node, String path, String parentPath) throws SQLException {
        SegmentDefinition type = node.definition();
        byte[] data = node.data();
        byte[] body = type.variableLength() ? Arrays.copyOfRange(data, LL, data.length) : data;
        insert.setString(1, dbd.name());
        insert.setBytes(2, rootKey);
        insert.setInt(3, rootSequence);
        insert.setString(4, path);
        insert.setString(5, type.name());
        insert.setShort(6, (short) type.level());
        insert.setString(7, parentPath);
        if (type.sequenceField() == null) {
            insert.setNull(8, Types.VARBINARY);
        } else {
            insert.setBytes(8, node.key());
        }
        insert.setInt(9, body.length);
        insert.setBytes(10, body);
        insert.addBatch();
        List<SegmentDefinition> childTypes = dbd.childrenOf(type);
        for (int t = 0; t < childTypes.size(); t++) {
            List<Segment> twins = node.children(childTypes.get(t).name());
            if (twins.size() > MAX_TWINS) {
                throw new DatabaseStoreException("more than " + MAX_TWINS + " twins of segment "
                        + childTypes.get(t).name() + " cannot be numbered in HIERARCHY_PATH");
            }
            for (int i = 0; i < twins.size(); i++) {
                String childPath = (path.equals("/") ? "" : path) + String.format("/%02d-%06d", t + 1, i);
                write(insert, dbd, rootKey, rootSequence, twins.get(i), childPath, path);
            }
        }
    }

    /** 可変長のセグメントの本体に LL を付けて、I/O 域の形に戻す。 */
    private static byte[] withLength(byte[] body) {
        int length = body.length + LL;
        byte[] data = new byte[length];
        data[0] = (byte) (length >>> 8);
        data[1] = (byte) length;
        System.arraycopy(body, 0, data, LL, body.length);
        return data;
    }

    private static DatabaseStoreException inconsistent(DatabaseDefinition dbd, String detail) {
        return new DatabaseStoreException("the IMS tables for DBD " + dbd.name() + " are inconsistent: " + detail);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 元の失敗を知らせるほうが大事である
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseStoreException("cannot close the IMS tables", e);
        }
    }
}
