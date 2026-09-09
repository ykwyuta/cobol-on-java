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
    /**
     * 名前から置き場を引く目録 (要件 FR-131、暫定判断 P-045 の解消)。
     *
     * <p>置き場を差し替えられるので、実行のたびに開き直す。目録は置き場の上に載っている
     * ものであり、置き場が変われば別の目録になる。
     */
    private SystemCatalog system;
    /**
     * 世代データグループの基底 (要件 FR-114)。
     *
     * <p>目録と分けて持つ。基底の定義は世代が 1 つも無くなっても残るものであり、
     * データセットの在り処とは寿命が違う。
     */
    private GenerationDataGroup groups;
    /**
     * このジョブが割り当てたデータセットの名前 (要件 FR-131)。
     *
     * <p>目録に載っていなくても、<b>同じジョブの後続ステップからは見える</b>。ホストでは
     * ジョブが割り当てたデータセットを覚えていて、あとのステップが同じ名前を書けば
     * そこから引く。目録を引き直すのは次のジョブからである。
     *
     * <p>{@code DISP=(NEW,KEEP)} で作ったものを次のステップが {@code DISP=OLD} で使う、
     * という書き方が通るのはこれによる。通らなくなるのは<b>ジョブをまたいだとき</b>で
     * あり、暫定判断 P-045 が挙げていた危うさもそこにあった。
     */
    private final java.util.Set<String> allocated = new java.util.HashSet<>();

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
        system = new SystemCatalog(base);
        groups = new GenerationDataGroup(base);
        allocated.clear();
        // 相対世代を絶対名へ直すのはここだけである。以降のステップは番号を知らない
        Job resolved = withGenerations(job);
        // データセットごとの、いちばん新しい処置。ジョブの終わりに PASS を片付ける
        Map<Path, Outcome> lastAction = new LinkedHashMap<>();
        boolean failed = false;
        for (Step step : resolved.steps()) {
            if (failed) {
                outcomes.add(new StepOutcome(step.name(), Status.FLUSHED, -1, null));
                continue;
            }
            StepOutcome outcome = runStep(resolved, step, state, lastAction);
            outcomes.add(outcome);
            failed = outcome.status() == Status.FAILED;
        }
        endOfJob(resolved, lastAction);
        // 通らなかったジョブが 0 を返さないようにする。異常終了も JCL エラーも同じである
        int code = failed || state.abended()
                ? Math.max(state.highest(), NOT_COMPLETED)
                : state.highest();
        return new Result(code, outcomes, state);
    }

    /**
     * 相対世代を絶対名へ直す (要件 FR-114)。
     *
     * <h2>ジョブの初めに 1 度だけである</h2>
     * <p>{@code (0)} が指すのは<b>ジョブが始まった時点の</b>いちばん新しい世代であり、
     * ステップ 1 が {@code (+1)} で世代を作っても動かない。ステップ 2 の {@code (+1)} も
     * ステップ 1 と同じデータセットを指す。ホストの JCL がそう解釈するからである。
     *
     * <p>ステップごとに引き直すと、1 つのファイルへ 2 度書くつもりのジョブが世代を
     * 2 つ作り、しかも<b>片方だけが残る</b>。実機との差が数字ではなくデータの欠落として
     * 出るので、ここは動かせない。
     *
     * <h2>基底が無ければ直さない</h2>
     * <p>登録されていない名前は相対世代のまま残す。割当ての段で JCL エラーになる。
     * 黙って絶対名を組み立てると、{@code DEFINE GDG} を忘れたジョブが<b>世代のようで
     * 世代でないデータセット</b>を作って正常終了してしまう。実機では動かないジョブである。
     */
    private Job withGenerations(Job job) {
        Map<String, Integer> starts = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        for (Step step : job.steps()) {
            List<DdAssignment> dd = new ArrayList<>();
            for (DdAssignment assignment : step.dd()) {
                dd.add(new DdAssignment(assignment.name(),
                        withGenerations(assignment.target(), starts),
                        assignment.space(), assignment.directoryBlocks()));
            }
            steps.add(new Step(step.name(), step.program(), step.parm(), dd, step.condition()));
        }
        return new Job(job.name(), steps);
    }

    /** 割当 1 個の相対世代を直す。連結は中の各段をたどる。 */
    private DdTarget withGenerations(DdTarget target, Map<String, Integer> starts) {
        if (target instanceof DdTarget.Concatenation concatenation) {
            List<DdTarget> parts = new ArrayList<>();
            for (DdTarget part : concatenation.parts()) {
                parts.add(withGenerations(part, starts));
            }
            return new DdTarget.Concatenation(parts);
        }
        if (!(target instanceof DdTarget.DataSet dataSet) || !dataSet.relativeGeneration()
                || !groups.defined(dataSet.name())) {
            return target;
        }
        // 基底ごとに 1 度だけ数える。同じジョブの中では何度書いても同じ番号から数える
        int start = starts.computeIfAbsent(key(dataSet.name()),
                ignored -> GenerationDataGroup.current(base, system, dataSet.name()));
        return dataSet.named(
                GenerationDataGroup.nameOf(dataSet.name(), start + dataSet.generation()));
    }

    private StepOutcome runStep(Job job, Step step, JobState state,
                                Map<Path, Outcome> lastAction) {
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
                DataView[] programArguments = arguments(step);
                loaded.validateArguments(programArguments);
                loaded.program().runFresh(context, programArguments);
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
     * @param name      目録に載っている名前。一時データセットは載らないので {@code null}。
     *                  区分データセットのメンバでは<b>ライブラリの名前</b>である。目録が
     *                  覚えるのはデータセットであってメンバではない
     * @param path      {@code DELETE} が消すもの。メンバを指した DD ならメンバだけを消す
     * @param temporary ジョブが終われば消えるか
     */
    private record Held(String name, Path path, Disposition disposition, boolean temporary) {
    }

    /**
     * データセット 1 個に、いちばん新しく効いた処置。
     *
     * @param name 目録に載っている名前。一時データセットは {@code null}
     */
    private record Outcome(String name, Disposition.Action action) {
    }

    /**
     * 名前を引いた結果 (要件 FR-113, FR-131)。
     *
     * @param dataSet データセットそのものの場所。区分ならライブラリのディレクトリである
     * @param path    DD が指すもの。メンバを書いていればライブラリの下のメンバ
     */
    private record Place(Path dataSet, Path path) {
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
                Place place = locate(target);
                allocateDataSet(name, target, place, assignment.partitioned(),
                        assignment.directoryBlocks());
                dataSets.add(new Held(target.name(), place.path(), target.disposition(), false));
                catalog.assign(name, place.path());
                if (target.partitioned()) {
                    // メンバが無いことが分かるのは開く段である (要件 FR-113)
                    catalog.memberOfLibrary(name);
                }
                if (target.disposition().status() == Disposition.Status.MOD) {
                    // DISP=MOD は OPEN OUTPUT を末尾への書き足しへ変える (要件 FR-133)
                    catalog.appendTo(name);
                }
            }
            case DdTarget.Temporary target -> {
                // 置き場を決めるのはここである。ジョブが場所を知らないので、
                // 同じジョブを同時に流しても互いの作業ファイルを踏まない
                createDirectory(temporary);
                Path path = temporary.resolve(target.name());
                allocateTemporary(name, path, target.disposition());
                dataSets.add(new Held(null, path, target.disposition(), true));
                catalog.assign(name, path);
                if (target.disposition().status() == Disposition.Status.MOD) {
                    catalog.appendTo(name);
                }
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
                // 連結の各段も、それぞれ普通のデータセットとして割り当てる。並べたことで
                // 割当ての規則が変わるわけではない
                List<Path> parts = new ArrayList<>();
                for (DdTarget part : target.parts()) {
                    if (part instanceof DdTarget.DataSet dataSet) {
                        Place place = locate(dataSet);
                        allocateDataSet(name, dataSet, place, dataSet.partitioned(), 0);
                        dataSets.add(new Held(dataSet.name(), place.path(),
                                dataSet.disposition(), false));
                        parts.add(place.path());
                    } else if (part instanceof DdTarget.Temporary held) {
                        createDirectory(temporary);
                        Path path = temporary.resolve(held.name());
                        allocateTemporary(name, path, held.disposition());
                        dataSets.add(new Held(null, path, held.disposition(), true));
                        parts.add(path);
                    } else {
                        parts.add(null);
                    }
                }
                Path joined = stepWork.resolve(name + ".concat");
                writeBytes(joined, concatenate(target.parts(), parts, name));
                copyAttributes(parts, joined);
                catalog.assign(name, joined);
            }
        }
        return List.of();
    }

    /**
     * 名前から置き場を引く (要件 FR-113, FR-131、暫定判断 P-045 の解消)。
     *
     * <p>目録を通すかどうかはここでは決めない。場所だけを出す。区分データセットなら
     * ライブラリのディレクトリと、その下のメンバの両方を返す。
     */
    private Place locate(DdTarget.DataSet target) {
        Path dataSet = system.onVolume(target.name());
        return new Place(dataSet,
                target.partitioned() ? dataSet.resolve(target.member()) : dataSet);
    }

    /**
     * データセット 1 個を割り当てる (要件 FR-131, FR-133)。
     *
     * <p>{@code DISP} の 1 つ目が言っていることと、実際にあるかどうかが食い違えば、
     * <b>ステップは動かない</b>。黙って作り直したり、無いものを空として読ませたりすると、
     * 名前を打ち間違えたジョブが「0 件処理した」と言って正常終了してしまう。
     *
     * <h2>「ある」は目録から引けることである</h2>
     * <p>{@code OLD} と {@code SHR} が確かめるのは<b>目録から引けるか</b>である。置き場に
     * バイト列が残っていても、目録に載っていなければ名前では届かない。{@code KEEP} で
     * 残したものがこれにあたり、{@code VOL=SER=} を書いて初めて見える。
     *
     * <p>{@code NEW} だけは置き場を見る。目録に載っていなくても<b>場所は塞がっている</b>
     * ので、そこへ新しく作ることはできない。ホストの {@code DUPLICATE NAME ON DIRECT
     * ACCESS} がこれである。
     *
     * <h2>作っただけでは目録に載らない</h2>
     * <p>{@code NEW} で作ったものは、その場で「載っていない」と書き留める。目録へ載るのは
     * ステップが終わって {@code CATLG} が効いたときだけである。書き留めておかないと、
     * 次のジョブから<b>覚えのない名前</b>として拾われ、{@code KEEP} と {@code CATLG} の
     * 区別がまた消えてしまう。
     */
    private void allocateDataSet(String ddName, DdTarget.DataSet target, Place place,
                                 boolean partitioned, int directoryBlocks) {
        // VOL=SER= を書けば目録を通さない。載っていないデータセットへ届く唯一の手である。
        // このジョブが割り当てたものも、目録を通さずに見える
        String name = target.name();
        if (target.relativeGeneration()) {
            // ジョブの初めに直せなかった。基底が目録に登録されていないのである
            throw new AllocationFailure("IEF212I " + ddName
                    + " - NOT A GENERATION DATA GROUP: " + name);
        }
        boolean known = target.serial() != null || system.isCataloged(name)
                || allocated.contains(key(name));
        boolean onVolume = Files.exists(place.dataSet());
        boolean found = known && onVolume;
        switch (target.disposition().status()) {
            case NEW -> {
                if (onVolume) {
                    throw new AllocationFailure("IEF344I " + ddName
                            + " - DUPLICATE NAME ON DIRECT ACCESS: " + name);
                }
                create(target, place, partitioned, directoryBlocks);
            }
            case OLD, SHR -> {
                if (!found) {
                    throw new AllocationFailure("IEF212I " + ddName
                            + " - DATA SET NOT FOUND: " + name);
                }
                if (target.partitioned() && !Files.isDirectory(place.dataSet())) {
                    // メンバを言えるのは区分データセットだけである (要件 FR-113)
                    throw new AllocationFailure("IEF212I " + ddName
                            + " - NOT A PARTITIONED DATA SET: " + name);
                }
            }
            case MOD -> {
                if (!found) {
                    if (onVolume) {
                        throw new AllocationFailure("IEF344I " + ddName
                                + " - DUPLICATE NAME ON DIRECT ACCESS: " + name);
                    }
                    create(target, place, partitioned, directoryBlocks);
                }
            }
            case ANY -> {
                // 状態を言っていない。確かめることも作ることもない。宣言的形式のための形で
                // あり、目録も見ない (暫定判断 P-054)
            }
        }
        // このジョブがこの名前を割り当てた。あとのステップは目録を通さずに引ける
        allocated.add(key(name));
    }

    private static String key(String name) {
        return name.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 割り当てた時点で場所を取る。中身が無いだけである。
     *
     * <p>区分データセットならディレクトリを作る。メンバはまだ無い — 作るのは
     * {@code OPEN OUTPUT} である。
     *
     * <p>区分になるかどうかは、メンバを名指したか、{@code SPACE=} にディレクトリブロックの
     * 数を書いたかで決まる (要件 FR-113)。メンバを言わずにライブラリだけを作ることがあり、
     * {@code IEBCOPY} の写し先がそれである。
     *
     * <p>ディレクトリブロックの数はライブラリの覚え書きへ残す。ホストではデータセットの
     * ラベルにあり、あとからメンバを足すジョブは {@code SPACE=} を書かずにそれを見る。
     */
    private void create(DdTarget.DataSet target, Place place, boolean partitioned,
                        int directoryBlocks) {
        if (partitioned) {
            createDirectory(place.dataSet());
            if (directoryBlocks > 0) {
                // ディレクトリの大きさを覚え書きへ残す (要件 FR-113、暫定判断 P-059 の解消)。
                // 書いてあるのは作ったジョブの SPACE= だけであり、残さなければ
                // あとからメンバを足すジョブが大きさを知らないまま動く
                DataSetAttributes.read(place.dataSet())
                        .withDirectoryBlocks(directoryBlocks)
                        .write(place.dataSet());
            }
        } else {
            writeBytes(place.dataSet(), new byte[0]);
        }
        system.uncatalog(target.name());
    }

    /** 一時データセットを割り当てる。目録には載らないので、あるかどうかだけを見る。 */
    private void allocateTemporary(String ddName, Path path, Disposition disposition) {
        boolean exists = Files.exists(path);
        switch (disposition.status()) {
            case NEW -> {
                if (exists) {
                    throw new AllocationFailure("IEF344I " + ddName
                            + " - DUPLICATE NAME ON DIRECT ACCESS: " + path.getFileName());
                }
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
     * ステップが終わったところで処置を効かせる (要件 FR-133、暫定判断 P-045 の解消)。
     *
     * <p>置き場と目録が別なので、4 つの残し方が<b>それぞれ違うことをする</b>。
     *
     * <ul>
     *   <li>{@code DELETE} — 置き場から消し、目録からも外す</li>
     *   <li>{@code KEEP} — 置き場に残す。目録には<b>触れない</b>。{@code NEW} で作ったものは
     *       載っていないままなので、次のジョブは名前だけでは届かない</li>
     *   <li>{@code CATLG} — 置き場に残し、目録へ載せる。名前だけで届くようになる</li>
     *   <li>{@code UNCATLG} — 置き場に残し、目録から外す。バイト列はあるが名前では届かない</li>
     *   <li>{@code PASS} — 後続のステップへ渡す。ジョブの終わりに片付ける</li>
     * </ul>
     *
     * <p>メンバを指した DD で {@code DELETE} が消すのは<b>メンバだけ</b>である。目録が
     * 覚えているのはライブラリであって、メンバ 1 つを消してもライブラリは残る。
     */
    private void dispose(List<Held> dataSets, boolean abended, Map<Path, Outcome> lastAction) {
        // 基底の登録は読み直す。同じジョブの先行ステップが DEFINE GDG したかもしれない
        GenerationDataGroup defined = new GenerationDataGroup(base);
        for (Held held : dataSets) {
            Disposition.Action action = abended
                    ? held.disposition().abnormal()
                    : held.disposition().normal();
            if (action == Disposition.Action.DELETE) {
                erase(held);
                lastAction.remove(held.path());
                continue;
            }
            if (held.name() != null) {
                switch (action) {
                    case CATLG -> {
                        system.catalog(held.name());
                        rollIn(defined, held.name());
                    }
                    case UNCATLG -> system.uncatalog(held.name());
                    default -> {
                        // KEEP と PASS は目録に触れない。載っているものは載ったまま、
                        // 載っていないものは載らないままである
                    }
                }
            }
            lastAction.put(held.path(), new Outcome(held.name(), action));
        }
    }

    /**
     * 世代を群れへ組み入れ、あふれたぶんを外す (要件 FR-114)。
     *
     * <h2>組み入れは目録へ載った時である</h2>
     * <p>{@code (+1)} で作っただけの世代はまだ群れの一員ではない。{@code DISP=CATLG} が
     * 効いて初めて数に入る。だから {@code DISP=(NEW,CATLG,DELETE)} のステップが異常終了
     * すれば、その世代は消えて<b>群れは元のまま</b>である。ホストが新しい世代を
     * ロールインまで数えないのはこの形である。
     *
     * <h2>あふれたら外す</h2>
     * <p>{@code LIMIT} を越えたぶんを古いほうから外す。{@code EMPTY} と書いてあれば
     * <b>いま入れた 1 つを残して全部</b>外す。外すのは目録からであって、置き場からでは
     * ない。{@code SCRATCH} と書いたときだけ実体も消える。
     *
     * <p>外れた世代は目録から引けなくなるので、{@code (0)} も {@code (-1)} も届かない。
     * バイト列は残っているが、名前で指すには {@code VOL=SER=} が要る。ホストの
     * {@code NOSCRATCH} がまさにそれである。
     */
    private void rollIn(GenerationDataGroup defined, String name) {
        String group = GenerationDataGroup.baseOf(name);
        GenerationDataGroup.Definition definition =
                group == null ? null : defined.definitionOf(group);
        if (definition == null) {
            return;
        }
        List<Integer> numbers = GenerationDataGroup.generations(base, system, group);
        int excess = numbers.size() - definition.limit();
        if (excess <= 0) {
            return;
        }
        // EMPTY はいま入れた 1 つを残して全部、NOEMPTY は古いほうからあふれたぶんだけ
        List<Integer> rolled = List.copyOf(definition.empty()
                ? numbers.subList(0, numbers.size() - 1)
                : numbers.subList(0, excess));
        for (int generation : rolled) {
            String victim = GenerationDataGroup.nameOf(group, generation);
            if (definition.scratch()) {
                erase(new Held(victim, system.onVolume(victim), null, false));
            } else {
                system.uncatalog(victim);
            }
        }
    }

    /**
     * データセットを置き場からも目録からも消す。
     *
     * <p>メンバを指していればメンバだけを消す。ライブラリそのものを指していれば、
     * 中のメンバごと消える。
     */
    private void erase(Held held) {
        if (Files.isDirectory(held.path())) {
            deleteTree(held.path());
        } else {
            remove(held.path());
            remove(DataSetAttributes.sidecarOf(held.path()));
        }
        // メンバを消してもライブラリは残る。目録から外すのはデータセットを消したときだけ
        if (held.name() != null && !Files.exists(system.onVolume(held.name()))) {
            system.forget(held.name());
            allocated.remove(key(held.name()));
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
    private void endOfJob(Job job, Map<Path, Outcome> lastAction) {
        for (Map.Entry<Path, Outcome> entry : lastAction.entrySet()) {
            if (entry.getValue().action() != Disposition.Action.PASS) {
                continue;
            }
            erase(new Held(entry.getValue().name(), entry.getKey(), null, false));
        }
        deleteTree(temporaryArea(job));
    }

    /**
     * 連結したデータセットを 1 つにまとめる (要件 FR-131)。
     *
     * <p>読むときは<b>並べた順に 1 つのファイルに見える</b>。ここでは作業領域へ書き出して
     * 1 つのファイルにしている。読むだけの使い方でしか意味を持たない (暫定判断 P-044)。
     */
    private byte[] concatenate(List<DdTarget> parts, List<Path> places, String name) {
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            DdTarget part = parts.get(i);
            byte[] bytes = switch (part) {
                case DdTarget.DataSet ignored -> readBytes(places.get(i));
                case DdTarget.Temporary ignored -> readBytes(places.get(i));
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
    private void copyAttributes(List<Path> places, Path joined) {
        for (Path place : places) {
            if (place == null) {
                continue;
            }
            if (Files.isReadable(DataSetAttributes.sidecarOf(place))) {
                DataSetAttributes.read(place).write(joined);
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
