package dev.cobolonjava.ims.batch;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * データベースをデータセットに置く形 (暫定判断 P-155)。
 *
 * <p>セグメントを階層の順に 1 件ずつ並べる。1 件は
 * {@code [続く長さ 2 byte][段 2 byte][セグメント名 8 byte][値]} で、数は big-endian の 2 進である。
 * 親は、直前に読んだ 1 つ上の段のセグメントである。空か無いデータセットは空のデータベースである。
 *
 * <p>IMS の HD の物理形式ではない。RDB の置き場 (ADR-0013) ができるまでの、ジョブのステップの間で
 * データベースを引き継ぐための形である。書くときは別の名前に書いてから置き換えるので、書きかけは残らない。
 */
final class DatabaseFile {

    private static final int NAME = 8;
    private static final int HEADER = 2 + NAME;
    private static final int MAX_LEVELS = 15;

    private final Path path;
    private final String ddName;
    private final CodePage codePage;

    DatabaseFile(Path path, String ddName, CodePage codePage) {
        this.path = path;
        this.ddName = ddName;
        this.codePage = codePage;
    }

    HierarchicalDatabase read(DatabaseDefinition dbd) {
        HierarchicalDatabase database = new HierarchicalDatabase(dbd);
        if (!Files.isRegularFile(path)) {
            return database;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the database data set of DD " + ddName, e);
        }
        Segment[] open = new Segment[MAX_LEVELS + 1];
        int p = 0;
        while (p < bytes.length) {
            if (p + 2 > bytes.length) {
                throw damaged("a record length is cut off");
            }
            int length = unsigned16(bytes, p);
            p += 2;
            if (length < HEADER || p + length > bytes.length) {
                throw damaged("a record is cut off");
            }
            int level = unsigned16(bytes, p);
            String name = codePage.decode(Arrays.copyOfRange(bytes, p + 2, p + HEADER)).stripTrailing();
            byte[] data = Arrays.copyOfRange(bytes, p + HEADER, p + length);
            p += length;
            SegmentDefinition type = dbd.segment(name);
            if (type == null || type.level() != level) {
                throw damaged("segment " + name + " at level " + level + " is not in DBD " + dbd.name());
            }
            Segment parent = level == 1 ? null : open[level - 1];
            if (level > 1 && parent == null) {
                throw damaged("segment " + name + " has no parent");
            }
            try {
                open[level] = database.restore(parent, type, data);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw damaged(e.getMessage());
            }
            Arrays.fill(open, level + 1, open.length, null);
        }
        return database;
    }

    void write(HierarchicalDatabase database) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Segment segment : database.hierarchicalOrder()) {
            byte[] data = segment.data();
            int length = HEADER + data.length;
            String name = segment.definition().name();
            out.write(length >>> 8);
            out.write(length);
            out.write(segment.level() >>> 8);
            out.write(segment.level());
            out.writeBytes(codePage.encode(name + " ".repeat(NAME - name.length())));
            out.writeBytes(data);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".writing");
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(temporary, out.toByteArray());
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the database data set of DD " + ddName, e);
        }
    }

    private ImsBatchException damaged(String detail) {
        return new ImsBatchException("the database data set of DD " + ddName + " is damaged: " + detail);
    }

    private static int unsigned16(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }
}
