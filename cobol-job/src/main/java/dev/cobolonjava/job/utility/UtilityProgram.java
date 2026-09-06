package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ユーティリティの下支え (要件 FR-137)。
 *
 * <p>ユーティリティは<b>翻訳された資産ではない</b>が、ジョブから見れば同じプログラムである。
 * したがって生成コードと同じ入口 ({@link CobolProgram}) を実装し、DD 名でデータへ触れる。
 * ジョブ実行の側に特別扱いは要らない。
 *
 * <h2>覚え書きの出し先</h2>
 * <p>ホストのユーティリティは {@code SYSPRINT} へ経過を書く。<b>ジョブが書いていなければ
 * 出さない</b>のがホストの決まりだが、それでは黙って失敗したときに手がかりが残らない。
 * 結び付けがなければ実行時の出力へ回す。
 */
abstract class UtilityProgram implements CobolProgram {

    /** ユーティリティは作業場所を持たない。触るのは DD の先だけである。 */
    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    /** 制御文の既定の入り口。 */
    protected static final String SYSIN = "SYSIN";
    /** 覚え書きの既定の出し先。 */
    protected static final String SYSPRINT = "SYSPRINT";

    /**
     * DD が指すファイル。
     *
     * @return 結び付けも既定の場所もなければ {@code null}
     */
    protected static Path pathOf(ProgramContext context, String ddName) {
        return context.catalog().resolve(ddName);
    }

    /** 制御文を読む。1 行が 1 文である。行末の空白は落とす。 */
    protected static List<String> control(ProgramContext context, String ddName) {
        Path path = pathOf(context, ddName);
        if (!Files.isReadable(path)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (byte[] line : lines(readBytes(path), context.codePage())) {
            String text = context.codePage().decode(line).stripTrailing();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    /** 覚え書きを 1 行書く。行き先は {@code SYSPRINT} である。 */
    protected static void print(ProgramContext context, String text) {
        print(context, SYSPRINT, text);
    }

    /**
     * 行を 1 つ書く。
     *
     * <p>結び付けがなければ実行時の出力へ回す。道具によって覚え書きの行き先が違う
     * ({@code SYSPRINT} / {@code TOOLMSG}) ので、行き先を言えるようにしてある。
     */
    protected static void print(ProgramContext context, String ddName, String text) {
        if (!context.catalog().isAssigned(ddName)) {
            context.display(context.codePage().encode(text), true, false);
            return;
        }
        Path path = pathOf(context, ddName);
        byte[] line = context.codePage().encode(text + "\n");
        try {
            Files.write(path, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            // 行の並びであることを属性に残す。読み返す側が切れ目を決められる
            new DataSetAttributes(RecordFormat.LINE, 132, context.codePage()).write(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    /** バイト列を行へ切る。区切りはコードページの改行である。 */
    protected static List<byte[]> lines(byte[] bytes, CodePage codePage) {
        byte newline = codePage.encode("\n")[0];
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == newline) {
                out.add(Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            out.add(Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return out;
    }

    protected static byte[] readBytes(Path path) {
        try {
            return Files.isReadable(path) ? Files.readAllBytes(path) : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    protected static void writeBytes(Path path, byte[] bytes) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }
}
