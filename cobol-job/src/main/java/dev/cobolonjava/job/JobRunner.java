package dev.cobolonjava.job;

import dev.cobolonjava.runtime.abend.Abend;
import dev.cobolonjava.runtime.abend.AbendCode;
import dev.cobolonjava.runtime.abend.Diagnosis;
import dev.cobolonjava.runtime.abend.DumpLevel;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.job.utility.Utilities;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        ABENDED,

        /** 割当てに失敗した。ホストの JCL エラーにあたる。 */
        FAILED,

        /** 先行ステップの JCL エラーで流された。 */
        FLUSHED
    }

    /**
     * 通らなかったジョブの終了コード。
     *
     * <p>異常終了と JCL エラーはどちらも<b>ジョブが通っていない</b>ことを表す。復帰コードは
     * 立たないので、いちばん大きい復帰コードをそのまま返すと {@code 0} になり、後続の運用が
     * 「通った」と読み違える。どちらだったかはステップの結末に残る。
     */
    private static final int NOT_COMPLETED = 12;

    /**
     * ステップ 1 個の結果。
     *
     * @param returnCode 復帰コード。飛ばされたステップでは {@code -1}
     * @param failure    異常終了したときの理由。ほかは {@code null}
     * @param abendCode  異常終了コード。分からなければ {@code null} (要件 FR-141)
     */
    public record StepOutcome(String name, Status status, int returnCode, String failure,
                              AbendCode abendCode) {

        /** コードの分からない結末。 */
        public StepOutcome(String name, Status status, int returnCode, String failure) {
            this(name, status, returnCode, failure, null);
        }
    }

    /**
     * ジョブの結果。
     *
     * @param returnCode ジョブ全体の終了コード。いちばん大きいステップの復帰コードである。
     *                   通らなかったジョブでは {@code 0} にならない
     */
    public record Result(int returnCode, List<StepOutcome> steps, JobState state) {

        public Result {
            steps = List.copyOf(steps);
        }

        /** どれかのステップが異常終了したか (要件 FR-141)。 */
        public boolean abended() {
            return steps.stream().anyMatch(step -> step.status() == Status.ABENDED);
        }

        /** 割当てに失敗したステップがあったか。 */
        public boolean failed() {
            return steps.stream().anyMatch(step -> step.status() == Status.FAILED);
        }

        /** ステップの結果を名前で引く。 */
        public StepOutcome step(String name) {
            return steps.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * ジョブを実行する。
     *
     * <h2>割当てに失敗したら残りは流す</h2>
     * <p>{@code DISP} が言っていることと実際が食い違えば、ステップは動かない。これは
     * プログラムの異常終了とは別のものであり、{@code COND=EVEN} でも覆せない。
     * ホストでも以降のステップは<b>実行されずに流される</b>。
     */
    public Result run(Job job) {
        JobState state = new JobState();
        List<StepOutcome> outcomes = new ArrayList<>();
        // データセットごとの、いちばん新しい処置。ジョブの終わりに PASS を片付ける
        Map<Path, Disposition.Action> lastAction = new LinkedHashMap<>();
        boolean failed = false;
        for (Step step : job.steps()) {
            if (failed) {
                outcomes.add(new StepOutcome(step.name(), Status.FLUSHED, -1, null));
                continue;
            }
            StepOutcome outcome = runStep(job, step, state, lastAction);
            outcomes.add(outcome);
            failed = outcome.status() == Status.FAILED;
        }
        endOfJob(job, lastAction);
        // 通らなかったジョブが 0 を返さないようにする。異常終了も JCL エラーも同じである
        int code = failed || state.abended()
                ? Math.max(state.highest(), NOT_COMPLETED)
                : state.highest();
        return new Result(code, outcomes, state);
    }

    private StepOutcome runStep(Job job, Step step, JobState state,
                                Map<Path, Disposition.Action> lastAction) {
        if (!allowed(step, state)) {
            return new StepOutcome(step.name(), Status.BYPASSED, -1, null);
        }
        Path stepWork = workDirectory.resolve(job.name() + "." + step.name());
        createDirectory(stepWork);
        Allocation allocation;
        try {
            allocation = allocate(step, stepWork, temporaryArea(job));
        } catch (AllocationFailure e) {
            // 割当てに失敗したステップは動かない。プログラムは呼ばれてすらいない
            deleteTree(stepWork);
            return new StepOutcome(step.name(), Status.FAILED, -1, e.getMessage());
        }

        ProgramContext context = ProgramContext.standard()
                .withCodePage(codePage)
                .withOutput(out)
                .withCatalog(allocation.catalog());
        context.setDumpLevel(dumpLevelOf(allocation.catalog()));
        String failure = null;
        AbendCode code = null;
        RuntimeException thrown = null;
        try {
            // ユーティリティは翻訳された資産ではない。名前で先に引き当てる (要件 FR-137)
            CobolProgram utility = Utilities.find(step.program());
            if (utility != null) {
                utility.runFresh(context, arguments(step));
            } else {
                ProgramContext.Loaded loaded = context.resolve(step.program(), loader);
                loaded.program().runFresh(context, arguments(step));
            }
        } catch (RuntimeException e) {
            // 実行を抜けた例外は異常終了である。コードは条件そのものが名乗る (要件 FR-141)
            failure = describe(e);
            code = Abend.codeOf(e);
            thrown = e;
        }
        // 閉じていないファイルをここで閉じる。異常終了しても、そこまでに書いたものは残る
        context.closeFiles();
        boolean abended = failure != null;
        if (abended) {
            // 覚え書きはスプールを流す前に書く。CEEDUMP を SYSOUT へ向けたジョブでも
            // 同じ流れに乗る (要件 FR-142)
            diagnose(allocation.catalog(), code, thrown, context);
        }
        allocation.spools().forEach(this::spill);
        dispose(allocation.dataSets(), abended, lastAction);
        // 失敗したステップの作業領域は残す。何が起きたのかを見られるほうが役に立つ
        if (!abended) {
            deleteTree(stepWork);
        }
        if (abended) {
            return abend(step, state, failure, code);
        }
        int returnCode = context.returnCode();
        state.completed(step.name(), returnCode);
        return new StepOutcome(step.name(), Status.EXECUTED, returnCode, null);
    }

    /** 診断出力の行き先。ジョブが {@code CEEDUMP} を書いていなければジョブの出力へ回す。 */
    private static final String CEEDUMP = "CEEDUMP";
    /** 実行時オプションを書く DD 名。 */
    private static final String CEEOPTS = "CEEOPTS";

    /**
     * 異常終了の診断出力を書く (要件 FR-142)。
     *
     * <p>行き先はホストと同じく {@code CEEDUMP} である。書かれていなければジョブの出力へ
     * 回す。黙って捨てると、本番で一度だけ起きた事故を追えなくなる。
     */
    private void diagnose(DataSetCatalog catalog, AbendCode code, RuntimeException thrown,
                          ProgramContext context) {
        List<String> lines = Diagnosis.of(code, thrown, context);
        if (lines.isEmpty()) {
            return;
        }
        if (!catalog.isAssigned(CEEDUMP)) {
            for (String line : lines) {
                context.display(codePage.encode(line), true, true);
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        Path path = catalog.resolve(CEEDUMP);
        writeBytes(path, codePage.encode(sb.toString()));
        new DataSetAttributes(RecordFormat.LINE, 132, codePage).write(path);
    }

    /**
     * {@code TERMTHDACT} の指定 (要件 FR-143)。
     *
     * <p>ホストと同じく {@code CEEOPTS} の DD から読む。書かれていなければ既定の
     * {@code TRACE} である。
     */
    private DumpLevel dumpLevelOf(DataSetCatalog catalog) {
        if (!catalog.isAssigned(CEEOPTS)) {
            return DumpLevel.TRACE;
        }
        String text = new String(readBytes(catalog.resolve(CEEOPTS)),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        String decoded = codePage.decode(text.getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1));
        for (String line : decoded.split("\n")) {
            String written = line.trim().toUpperCase(java.util.Locale.ROOT);
            int open = written.indexOf('(');
            if (!written.startsWith("TERMTHDACT") || open < 0 || !written.endsWith(")")) {
                continue;
            }
            DumpLevel level = DumpLevel.of(written.substring(open + 1, written.length() - 1));
            if (level != null) {
                return level;
            }
        }
        return DumpLevel.TRACE;
    }

    private StepOutcome abend(Step step, JobState state, String failure, AbendCode code) {
        state.abended(step.name(), code);
        return new StepOutcome(step.name(), Status.ABENDED, -1, failure, code);
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
     * ステップの割当て。
     *
     * @param catalog  DD 名から実際のファイルを引く目録
     * @param spools   ステップのあとで流し出すスプールのファイル
     * @param dataSets ステップのあとで処置を効かせるデータセット
     */
    private record Allocation(DataSetCatalog catalog, List<Path> spools,
                              List<Held> dataSets) {
    }

    /** 割当てに失敗した。ホストの JCL エラーにあたる。 */
    private static final class AllocationFailure extends RuntimeException {

        AllocationFailure(String message) {
            super(message);
        }
    }

    /** ステップのすべての DD を割り当てる。 */
    private Allocation allocate(Step step, Path stepWork, Path temporary) {
        DataSetCatalog catalog = new DataSetCatalog(base);
        List<Path> spools = new ArrayList<>();
        List<Held> dataSets = new ArrayList<>();
        for (DdAssignment assignment : step.dd()) {
            spools.addAll(assign(catalog, stepWork, temporary, assignment, dataSets));
        }
        return new Allocation(catalog, spools, dataSets);
    }

    /** 一時データセットの置き場。ジョブごとに 1 つであり、終われば消える。 */
    private Path temporaryArea(Job job) {
        return workDirectory.resolve(job.name() + ".temp");
    }

    /**
     * ステップのあとで処置を効かせる相手。
     *
     * @param temporary ジョブが終われば消えるか
     */
    private record Held(Path path, Disposition disposition, boolean temporary) {
    }

    /**
     * DD 割当を目録へ入れる。
     *
     * @return ステップのあとで流し出すスプールのファイル
     */
    private List<Path> assign(DataSetCatalog catalog, Path stepWork, Path temporary,
                              DdAssignment assignment, List<Held> dataSets) {
        String name = assignment.name();
        if (assignment.space() != DdAssignment.UNLIMITED) {
            // 割り当てた大きさを目録へ伝える。使い切れば書けなくなる
            catalog.limit(name, assignment.space());
        }
        switch (assignment.target()) {
            case DdTarget.DataSet target -> {
                hold(catalog, name, target.path(), target.disposition(), false, dataSets);
            }
            case DdTarget.Temporary target -> {
                // 置き場を決めるのはここである。ジョブが場所を知らないので、
                // 同じジョブを同時に流しても互いの作業ファイルを踏まない
                createDirectory(temporary);
                hold(catalog, name, temporary.resolve(target.name()), target.disposition(),
                        true, dataSets);
            }
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
                for (DdTarget part : target.parts()) {
                    if (part instanceof DdTarget.DataSet dataSet) {
                        allocateDataSet(name, dataSet.path(), dataSet.disposition());
                        dataSets.add(new Held(dataSet.path(), dataSet.disposition(), false));
                    } else if (part instanceof DdTarget.Temporary held) {
                        createDirectory(temporary);
                        Path path = temporary.resolve(held.name());
                        allocateDataSet(name, path, held.disposition());
                        dataSets.add(new Held(path, held.disposition(), true));
                    }
                }
                Path joined = stepWork.resolve(name + ".concat");
                writeBytes(joined, concatenate(target.parts(), temporary, name));
                copyAttributes(target.parts(), joined);
                catalog.assign(name, joined);
            }
        }
        return List.of();
    }

    /**
     * データセット 1 個を割り当てる (要件 FR-133)。
     *
     * <p>{@code DISP} の 1 つ目が言っていることと、実際にあるかどうかが食い違えば、
     * <b>ステップは動かない</b>。黙って作り直したり、無いものを空として読ませたりすると、
     * 名前を打ち間違えたジョブが「0 件処理した」と言って正常終了してしまう。
     */
    /** 割り当てて目録へ入れ、あとで処置を効かせる相手として覚える。 */
    private void hold(DataSetCatalog catalog, String ddName, Path path,
                      Disposition disposition, boolean temporary, List<Held> dataSets) {
        allocateDataSet(ddName, path, disposition);
        dataSets.add(new Held(path, disposition, temporary));
        catalog.assign(ddName, path);
        if (disposition.status() == Disposition.Status.MOD) {
            // DISP=MOD は OPEN OUTPUT を末尾への書き足しへ変える (要件 FR-133)
            catalog.appendTo(ddName);
        }
    }

    private void allocateDataSet(String ddName, Path path, Disposition disposition) {
        boolean exists = Files.exists(path);
        switch (disposition.status()) {
            case NEW -> {
                if (exists) {
                    throw new AllocationFailure("IEF344I " + ddName
                            + " - DUPLICATE NAME ON DIRECT ACCESS: " + path.getFileName());
                }
                // 割り当てた時点で場所は取れている。中身が無いだけである
                writeBytes(path, new byte[0]);
            }
            case OLD, SHR -> {
                if (!exists) {
                    throw new AllocationFailure("IEF212I " + ddName
                            + " - DATA SET NOT FOUND: " + path.getFileName());
                }
            }
            case MOD -> {
                if (!exists) {
                    writeBytes(path, new byte[0]);
                }
            }
            case ANY -> {
                // 状態を言っていない。確かめることも作ることもない
            }
        }
    }

    /**
     * ステップが終わったところで処置を効かせる (要件 FR-133)。
     *
     * <p>{@code DELETE} なら消す。{@code KEEP} / {@code CATLG} / {@code UNCATLG} /
     * {@code PASS} はどれも残す。目録をディレクトリそのものとしているので、
     * <b>載せる・外すの区別がない</b> (暫定判断 P-045)。
     */
    private void dispose(List<Held> dataSets, boolean abended,
                         Map<Path, Disposition.Action> lastAction) {
        for (Held held : dataSets) {
            Disposition.Action action = abended
                    ? held.disposition().abnormal()
                    : held.disposition().normal();
            if (action == Disposition.Action.DELETE) {
                remove(held.path());
                remove(DataSetAttributes.sidecarOf(held.path()));
                lastAction.remove(held.path());
                continue;
            }
            lastAction.put(held.path(), action);
        }
    }

    /**
     * ジョブの終わりの後始末 (要件 FR-133)。
     *
     * <p>一時データセットは消える。ジョブの間だけ存在するものだからである。
     *
     * <p>{@code PASS} で残したものも消える。渡すのは<b>このジョブの後続ステップへ</b>で
     * あって、次のジョブへではない。残したければ {@code CATLG} と書く。
     */
    private void endOfJob(Job job, Map<Path, Disposition.Action> lastAction) {
        for (Map.Entry<Path, Disposition.Action> entry : lastAction.entrySet()) {
            if (entry.getValue() == Disposition.Action.PASS) {
                remove(entry.getKey());
                remove(DataSetAttributes.sidecarOf(entry.getKey()));
            }
        }
        deleteTree(temporaryArea(job));
    }

    /**
     * 連結したデータセットを 1 つにまとめる (要件 FR-131)。
     *
     * <p>読むときは<b>並べた順に 1 つのファイルに見える</b>。ここでは作業領域へ書き出して
     * 1 つのファイルにしている。読むだけの使い方でしか意味を持たない (暫定判断 P-044)。
     */
    private byte[] concatenate(List<DdTarget> parts, Path temporary, String name) {
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (DdTarget part : parts) {
            byte[] bytes = switch (part) {
                case DdTarget.DataSet dataSet -> readBytes(dataSet.path());
                case DdTarget.Temporary held -> readBytes(temporary.resolve(held.name()));
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
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + path, e);
        }
    }

    /**
     * 作業領域を片付ける (暫定判断 P-043 の解消)。
     *
     * <p>スプールも埋め込みデータも連結の写しも、<b>そのステップの間だけ要るもの</b>である。
     * 残しておくと繰り返し動かすたびに増え続ける。
     */
    private static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path each : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + path, e);
        }
    }
}
