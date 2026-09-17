package dev.cobolonjava.ims.batch;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 記号 CHKP が退避した域をデータセットに置く形 (暫定判断 P-164)。
 *
 * <p>検査点 1 つが 1 件である。1 件は {@code [続く長さ 4][PSB 名 8][検査点 ID 8][域の数 2]} に
 * {@code [域の長さ 4][値]} を域の数だけ続けたもので、数は big-endian の 2 進である。同じ PSB と ID の検査点は
 * あとのものが残る。空か無いデータセットは検査点が無いということである。
 *
 * <p>IMS のログの形ではない。RDB の置き場を使わないジョブで、再始動のために域を引き継ぐための形である
 * ({@link DatabaseFile} と同じ立て付け)。書くときは別の名前に書いてから置き換えるので、書きかけは残らない。
 */
final class CheckpointFile {

    private static final int NAME = 8;
    private static final int HEADER = NAME + NAME + 2;
    /** 域の数の上限 (記号 CHKP の上限と同じ)。壊れたデータセットで際限なく読まないために置く。 */
    private static final int MAX_AREAS = 7;

    private final Path path;
    private final String ddName;
    private final CodePage codePage;

    CheckpointFile(Path path, String ddName, CodePage codePage) {
        this.path = path;
        this.ddName = ddName;
        this.codePage = codePage;
    }

    /** 検査点を {@code PSB 名/検査点 ID} で引ける形に読む。 */
    Map<String, List<byte[]>> read() {
        Map<String, List<byte[]>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return out;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the checkpoint data set of DD " + ddName, e);
        }
        int p = 0;
        while (p < bytes.length) {
            if (p + 4 > bytes.length) {
                throw damaged("a record length is cut off");
            }
            int length = signed32(bytes, p);
            p += 4;
            if (length < HEADER || p + length > bytes.length) {
                throw damaged("a record is cut off");
            }
            int end = p + length;
            String psb = codePage.decode(Arrays.copyOfRange(bytes, p, p + NAME)).stripTrailing();
            String id = codePage.decode(Arrays.copyOfRange(bytes, p + NAME, p + 2 * NAME)).stripTrailing();
            int count = ((bytes[p + 2 * NAME] & 0xFF) << 8) | (bytes[p + 2 * NAME + 1] & 0xFF);
            if (count > MAX_AREAS) {
                throw damaged("checkpoint " + id + " has " + count + " areas");
            }
            p += HEADER;
            List<byte[]> areas = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (p + 4 > end) {
                    throw damaged("the length of an area of checkpoint " + id + " is cut off");
                }
                int areaLength = signed32(bytes, p);
                p += 4;
                if (areaLength < 0 || p + areaLength > end) {
                    throw damaged("an area of checkpoint " + id + " is cut off");
                }
                areas.add(Arrays.copyOfRange(bytes, p, p + areaLength));
                p += areaLength;
            }
            if (p != end) {
                throw damaged("checkpoint " + id + " has bytes after its last area");
            }
            out.put(psb + "/" + id, areas);
        }
        return out;
    }

    void write(Map<String, List<byte[]>> checkpoints) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, List<byte[]>> checkpoint : checkpoints.entrySet()) {
            int slash = checkpoint.getKey().indexOf('/');
            String psb = checkpoint.getKey().substring(0, slash);
            String id = checkpoint.getKey().substring(slash + 1);
            List<byte[]> areas = checkpoint.getValue();
            int length = HEADER + areas.stream().mapToInt(area -> 4 + area.length).sum();
            writeInt(out, length);
            out.writeBytes(codePage.encode(pad(psb) + pad(id)));
            out.write(areas.size() >>> 8);
            out.write(areas.size());
            for (byte[] area : areas) {
                writeInt(out, area.length);
                out.writeBytes(area);
            }
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
            throw new UncheckedIOException("cannot write the checkpoint data set of DD " + ddName, e);
        }
    }

    private static String pad(String value) {
        return value.length() >= NAME ? value.substring(0, NAME) : value + " ".repeat(NAME - value.length());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static int signed32(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private ImsBatchException damaged(String detail) {
        return new ImsBatchException("the checkpoint data set of DD " + ddName + " is damaged: " + detail);
    }
}
