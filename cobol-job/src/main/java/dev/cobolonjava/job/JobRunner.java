package dev.cobolonjava.job;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.job.utility.Utilities;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 内部ジョブモデルを実行する (要件 FR-130, FR-134, FR-136)。
 *
 * <p>解釈するのは<b>内部モデルだけ</b>である。JCL も宣言的形式も知らない。
 * どちらのフロントエンドから来ても同じ結果になるのは、両方がここへ落ちるからである。
 *
 * <h2>ステップは独立している</h2>
 * <p>ステップごとに新しい実行時の入口を作る。作業場所も、読み込んだ副プログラムも、
 * 開いたファイルも、復帰コードも<b>持ち越さない</b>。持ち越すのはジョブの状態だけであり、
 * それは復帰コードと異常終了の有無である。
 *
 * <h2>SYSOUT はスプールを通る</h2>
 * <p>ホストでは {@code SYSOUT} へ書いたものがスプールに溜まり、ジョブが終わってから
 * 印字される。ここでも作業領域のファイルへ溜め、ステップが終わったところで流し出す。
 * プログラムから見れば普通のデータセットであり、特別扱いが要らない。
 */
public final class JobRunner {

    private final Path workDirectory;
    private final ClassLoader loader;
    private final OutputStream out;
    private final CodePage codePage;
    /** 結び付けられていない DD 名が指す先。データセットの置き場である。 */
    private Path base = Path.of(".");

    public JobRunner(Path workDirectory, ClassLoader loader, OutputStream out, CodePage codePage) {
        this.workDirectory = workDirectory;
        this.loader = loader;
        this.out = out;
        this.codePage = codePage;
    }

    /** 既定のコードページで実行する構成。 */
    public static JobRunner at(Path workDirectory, ClassLoader loader, OutputStream out) {
        return new JobRunner(workDirectory, loader, out, CodePages.DEFAULT);
    }

    /** データセットの置き場を差し替える。書かれていない DD 名はこの下を指す。 */
    public JobRunner withBase(Path value) {
        this.base = value;
        return this;
    }

    /** ステップがどうなったか。 */
    public enum Status {

        /** 動いた。 */
        EXECUTED,

        /** 条件が成り立たず飛ばされた。 */
        BYPASSED,

        /** 異常終了した。 */
        ABENDED
    }

    /**
     * ステップ 1 個の結果。
     *
     * @param returnCode 復帰コード。飛ばされたステップでは {@code -1}
     * @param failure    異常終了したときの理由。ほかは {@code null}
     */
    public record StepOutcome(String name, Status status, int returnCode, String failure) {
    }

    /**
     * ジョブの結果。
     *
     * @param returnCode ジョブ全体の終了コード。いちばん大きいステップの復帰コードである
     */
    public record Result(int returnCode, List<StepOutcome> steps, JobState state) {

        public Result {
            steps = List.copyOf(steps);
        }

