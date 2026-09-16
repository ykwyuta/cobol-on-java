package dev.cobolonjava.ims.rdb;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import dev.cobolonjava.ims.store.CheckpointStore;
import dev.cobolonjava.ims.store.DatabaseConflictException;
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.ims.store.DatabaseStoreException;
import dev.cobolonjava.ims.store.MessageInbox;
import dev.cobolonjava.ims.store.QueueLease;
import dev.cobolonjava.ims.store.QueueLeaseException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * IMS のデータベースを RDB の表に生バイトで置く (設計 78 §3.2、ADR-0013、ADR-0015、暫定判断 P-160、P-161)。
 *
 * <h2>行の形</h2>
 * <p>セグメント 1 つが 1 行である。値はセグメントの生バイト (可変長なら LL を外した本体で、長さは {@code SEG_LEN})。
 * {@code HIERARCHY_PATH} は根が {@code /}、その下が {@code /TT-NNNNNN} をつないだもので、{@code TT} は子の型の順
 * (DBD に書いた順、1 から)、{@code NNNNNN} は兄弟の並び (0 から) である。固定幅で数字と {@code -} だけなので、
 * バイト値の順が階層の順になる。
 *
 * <h2>確定とルートアンカーロック</h2>
 * <p>同期点では、前の同期点から変わった根のキーを {@code (DBD 名, キー)} の昇順に並べ、根ごとの排他の行
 * {@code IMS_ROOT_LOCK} を {@code SELECT ... FOR UPDATE} で押さえる。昇順に押さえるのは、ルート間・DBD 間の
 * 獲得順序を揃えて循環待ちを起こさないためである (ADR-0015 の決定 1)。押さえた行の版が、この置き場が読んだときの
 * 版と違えば、ほかの領域が先に確定している。上書きせずに {@link DatabaseConflictException} で止める。
 * 新しい根で排他の行がまだ無ければ作る。同時に作られて一意制約に当たったときも競合である (ファントム)。
 *
 * <p>版を上げてから、そのキーの根の行をすべて消し、いまの木を振り直して書く。振り直すので、中間挿入で番号の隙間が
 * 尽きることはない (P-101)。すべて 1 つの JDBC のトランザクションで確定する。
 *
 * <h2>読み直し</h2>
 * <p>確定のあと、ほかの領域が版を上げた根を読み直してメモリの木を差し替える。同期点では位置を捨てているので、
 * セグメントを差し替えても位置は壊れない。長く動く領域 (MPP) も、他の領域の更新を同期点ごとに見る。
 *
 * <h2>まだ持たないもの</h2>
 * <p>開くときはデータベース全体をメモリに読む。GH で押さえる形 (ADR-0015 の読みの排他)、競合したときの自動の
 * 再試行 (P-107)、根ごとの遅延読み込みは無い。読み直しは同期点ごとに排他の表を DBD ごとに全件読む。
 */
public final class JdbcDatabaseStore implements DatabaseStore, MessageInbox, CheckpointStore, QueueLease {

    private static final int LL = 2;
    private static final int MAX_LEVELS = 15;
    private static final int MAX_TWINS = 999_999;

    private final Connection connection;
    /** DBD ごとに、この置き場が読んだか書いた根の版。排他の行が無い根は載せない (版 0 とみなす)。 */
    private final Map<String, Map<ByteBuffer, Long>> versions = new HashMap<>();
    /** 次の確定で処理済みとして書く電文の ID (P-163)。 */
    private final List<String> inbox = new ArrayList<>();
    /** 次の確定で書く検査点 (P-164)。同じ ID を 2 度書けば、あとのものが残る。 */
    private final Map<String, PendingCheckpoint> pendingCheckpoints = new LinkedHashMap<>();
    /** 借りている取引コードと借り手 (P-167)。借りていなければ {@code null}。 */
    private String leasedTransactionCode;
    private String leaseOwner;

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

    /** 変わった根 1 つ。押さえる順に並べる。 */
    private record Pending(HierarchicalDatabase database, byte[] key) {
    }

    /** 次の確定で書く検査点 1 つ。 */
    private record PendingCheckpoint(String psb, String checkpointId, List<byte[]> areas) {
    }

