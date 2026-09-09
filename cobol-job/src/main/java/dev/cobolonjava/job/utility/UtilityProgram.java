package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAllocation;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.DataSetIoException;
import dev.cobolonjava.runtime.file.DataSetOpenException;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.FileOperationException;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.IOException;
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

    /**
     * 開く段の検査を通したパス (要件 FR-113, 暫定判断 P-053)。
     *
     * <p>ユーティリティはデータセットを開かずに生バイトを読み書きする。それでも<b>開けない
     * ものは読めない</b>ことに変わりはない。この検査を通さないと、区分データセットの無い
     * メンバを読ませたときに空として写し、正常終了してしまう。ホストの {@code IEBGENER} は
     * そこで {@code S013} になる。
     *
     * <p>無いだけのデータセットはここでは止めない。道具ごとに言うことが違う
     * ({@code IEB000I} / {@code ICE603I} / {@code IDC3300I}) からであり、呼ぶ側が決める。
     */
    protected static Path opened(ProgramContext context, String ddName) {
        Path path = pathOf(context, ddName);
        // 返る状態コード (35) は捨てる。無いだけのデータセットは道具の言い分で報せる
        DataSetAllocation.opening(path, OpenMode.INPUT, false,
                context.catalog().isMemberOfLibrary(ddName));
        return path;
    }

    /**
     * 書きにいく側の検査 (要件 FR-113、暫定判断 P-059 の解消)。
     *
     * <p>{@link #opened} との違いは<b>無くてよい</b>ことである。これから作るのだから、
     * 無いメンバを {@code S013} にしてはならない。区分データセットそのものを名指した
     * ときだけは、どのメンバへ書くのか決まっていないので止まる。
     *
     * <p>代わりにディレクトリの空きを見る。ホストの道具も、入りきらなければそこで
     * 異常終了する。
     */
    protected static Path created(ProgramContext context, String ddName) {
        Path path = pathOf(context, ddName);
        boolean member = context.catalog().isMemberOfLibrary(ddName);
        DataSetAllocation.opening(path, OpenMode.OUTPUT, false, member);
        Path library = path.getParent();
        if (member && library != null) {
            room(context, library, path.getFileName().toString());
        }
        return path;
    }

    /**
     * バイト列を読む。形が壊れていれば、そこで止める (要件 FR-141, 暫定判断 P-053)。
     *
     * <p>切れないバイト列を黙って写せば、<b>壊れたデータセットを写して正常終了する</b>。
     * 翻訳された資産なら {@code 30} が立ち、{@code FILE STATUS} を書いていなければ
     * {@code S001} になる。ユーティリティには状態コードを返す先がないので、そのまま
     * {@code S001} で止める。
     */
    protected static byte[] readSound(ProgramContext context, Path path) {
        byte[] bytes = readBytes(path);
        if (Records.damaged(bytes, DataSetAttributes.read(path))) {
            throw new FileOperationException(named(context, path), FileStatus.IO_ERROR);
        }
        return bytes;
    }

    /**
     * バイト列を書く。割り当てた領域を越えれば、そこで止める (要件 FR-141, 暫定判断 P-053)。
     *
     * <p>JCL の {@code SPACE=} である。ホストのユーティリティは書けなくなったところで
     * 異常終了し、途中まで書いたものを残す。ここでは<b>1 度に書き出す</b>ので途中までは
     * 残らない (暫定判断 P-038)。
     */
    protected static void writeSound(ProgramContext context, Path path, byte[] bytes) {
        String ddName = context.catalog().ddNameFor(path);
        long limit = ddName == null ? 0 : context.catalog().limitOf(ddName);
        if (limit > 0 && bytes.length > limit) {
            throw new FileOperationException(named(context, path), FileStatus.NO_SPACE);
        }
        writeBytes(path, bytes);
    }

    /**
     * 新しいメンバがディレクトリに入るか (要件 FR-113, FR-141、暫定判断 P-059 の解消)。
     *
     * <p>ホストのディレクトリは<b>あらかじめ取った大きさしかない</b>。使い切れば、
     * データを置く場所が空いていてもメンバを増やせない。翻訳した資産の
     * {@code OPEN OUTPUT} だけで効かせて道具で効かせずにいると、<b>同じライブラリが
     * 書き手によって入る数を変える</b>ことになる。
     *
     * <p>すでにある名前へ書き直すだけなら項目は増えないので、いつでも通る。
     */
    protected static void room(ProgramContext context, Path library, String name) {
        int blocks = DataSetAttributes.read(library).directoryBlocks();
        if (!PartitionedDataSet.roomFor(library, context.codePage(), blocks, name)) {
            throw new DataSetOpenException("no room in the directory of "
                    + library.getFileName(), PartitionedDataSet.memberOf(library, name));
        }
    }

    /** 覚え書きに書く名前。DD 名で言えるならそちらのほうが分かる。 */
    private static String named(ProgramContext context, Path path) {
        String ddName = context.catalog().ddNameFor(path);
        return ddName != null ? ddName : String.valueOf(path.getFileName());
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
            throw new DataSetIoException("write", path, e);
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
            throw new DataSetIoException("read", path, e);
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
            throw new DataSetIoException("write", path, e);
        }
    }
}