        /** ステップの結果を名前で引く。 */
        public StepOutcome step(String name) {
            return steps.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** ジョブを実行する。 */
    public Result run(Job job) {
        JobState state = new JobState();
        List<StepOutcome> outcomes = new ArrayList<>();
        for (Step step : job.steps()) {
            outcomes.add(runStep(job, step, state));
        }
        return new Result(state.highest(), outcomes, state);
    }

    private StepOutcome runStep(Job job, Step step, JobState state) {
        if (!allowed(step, state)) {
            return new StepOutcome(step.name(), Status.BYPASSED, -1, null);
        }
        Path stepWork = workDirectory.resolve(job.name() + "." + step.name());
        createDirectory(stepWork);
        DataSetCatalog catalog = new DataSetCatalog(base);
        List<Path> spools = new ArrayList<>();
        for (DdAssignment assignment : step.dd()) {
            spools.addAll(assign(catalog, stepWork, assignment));
        }

        ProgramContext context = ProgramContext.standard()
                .withCodePage(codePage)
                .withOutput(out)
                .withCatalog(catalog);
        try {
            // ユーティリティは翻訳された資産ではない。名前で先に引き当てる (要件 FR-137)
            CobolProgram utility = Utilities.find(step.program());
            if (utility != null) {
                utility.runFresh(context, arguments(step));
            } else {
                ProgramContext.Loaded loaded = context.resolve(step.program(), loader);
                loaded.program().runFresh(context, arguments(step));
            }
        } catch (ProgramNotFoundException e) {
            // ロードモジュールが見つからないのは異常終了である (要件 FR-141 の S806 相当)
            return abend(step, state, e.getMessage());
        } catch (RuntimeException e) {
            return abend(step, state, describe(e));
        } finally {
            spools.forEach(this::spill);
        }
        int returnCode = context.returnCode();
        state.completed(step.name(), returnCode);
        return new StepOutcome(step.name(), Status.EXECUTED, returnCode, null);
    }

    private StepOutcome abend(Step step, JobState state, String failure) {
        state.abended(step.name());
        return new StepOutcome(step.name(), Status.ABENDED, -1, failure);
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /**
     * ステップを動かすか。
     *
     * <p>先行ステップが異常終了していれば、以降は飛ばす。{@code COND=EVEN} と
     * {@code COND=ONLY} だけがそれを覆す。
     */
    private static boolean allowed(Step step, JobState state) {
        if (state.abended() && !step.condition().survivesAbend()) {
            return false;
        }
        return step.condition().allows(state);
    }

    /**
     * DD 割当を目録へ入れる。
     *
     * @return ステップのあとで流し出すスプールのファイル
     */
    private List<Path> assign(DataSetCatalog catalog, Path stepWork, DdAssignment assignment) {
        String name = assignment.name();
        switch (assignment.target()) {
            case DdTarget.DataSet target -> catalog.assign(name, target.path());
            case DdTarget.Sysout ignored -> {
                Path spool = stepWork.resolve(name + ".sysout");
                catalog.assign(name, spool);
                return List.of(spool);
            }
            case DdTarget.Dummy ignored -> {
                // 読めば即座に終わり、書いたものは捨てられる。空のファイルがそれである
                Path empty = stepWork.resolve(name + ".dummy");
                writeBytes(empty, new byte[0]);
                catalog.assign(name, empty);
            }
            case DdTarget.Inline target -> {
                Path inline = stepWork.resolve(name + ".inline");
                writeBytes(inline, target.data());
                // 埋め込んだデータは行の並びである。区切りはコードページの改行になる
                new DataSetAttributes(RecordFormat.LINE, 80, codePage).write(inline);
                catalog.assign(name, inline);
            }
            case DdTarget.Concatenation target -> {
                Path joined = stepWork.resolve(name + ".concat");
                writeBytes(joined, concatenate(target.parts(), name));
                copyAttributes(target.parts(), joined);
                catalog.assign(name, joined);
            }
        }
        return List.of();
    }

    /**
     * 連結したデータセットを 1 つにまとめる (要件 FR-131)。
     *
     * <p>読むときは<b>並べた順に 1 つのファイルに見える</b>。ここでは作業領域へ書き出して
     * 1 つのファイルにしている。読むだけの使い方でしか意味を持たない (暫定判断 P-044)。
     */
    private byte[] concatenate(List<DdTarget> parts, String name) {
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (DdTarget part : parts) {
            byte[] bytes = switch (part) {
                case DdTarget.DataSet dataSet -> readBytes(dataSet.path());
                case DdTarget.Inline inline -> inline.data();
                case DdTarget.Dummy ignored -> new byte[0];
                default -> throw new IllegalArgumentException(
                        "a concatenated DD holds data sets: " + name);
            };
            joined.writeBytes(bytes);
        }
        return joined.toByteArray();
    }

    /** レコードの切れ目は、連結の<b>先頭のデータセット</b>のものに揃える。 */
    private void copyAttributes(List<DdTarget> parts, Path joined) {
        for (DdTarget part : parts) {
            if (!(part instanceof DdTarget.DataSet dataSet)) {
                continue;
            }
            Path sidecar = DataSetAttributes.sidecarOf(dataSet.path());
            if (Files.isReadable(sidecar)) {
                DataSetAttributes.read(dataSet.path()).write(joined);
                return;
            }
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.isReadable(path) ? Files.readAllBytes(path) : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    /** スプールに溜まったものをジョブの出力へ流す。 */
    private void spill(Path spool) {
        if (!Files.isReadable(spool)) {
            return;
        }
        try {
            for (byte[] record : splitLines(Files.readAllBytes(spool))) {
                out.write(codePage.decode(record).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write(System.lineSeparator().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot spill " + spool, e);
        }
    }

    /** スプールの中身を行へ切る。区切りはコードページの改行である。 */
    private List<byte[]> splitLines(byte[] bytes) {
        byte newline = codePage.encode("\n")[0];
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == newline) {
                out.add(java.util.Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            out.add(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return out;
    }

    /**
     * {@code PARM=} を引数の並びにする (要件 FR-134)。
     *
     * <p>参照実装と同じ形で渡す。<b>先頭 2 バイトが長さ</b>で、そのあとに中身が続く。
     * 受け取る側は連絡節に {@code 01 PARM. 05 LEN PIC S9(4) COMP. 05 TEXT PIC X(n).}
     * と書いてある。
     */
    private DataView[] arguments(Step step) {
        if (step.parm() == null) {
            return new DataView[0];
        }
        byte[] text = codePage.encode(step.parm());
        Storage storage = Storage.allocate(text.length + 2);
        storage.view(0, 2).setBytes(new byte[] {
            (byte) (text.length >> 8), (byte) text.length,
        });
        if (text.length > 0) {
            storage.view(2, text.length).setBytes(text);
        }
        return new DataView[] {storage.whole()};
    }

    private static void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + path, e);
        }
    }

    private static void writeBytes(Path path, byte[] bytes) {
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }
}