    @Override
    public HierarchicalDatabase open(DatabaseDefinition dbd) {
        HierarchicalDatabase database = new HierarchicalDatabase(dbd);
        try {
            // 版を先に読む。行を読んでいる間に確定されても、古い版と比べて競合として気づける
            versions.put(dbd.name(), readVersions(dbd.name()));
            build(database, readRows(dbd, null), -1);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot read DBD " + dbd.name() + " from the IMS tables", e);
        }
        return database;
    }

    @Override
    public void commit(Collection<HierarchicalDatabase> databases) {
        List<Pending> pending = new ArrayList<>();
        for (HierarchicalDatabase database : databases) {
            for (ByteBuffer changed : database.changedRootKeys()) {
                byte[] key = new byte[changed.remaining()];
                changed.duplicate().get(key);
                pending.add(new Pending(database, key));
            }
        }
        pending.sort(Comparator.comparing((Pending p) -> p.database().definition().name())
                .thenComparing(Pending::key, Arrays::compareUnsigned));
        Map<Pending, Long> written = new HashMap<>();
        try {
            for (Pending root : pending) {
                written.put(root, lock(root));
            }
            write(pending);
            // 処理済みの電文を、業務の更新と同じトランザクションで書く (ADR-0014 の決定 2)
            writeInbox();
            // 記号 CHKP が退避した域も同じトランザクションで書く (P-164)
            writeCheckpoints();
            // 借用の心拍も同期点で打つ。引き継がれていれば、ここで競合として止まる (P-167)
            refreshLease();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            inbox.clear();
            pendingCheckpoints.clear();
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new DatabaseConflictException("another region created the same root or processed the same"
                        + " message at the same time: " + e.getMessage());
            }
            throw new DatabaseStoreException("cannot commit to the IMS tables", e);
        } catch (RuntimeException e) {
            rollbackQuietly();
            inbox.clear();
            pendingCheckpoints.clear();
            throw e;
        }
        for (Map.Entry<Pending, Long> root : written.entrySet()) {
            versions.get(root.getKey().database().definition().name())
                    .put(ByteBuffer.wrap(root.getKey().key()), root.getValue());
        }
        refresh(databases);
    }

    @Override
    public MessageInbox inbox() {
        return this;
    }

    @Override
    public CheckpointStore checkpoints() {
        return this;
    }

    @Override
    public void record(String psb, String checkpointId, List<byte[]> areas) {
        List<byte[]> copy = new ArrayList<>();
        // 呼ぶ側の域はこのあとも書き換わる。確定まで持つので写しを取る
        areas.forEach(area -> copy.add(area.clone()));
        pendingCheckpoints.put(psb + "/" + checkpointId, new PendingCheckpoint(psb, checkpointId, copy));
    }

    @Override
    public List<byte[]> load(String psb, String checkpointId) {
        PendingCheckpoint pending = pendingCheckpoints.get(psb + "/" + checkpointId);
        if (pending != null) {
            return pending.areas();
        }
        List<byte[]> areas = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT AREA_LEN, AREA_DATA FROM IMS_CHECKPOINT WHERE PSB_NAME = ? AND CHKP_ID = ?"
                        + " ORDER BY AREA_SEQ")) {
            select.setString(1, psb);
            select.setString(2, checkpointId);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    byte[] data = result.getBytes(2);
                    if (data.length != result.getInt(1)) {
                        throw new DatabaseStoreException("checkpoint " + checkpointId + " of PSB " + psb
                                + " has an area whose AREA_LEN is wrong");
                    }
                    areas.add(data);
                }
            }
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot read checkpoint " + checkpointId + " of PSB " + psb, e);
        }
        return areas.isEmpty() ? null : areas;
    }

    /** 同じ ID の検査点を置き換える。前の検査点の域の数が多くても残らないよう、先に消す。 */
    private void writeCheckpoints() throws SQLException {
        if (pendingCheckpoints.isEmpty()) {
            return;
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM IMS_CHECKPOINT WHERE PSB_NAME = ? AND CHKP_ID = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO IMS_CHECKPOINT (PSB_NAME, CHKP_ID, AREA_SEQ, AREA_LEN, AREA_DATA)"
                             + " VALUES (?, ?, ?, ?, ?)")) {
            for (PendingCheckpoint checkpoint : pendingCheckpoints.values()) {
                delete.setString(1, checkpoint.psb());
                delete.setString(2, checkpoint.checkpointId());
                delete.executeUpdate();
                List<byte[]> areas = checkpoint.areas();
                for (int i = 0; i < areas.size(); i++) {
                    insert.setString(1, checkpoint.psb());
                    insert.setString(2, checkpoint.checkpointId());
                    insert.setShort(3, (short) i);
                    insert.setInt(4, areas.get(i).length);
                    insert.setBytes(5, areas.get(i));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
        pendingCheckpoints.clear();
    }

    @Override
    public QueueLease queueLease() {
        return this;
    }

    /**
     * 取引コードを借りる (P-167)。ほかの借り手が居ても、心拍が古ければ引き継ぐ。落ちた領域の借用を
     * 誰も返せないので、引き継げないと二度と起こせなくなるからである。
     */
    @Override
    public Held acquire(String transactionCode, String owner) {
        try {
            Timestamp now = currentTimestamp();
            String holder = null;
            Timestamp heartbeat = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT LEASE_OWNER, HEARTBEAT_AT FROM IMS_QUEUE_LEASE"
                            + " WHERE TRANSACTION_CODE = ? FOR UPDATE")) {
                select.setString(1, transactionCode);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        holder = result.getString(1);
                        heartbeat = result.getTimestamp(2);
                    }
                }
            }
            if (holder == null) {
                insertLease(transactionCode, owner, now);
            } else if (holder.equals(owner) || expired(now, heartbeat)) {
                updateLease(transactionCode, owner, now);
            } else {
                rollbackQuietly();
                throw new QueueLeaseException("another region is reading the queue of transaction code "
                        + transactionCode + " (owner " + holder + ", last heartbeat " + heartbeat
                        + "); two regions on one transaction code do not keep the order of its messages");
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new QueueLeaseException("cannot take the queue lease of transaction code "
                    + transactionCode, e);
        }
        leasedTransactionCode = transactionCode;
        leaseOwner = owner;
        return () -> release(transactionCode, owner);
    }

    private void release(String transactionCode, String owner) {
        leasedTransactionCode = null;
        leaseOwner = null;
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM IMS_QUEUE_LEASE WHERE TRANSACTION_CODE = ? AND LEASE_OWNER = ?")) {
            delete.setString(1, transactionCode);
            delete.setString(2, owner);
            delete.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new QueueLeaseException("cannot give back the queue lease of transaction code "
                    + transactionCode, e);
        }
    }

    /** 同期点ごとに心拍を打つ。引き継がれていれば 1 行も更新できないので、そこで気づける。 */
    private void refreshLease() throws SQLException {
        if (leasedTransactionCode == null) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE IMS_QUEUE_LEASE SET HEARTBEAT_AT = ?"
                        + " WHERE TRANSACTION_CODE = ? AND LEASE_OWNER = ?")) {
            update.setTimestamp(1, currentTimestamp());
            update.setString(2, leasedTransactionCode);
            update.setString(3, leaseOwner);
            if (update.executeUpdate() == 0) {
                throw new DatabaseConflictException("another region took over the queue of transaction code "
                        + leasedTransactionCode + "; this region stopped reading it");
            }
        }
    }

    private void insertLease(String transactionCode, String owner, Timestamp now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO IMS_QUEUE_LEASE (TRANSACTION_CODE, LEASE_OWNER, HEARTBEAT_AT) VALUES (?, ?, ?)")) {
            insert.setString(1, transactionCode);
            insert.setString(2, owner);
            insert.setTimestamp(3, now);
            insert.executeUpdate();
        }
    }

    private void updateLease(String transactionCode, String owner, Timestamp now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE IMS_QUEUE_LEASE SET LEASE_OWNER = ?, HEARTBEAT_AT = ? WHERE TRANSACTION_CODE = ?")) {
            update.setString(1, owner);
            update.setTimestamp(2, now);
            update.setString(3, transactionCode);
            update.executeUpdate();
        }
    }

    /** 置き場の時計で測る。領域ごとの時計がずれていても、借用の判断は 1 つの時計で決まる。 */
    private Timestamp currentTimestamp() throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
             ResultSet result = select.executeQuery()) {
            result.next();
            return result.getTimestamp(1);
        }
    }

    /**
     * 心拍が古ければ、その借り手は落ちたとみなす。長さは実機から採った値ではない (P-167)。
     */
    private static boolean expired(Timestamp now, Timestamp heartbeat) {
        long seconds = Long.parseLong(System.getProperty("cobol.ims.queue.lease-seconds", "60"));
        return now.getTime() - heartbeat.getTime() >= seconds * 1000L;
    }

    @Override
    public boolean seen(String messageId) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM IMS_MESSAGE_INBOX WHERE MESSAGE_ID = ?")) {
            select.setString(1, messageId);
            try (ResultSet result = select.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot read the message inbox", e);
        }
    }

    @Override
    public void record(String messageId) {
        inbox.add(messageId);
    }

    private void writeInbox() throws SQLException {
        if (inbox.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO IMS_MESSAGE_INBOX (MESSAGE_ID) VALUES (?)")) {
            for (String messageId : inbox) {
                // 同じ ID をこの領域が 2 度書くことはないが、読んだあとに他の領域が書けば一意制約に当たる。
                // それは同じ電文を 2 つの領域が処理したということであり、競合として扱う
                insert.setString(1, messageId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        inbox.clear();
    }

    /**
     * 根の排他の行を押さえ、読んだときの版と比べて版を上げる。
     *
     * @return 上げたあとの版
     */
    private long lock(Pending root) throws SQLException {
        String dbd = root.database().definition().name();
        long expected = versions.computeIfAbsent(dbd, ignored -> new HashMap<>())
                .getOrDefault(ByteBuffer.wrap(root.key()), 0L);
        Long current = null;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT VERSION FROM IMS_ROOT_LOCK WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ? FOR UPDATE")) {
            select.setString(1, dbd);
            select.setBytes(2, root.key());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    current = result.getLong(1);
                }
            }
        }
        long found = current == null ? 0L : current;
        if (found != expected) {
            throw new DatabaseConflictException("another region committed root "
                    + HexFormat.of().formatHex(root.key()) + " of DBD " + dbd + " after this region read it"
                    + " (version " + expected + ", now " + found + ")");
        }
        if (current == null) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO IMS_ROOT_LOCK (DBD_NAME, ROOT_KEY_RAW, VERSION) VALUES (?, ?, 1)")) {
                insert.setString(1, dbd);
                insert.setBytes(2, root.key());
                insert.executeUpdate();
            }
            return 1L;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE IMS_ROOT_LOCK SET VERSION = ? WHERE DBD_NAME = ? AND ROOT_KEY_RAW = ?")) {
            update.setLong(1, found + 1);
            update.setString(2, dbd);
            update.setBytes(3, root.key());
            update.executeUpdate();
        }
        return found + 1;
    }

    /** 押さえた根の行を消し、いまの木を書く。 */
    private void write(List<Pending> pending) throws SQLException {
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
            for (Pending root : pending) {
                DatabaseDefinition dbd = root.database().definition();
                byte[] key = root.key();
                for (PreparedStatement delete : List.of(deleteSegments, deleteRoots)) {
                    delete.setString(1, dbd.name());
                    delete.setBytes(2, key);
                    delete.executeUpdate();
                }
                int sequence = 0;
                for (Segment segment : root.database().roots()) {
                    if (!Arrays.equals(segment.key(), key)) {
                        continue;
                    }
                    insertRoot.setString(1, dbd.name());
                    insertRoot.setBytes(2, key);
                    insertRoot.setInt(3, sequence);
                    insertRoot.addBatch();
                    writeSubtree(insertSegment, dbd, key, sequence, segment, "/", "");
                    sequence++;
                }
            }
            insertRoot.executeBatch();
            insertSegment.executeBatch();
        }
    }

    /** ほかの領域が版を上げた根を読み直し、メモリの木を差し替える。 */
    private void refresh(Collection<HierarchicalDatabase> databases) {
        try {
            for (HierarchicalDatabase database : databases) {
                DatabaseDefinition dbd = database.definition();
                Map<ByteBuffer, Long> known = versions.computeIfAbsent(dbd.name(), ignored -> new HashMap<>());
                for (Map.Entry<ByteBuffer, Long> latest : readVersions(dbd.name()).entrySet()) {
                    if (latest.getValue().equals(known.get(latest.getKey()))) {
                        continue;
                    }
                    byte[] key = new byte[latest.getKey().remaining()];
                    latest.getKey().duplicate().get(key);
                    int index = database.detachRoots(key);
                    build(database, readRows(dbd, key), index);
                    known.put(latest.getKey(), latest.getValue());
                }
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DatabaseStoreException("cannot refresh the IMS tables after a sync point", e);
        }
    }

    private Map<ByteBuffer, Long> readVersions(String dbd) throws SQLException {
        Map<ByteBuffer, Long> out = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT ROOT_KEY_RAW, VERSION FROM IMS_ROOT_LOCK WHERE DBD_NAME = ?")) {
            select.setString(1, dbd);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    out.put(ByteBuffer.wrap(result.getBytes(1)), result.getLong(2));
                }
            }
        }
        return out;
    }

    /**
     * セグメントの行を読み、根のキー (符号なし)、同じキーの根の並び、道の順に並べる。置き場の照合順序には頼らない。
     *
     * @param key 読む根のキー。{@code null} なら DBD のすべて
     */
    private List<Row> readRows(DatabaseDefinition dbd, byte[] key) throws SQLException {
        List<Row> rows = new ArrayList<>();
        String sql = "SELECT ROOT_KEY_RAW, ROOT_SEQ, HIERARCHY_PATH, SEG_NAME, SEG_LEVEL, SEG_LEN, SEG_DATA"
                + " FROM IMS_SEGMENT_STORE WHERE DBD_NAME = ?" + (key == null ? "" : " AND ROOT_KEY_RAW = ?");
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, dbd.name());
            if (key != null) {
                select.setBytes(2, key);
            }
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(4);
                    SegmentDefinition type = dbd.segment(name);
                    byte[] body = result.getBytes(7);
                    if (type == null || body.length != result.getInt(6)) {
                        throw inconsistent(dbd, "segment " + name + " is not in the DBD or its SEG_LEN is wrong");
                    }
                    rows.add(new Row(result.getBytes(1), result.getInt(2), result.getString(3), name,
                            result.getInt(5), type.variableLength() ? withLength(body) : body));
                }
            }
        }
        rows.sort(Comparator.comparing(Row::rootKey, Arrays::compareUnsigned)
                .thenComparingInt(Row::rootSeq)
                .thenComparing(Row::path));
        return rows;
    }

    /**
     * 並べた行から木を組む。
     *
     * @param rootIndex 根を置く位置。負なら根もキーの順を確かめながら末尾に置く (開くとき)
     */
    private static void build(HierarchicalDatabase database, List<Row> rows, int rootIndex) {
        DatabaseDefinition dbd = database.definition();
        Segment[] open = new Segment[MAX_LEVELS + 1];
        int index = rootIndex;
        for (Row row : rows) {
            SegmentDefinition type = dbd.segment(row.segment());
            if (type.level() != row.level() || row.level() > MAX_LEVELS) {
                throw inconsistent(dbd, "segment " + row.segment() + " is stored at level " + row.level());
            }
            try {
                if (row.level() == 1 && index >= 0) {
                    open[1] = database.attachRoot(index++, type, row.data());
                } else {
                    open[row.level()] = database.restore(row.level() == 1 ? null : open[row.level() - 1], type,
                            row.data());
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw inconsistent(dbd, e.getMessage());
            }
            Arrays.fill(open, row.level() + 1, open.length, null);
        }
    }

    /** セグメントと、その子孫を先行順に積む。 */
    private static void writeSubtree(PreparedStatement insert, DatabaseDefinition dbd, byte[] rootKey,
                                     int rootSequence, Segment node, String path, String parentPath)
            throws SQLException {
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
                writeSubtree(insert, dbd, rootKey, rootSequence, twins.get(i), childPath, path);
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
