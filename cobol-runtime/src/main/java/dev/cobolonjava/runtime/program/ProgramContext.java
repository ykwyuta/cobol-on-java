package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.DumpLevel;
import dev.cobolonjava.runtime.abend.StorageMap;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.file.IndexedDataSet;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.RelativeDataSet;
import dev.cobolonjava.runtime.sort.SortKey;
import dev.cobolonjava.runtime.sort.SortWork;
import dev.cobolonjava.runtime.file.SequentialDataSet;
import dev.cobolonjava.runtime.interop.LegacyClassNameResolver;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.ProgramResolver;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.procedure.ProcedureBoundary;
import dev.cobolonjava.runtime.procedure.ProcedureDecision;
import dev.cobolonjava.runtime.procedure.ProcedureHook;
import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.procedure.ProcedureInvocation;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 実行中のプログラムが外へ触れるための入口 (要件 FR-060)。
 *
 * <p>{@code DISPLAY} の行き先と、実行時のコードページを持つ。生成コードが
 * 大域の状態に触れないようにするために引き回す。試験では出力を捕まえられる。
 *
 * <h2>出力は文字へ直してから書く</h2>
 * <p>記憶域の中身は EBCDIC のバイト列である。そのまま端末へ出しても読めないため、
 * コードページで文字へ直してから書く。<b>データセットへの書き出しとは扱いが違う</b> —
 * そちらは生バイトのままであり (要件 FR-110)、変換するのは人が読む出力だけである。
 *
 * <h2>呼ばれたプログラムはここに留まる</h2>
 * <p>{@code CALL} で呼ばれた副プログラムは、名前ごとに 1 つだけ作って持ち続ける
 * (要件 FR-080)。COBOL では<b>副プログラムの作業場所は呼び出しをまたいで残る</b>ため、
 * 呼ばれるたびに作り直すと前回の値が消えてしまう。
 */
public final class ProgramContext {

    private final CodePage codePage;
    private final OutputStream out;
    private final OutputStream error;
    private final Charset outputCharset;
    /** 呼び出しをまたいで残る副プログラム。名前から引く。 */
    private final Map<String, Loaded> loaded;
    /** COBOL と登録済み Java に共通の名前解決境界。 */
    private final ProgramResolver programResolver;
    /** 明示的な外部形式PERFORMの境界。通常実行ではNOOPである。 */
    private final ProcedureHook procedureHook;
    /** CICS / SQL等の任意subsystemをruntimeへ逆依存させずに渡すtask-scoped service。 */
    private final RuntimeServices services;
    /** hook呼び出しのセッション内通番。 */
    private long procedureSequence;
    /** 日付と時刻の特殊レジスタが見る時計。試験では固定する。 */
    private final Clock clock;
    /** {@code ACCEPT} が読む行の出どころ。 */
    private final Supplier<String> input;
    /** 特殊レジスタの置き場。実行の全体で 1 つである。 */
    private final Storage registers;
    /**
     * 整列作業ファイルを用意する (要件 FR-120)。
     *
     * <p>{@code SD} が表すのは<b>データセットではなく作業場所</b>である。開くことも閉じることも
     * なく、{@code SORT} のたびに作り直す。前の整列の中身が残っていてはならない。
     */
    public SortWork sortWork(String name, List<SortKey> keys) {
        return sortWork(name, keys, null);
    }

    /** 照合順序を決めて用意する。{@code null} ならコードページの並びである。 */
    public SortWork sortWork(String name, List<SortKey> keys,
                             dev.cobolonjava.runtime.codepage.CollatingSequence sequence) {
        SortWork work = new SortWork(keys, codePage, sequence);
        sorts.put(name, work);
        return work;
    }

    /** 用意済みの整列作業ファイル。{@code RELEASE} と {@code RETURN} が使う。 */
    public SortWork sortWork(String name) {
        SortWork work = sorts.get(name);
        if (work == null) {
            throw new IllegalStateException("sort work is not in use: " + name);
        }
        return work;
    }

    /**
     * 開いたままのファイルを閉じる (要件 FR-102, FR-141)。
     *
     * <p>実行が終わったのに閉じていないファイルは、<b>書いたものがまだ置き場に届いていない</b>。
     * ホストでは実行の終了処理がデータセットを閉じるので、途中で異常終了しても
     * そこまでに書いたレコードは残る。残ったものを見て何が起きたのかを調べられることと、
     * 処置 ({@code DISP}) が「作りかけのものを消す」という形で効くことの両方がこれに拠る。
     *
     * <p>閉じるときの誤りは伝えない。すでに異常終了しているかもしれず、そこへ別の誤りを
     * かぶせると<b>最初に起きたことが分からなくなる</b>。
     */
    public void closeFiles() {
        for (DataSet file : files.values()) {
            if (file.mode() != null) {
                try {
                    file.close();
                } catch (RuntimeException ignored) {
                    // 閉じられなかったファイルのために、閉じられるファイルを諦めない
                }
            }
        }
        files.clear();
    }

    /** DD 名から実際のファイルを探す目録。 */
    private final DataSetCatalog catalog;
    /** 開いているファイル。ファイル名から引く。 */
    private final Map<String, DataSet> files;
    /** 整列作業ファイル。{@code SORT} の間だけ存在する。 */
    private final Map<String, SortWork> sorts = new HashMap<>();
    /**
     * いま動いているプログラム。内側が先頭である (要件 FR-142)。
     *
     * <p>異常終了の覚え書きが、止まったところの記憶域を見るために要る。呼び出し履歴だけなら
     * JVM の呼び出し履歴で足りるが、<b>それぞれの作業場所の中身</b>は積んでおかないと
     * 取り出せない。
     */
    private final java.util.Deque<Active> active = new java.util.ArrayDeque<>();
    /** active と同じ順で、その入口が副プログラム呼び出しかを持つ。 */
    private final java.util.Deque<Boolean> calledFrames = new java.util.ArrayDeque<>();
    /** 診断出力の細かさ (要件 FR-143)。 */
    private DumpLevel dumpLevel = DumpLevel.TRACE;

    /**
     * 動いているプログラム 1 個。
     *
     * @param name    プログラム名
     * @param storage その作業場所
     * @param map     作業場所の割り付け。持たないプログラムでは空 (要件 FR-142)
     */
    public record Active(String name, Storage storage, StorageMap map) {

        /** 割り付けを持たないプログラム。手で書いたものがこれである。 */
        public Active(String name, Storage storage) {
            this(name, storage, StorageMap.EMPTY);
        }
    }

    /**
     * プログラムへ入ったことを記録する。
     *
     * <p>抜けるときに {@link #leave()} を呼ぶのは<b>正常に戻ったときだけ</b>である。
     * 異常終了で抜けたプログラムは積まれたまま残り、覚え書きがその中身を見られる。
     */
    public void enter(String name, Storage storage) {
        enter(name, storage, StorageMap.EMPTY, null);
    }

    /** 割り付けまで添えてプログラムへ入ったことを記録する (要件 FR-142)。 */
    public void enter(String name, Storage storage, StorageMap map) {
        enter(name, storage, map, null);
    }

    /**
     * {@code EXTERNAL} の領域まで添えてプログラムへ入ったことを記録する
     * (要件 FR-014, FR-142)。
     *
     * <p>入る前に<b>いま動いている側の中身を実行単位の写しへ書き戻す</b>。そのうえで
     * 入る側へ読み込む。{@code CALL} は呼ぶ側が書いた値を呼ばれた側が見なければ
     * ならないので、この順でなければならない。
     */
    public void enter(String name, Storage storage, StorageMap map, CobolProgram program) {
        enterFrame(name, storage, map, program, !active.isEmpty());
    }

    /** Java の外部 API を含む主プログラム入口。 */
    public void enterMain(String name, Storage storage, StorageMap map, CobolProgram program) {
        enterFrame(name, storage, map, program, false);
    }

    /** COBOL の {@code CALL} または Java の低レベル call 入口。 */
    public void enterCall(String name, Storage storage, StorageMap map, CobolProgram program) {
        enterFrame(name, storage, map, program, true);
    }

    private void enterFrame(String name, Storage storage, StorageMap map, CobolProgram program,
                            boolean called) {
        flushExternals();
        active.push(new Active(name, storage, map));
        calledFrames.push(called);
        externalFrames.push(new ExternalFrame(name, storage, program));
        loadExternals();
    }

    /**
     * プログラムから正常に戻ったことを記録する。
     *
     * <p>抜ける側の {@code EXTERNAL} を書き戻してから、呼んだ側へ読み込み直す。
     * 呼ばれた側が書き換えた値は<b>戻ったところで見えていなければならない</b>。
     */
    public void leave() {
        flushExternals();
        active.poll();
        calledFrames.poll();
        externalFrames.poll();
        loadExternals();
    }

    /** 現在の入口が副プログラム呼び出しか。{@code EXIT PROGRAM} の意味を決める。 */
    public boolean currentInvocationIsCall() {
        return Boolean.TRUE.equals(calledFrames.peek());
    }

    /**
     * 現在のprogram入口を識別する、実行中だけ有効なtokenを返す。
     *
     * <p>CICS condition handlerの段落番号が、登録したprogram以外の段落番号として
     * 誤解釈されるのを防ぐために使う。tokenの型と内容は公開せず、同一性だけを比較すること。
     */
    public Object currentInvocationToken() {
        Object token = externalFrames.peek();
        if (token == null) {
            throw new IllegalStateException("no active COBOL program invocation");
        }
        return token;
    }

    /** 現在のresolverでprogramを起動せず解決可能性だけを確認する。 */
    public boolean isProgramResolvable(String name, ClassLoader loader) {
        return programResolver.isResolvable(
                ProgramId.of(name), java.util.Objects.requireNonNull(loader, "loader"));
    }

    // ---- EXTERNAL (要件 FR-014) ----

    /** 実行単位で 1 つずつ持つ {@code EXTERNAL} の中身。データ名で引く。 */
    private final Map<String, byte[]> externals = new java.util.HashMap<>();
    /** 積まれたプログラムの記憶域と、その {@code EXTERNAL} の位置。 */
    private final java.util.Deque<ExternalFrame> externalFrames = new java.util.ArrayDeque<>();

    private record ExternalFrame(String name, Storage storage, CobolProgram program) {

        CobolProgram.ExternalRegion[] regions() {
            return program == null ? CobolProgram.NO_EXTERNAL_REGIONS : program.externalRegions();
        }
    }

    /**
     * 囲む側の宣言節を、その記憶域で動かす (要件 FR-091, FR-105)。
     *
     * <p>{@code USE GLOBAL AFTER ERROR PROCEDURE} である。囲まれたプログラムで入出力の
     * 異常が起きたとき、動かすのは囲む側の節であり、<b>囲む側の記憶域</b>で動かす。
     * 節の中身は囲む側の段落と項目を指しているからである。
     *
     * <p>囲む側は積まれた中にいる。{@code CALL} で入ったので、下のほうにいるはずである。
     * いなければ何もしない — 呼ばれ方が規格の想定と違うということであり、黙って
     * 別のプログラムの節を動かすよりは何もしないほうがよい。
     *
     * @param owner 宣言節を書いたプログラムの名前
     */
    public void performGlobal(String owner, int from, int through) {
        ExternalFrame current = externalFrames.peek();
        for (ExternalFrame frame : externalFrames) {
            if (frame.program() == null || !owner.equalsIgnoreCase(frame.name())) {
                continue;
            }
            // 節はよそのプログラムの記憶域で動く。<b>入って出るのと同じ</b>形に
            // 分け合っているものを合わせる。そうしないと、戻ったところで
            // 呼んだ側の古い写しが書き戻され、節の書いた値が消える
            flush(current);
            load(frame);
            frame.program().performGlobalRange(from, through, frame.storage(), this);
            flush(frame);
            load(current);
            return;
        }
    }

    /** いま動いている側の {@code EXTERNAL} を実行単位の写しへ書き戻す。 */
    private void flushExternals() {
        flush(externalFrames.peek());
    }

    private void flush(ExternalFrame frame) {
        if (frame == null) {
            return;
        }
        for (CobolProgram.ExternalRegion region : frame.regions()) {
            externals.put(region.name(),
                    frame.storage().view(region.offset(), region.length()).toByteArray());
        }
    }

    /**
     * 実行単位の写しを、いま動いている側へ読み込む。
     *
     * <p>まだ写しが無ければ何もしない。<b>そのプログラムの持っている中身が最初の値</b>で
     * ある。規格は {@code EXTERNAL} に {@code VALUE} を書くことを許していないので、
     * どのプログラムから見ても同じ初期状態から始まる。
     */
    private void loadExternals() {
        load(externalFrames.peek());
    }

    private void load(ExternalFrame frame) {
        if (frame == null) {
            return;
        }
        for (CobolProgram.ExternalRegion region : frame.regions()) {
            byte[] shared = externals.get(region.name());
            if (shared != null && shared.length == region.length()) {
                frame.storage().view(region.offset(), region.length()).setBytes(shared);
            }
        }
    }

    /** いま動いているプログラム。内側が先頭に並ぶ。 */
    public List<Active> active() {
        return List.copyOf(active);
    }

    /** 診断出力の細かさ (要件 FR-143)。 */
    public DumpLevel dumpLevel() {
        return dumpLevel;
    }

    /** 診断出力の細かさを差し替える。ジョブが {@code CEEOPTS} で指定する。 */
    public void setDumpLevel(DumpLevel value) {
        this.dumpLevel = value == null ? DumpLevel.TRACE : value;
    }

    private ProgramContext(CodePage codePage, OutputStream out, OutputStream error,
                           Charset outputCharset, Map<String, Loaded> loaded, Clock clock,
                           Supplier<String> input, Storage registers,
                           DataSetCatalog catalog, Map<String, DataSet> files,
                           ProgramResolver programResolver, ProcedureHook procedureHook,
                           RuntimeServices services) {
        this.codePage = codePage;
        this.out = out;
        this.error = error;
        this.outputCharset = outputCharset;
        this.loaded = loaded;
        this.clock = clock;
        this.input = input;
        this.registers = registers;
        this.catalog = catalog;
        this.files = files;
        this.programResolver = programResolver;
        this.procedureHook = procedureHook;
        this.services = java.util.Objects.requireNonNull(services, "services");
    }

    /**
     * ファイルを引く。開いていなければ新しく用意する (要件 FR-102)。
     *
     * <p>入れ物はプログラムの側ではなくここが持つ。<b>閉じずに終わったファイルを
     * 実行の終わりに片付けられる</b>ようにするためである。
     *
     * @param name   {@code FD} に書かれたファイル名
     * @param ddName {@code ASSIGN TO} に書かれた DD 名
     */
    /**
     * そのファイルが領域を使い切ったときの異常終了コード (暫定判断 P-052)。まだ開いていない
     * ファイルなら {@code S037} である。
     */
    public dev.cobolonjava.runtime.abend.AbendCode spaceAbendOf(String name) {
        DataSet file = files.get(name);
        return file == null ? dev.cobolonjava.runtime.abend.AbendCode.S037 : file.spaceAbend();
    }

    public DataSet file(String name, String ddName) {
        return files.computeIfAbsent(name,
                k -> allocated(SequentialDataSet.at(catalog.resolve(ddName)), ddName));
    }

    /**
     * ファイルを引く。開いていなければ、宣言された様式で新しく用意する (要件 FR-102, FR-110)。
     *
     * <p>{@code OPEN OUTPUT} で作るファイルにはサイドカーがない。そのとき<b>レコードの
     * 切れ目を決められるのはプログラムの宣言だけ</b>である。サイドカーがあればそちらが勝つ。
     *
     * @param format       {@code ORGANIZATION} と {@code RECORDING MODE} から決まる様式
     * @param recordLength {@code FD} 配下のレコード記述から決まる長さ
     */
    public DataSet file(String name, String ddName, Organization organization,
                        RecordFormat format, int recordLength) {
        return files.computeIfAbsent(name, k -> {
            DataSetAttributes declared = new DataSetAttributes(format, recordLength, codePage);
            Path path = catalog.resolve(ddName);
            return allocated(organization == Organization.RELATIVE
                    ? RelativeDataSet.at(path, declared)
                    : SequentialDataSet.at(path, declared), ddName);
        });
    }

    /**
     * 割当てが決めたことをファイルへ渡す (要件 FR-113, FR-141)。
     *
     * <p>プログラムからは見えないことである。取った領域の大きさも、指しているのが
     * 区分データセットのメンバかどうかも、<b>ジョブが決めてプログラムは知らない</b>。
     * 渡さなければ、実機では止まるジョブがここでは通ってしまう。
     *
     * <p>編成によらず渡す。どの編成かでホストとの合い方が変わるのがいちばん困る形だからで
     * ある (暫定判断 P-053)。
     */
    private DataSet allocated(DataSet file, String ddName) {
        file.limit(catalog.limitOf(ddName));
        file.member(catalog.isMemberOfLibrary(ddName));
        file.secondary(catalog.hasSecondary(ddName));
        return file;
    }

    /**
     * 索引編成のファイルを引く (要件 FR-100, FR-101)。
     *
     * <p>鍵の場所はファイルではなく<b>プログラムが決める</b>。{@code RECORD KEY} に書いた
     * 項目の位置と長さであり、ここで渡す。
     *
     * @param keys 主鍵が先頭、以降が {@code ALTERNATE RECORD KEY} の並び順
     */
    public DataSet file(String name, String ddName, RecordFormat format, int recordLength,
                        List<IndexedDataSet.Key> keys) {
        return files.computeIfAbsent(name, k -> allocated(IndexedDataSet.at(
                catalog.resolve(ddName), new DataSetAttributes(format, recordLength, codePage),
                keys.get(0), keys.subList(1, keys.size())), ddName));
    }

    /** DD 名から実際のファイルを探す目録。 */
    public DataSetCatalog catalog() {
        return catalog;
    }

    /**
     * 出力の行き先を差し替えた構成を返す。
     *
     * <p>ジョブ実行がステップの出力をまとめて受け取るために要る。
     */
    public ProgramContext withOutput(OutputStream value) {
        return new ProgramContext(codePage, value, value, outputCharset, loaded, clock, input,
                registers, catalog, files, programResolver, procedureHook, services);
    }

    /** 目録を差し替えた構成を返す。 */
    public ProgramContext withCatalog(DataSetCatalog value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, input,
                registers, value, files, programResolver, procedureHook, services);
    }

    /** プログラム解決境界を差し替えた構成を返す。読み込み済み状態は引き継ぐ。 */
    public ProgramContext withProgramResolver(ProgramResolver value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, input,
                registers, catalog, files, java.util.Objects.requireNonNull(value, "value"),
                procedureHook, services);
    }

    /** 明示的PERFORMのhookを差し替えた構成を返す。 */
    public ProgramContext withProcedureHook(ProcedureHook value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, input,
                registers, catalog, files, programResolver,
                java.util.Objects.requireNonNull(value, "value"), services);
    }

    /** task-scoped subsystem serviceを差し替えた構成を返す。 */
    public ProgramContext withServices(RuntimeServices value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, input,
                registers, catalog, files, programResolver, procedureHook,
                java.util.Objects.requireNonNull(value, "value"));
    }

    /** 生成コードが必要とするtask-scoped subsystem serviceを取得する。 */
    public <T> T service(Class<T> type) {
        return services.require(type);
    }

    /** 生成コードが明示的PERFORMへ入る直前に呼ぶ。 */
    public ProcedureBoundary beforeProcedure(ProcedureId id, String callerProcedure,
                                             String sourceFile, int sourceLine,
                                             Storage workingStorage,
                                             dev.cobolonjava.runtime.storage.DataView[] arguments) {
        ProcedureInvocation invocation = new ProcedureInvocation(++procedureSequence, id,
                callerProcedure, sourceFile, sourceLine, workingStorage,
                java.util.Arrays.asList(arguments), this);
        ProcedureDecision decision = java.util.Objects.requireNonNull(
                procedureHook.before(invocation), "procedure hook decision");
        return new ProcedureBoundary(procedureHook, invocation, decision);
    }

    /**
     * 特殊レジスタの置き場 (要件 FR-084)。
     *
     * <p>{@code RETURN-CODE} は実行の全体で 1 つであり、呼ぶ側と呼ばれる側が同じものを見る。
     * 生成コードは<b>プログラム自身の記憶域ではなくこちら</b>を読み書きする。
     * 写し取る仕組みが要らないのはこのためである。
     */
    public Storage registers() {
        return registers;
    }

    /**
     * 復帰コードを置く (要件 FR-084)。
     *
     * <p>ユーティリティのように、生成コードを通らずに復帰コードを立てるものが使う。
     * 置き場は生成コードが読み書きするものと同じである。
     */
    public void setReturnCode(int value) {
        registers.view(SpecialRegisterArea.RETURN_CODE_OFFSET, 2)
                .setBytes(new byte[] {(byte) (value >> 8), (byte) value});
    }

    /** {@code STOP RUN} のあとにプロセスの終了コードとなる値 (要件 FR-084)。 */
    public int returnCode() {
        byte[] bytes = registers.array();
        int offset = SpecialRegisterArea.RETURN_CODE_OFFSET;
        return (short) (((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF));
    }

    /**
     * 一度読み込んだ副プログラムと、その作業場所。
     *
     * <p>作業場所を一緒に持つのは、<b>呼び出しをまたいで残す</b>ためである。
     */
    public record Loaded(CobolProgram program, Storage storage,
                         dev.cobolonjava.runtime.interop.ProgramSignature signature) {

        /** 署名付き定義だけ、プログラムへ入る前に低レベルABIを検査する。 */
        public void validateArguments(dev.cobolonjava.runtime.storage.DataView[] arguments) {
            if (signature != null) {
                signature.validate(arguments);
            }
        }
    }

    /** 端末へ書く既定の構成。 */
    public static ProgramContext standard() {
        return new ProgramContext(CodePages.DEFAULT, System.out, System.err,
                Charset.defaultCharset(), new HashMap<>(), Clock.systemDefaultZone(),
                ProgramContext::readStandardInput, Storage.allocate(SpecialRegisterArea.SIZE),
                DataSetCatalog.standard(), new HashMap<>(), LegacyClassNameResolver.INSTANCE,
                ProcedureHook.NOOP, RuntimeServices.EMPTY);
    }

    /** 出力を捕まえる構成。試験で使う。 */
    public static ProgramContext capturing(ByteArrayOutputStream sink) {
        return new ProgramContext(CodePages.DEFAULT, sink, sink, StandardCharsets.UTF_8,
                new HashMap<>(), Clock.systemDefaultZone(), ProgramContext::readStandardInput,
                Storage.allocate(SpecialRegisterArea.SIZE), DataSetCatalog.standard(),
                new HashMap<>(), LegacyClassNameResolver.INSTANCE, ProcedureHook.NOOP,
                RuntimeServices.EMPTY);
    }

    /**
     * コードページを差し替えた構成を返す。
     *
     * <p>読み込んだ副プログラムは<b>引き継ぐ</b>。同じ実行の続きだからである。
     */
    public ProgramContext withCodePage(CodePage value) {
        return new ProgramContext(value, out, error, outputCharset, loaded, clock, input,
                registers, catalog, files, programResolver, procedureHook, services);
    }

    /**
     * 時計を差し替えた構成を返す。
     *
     * <p>実行のたびに変わる値は、そのままでは試験に書けない。日付と時刻を固定するために要る。
     */
    public ProgramContext withClock(Clock value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, value, input,
                registers, catalog, files, programResolver, procedureHook, services);
    }

    /** {@code DISPLAY} が出力へ書くときの文字コード。出力を受け取る側が読み戻すために要る。 */
    public Charset outputCharset() {
        return outputCharset;
    }

    /** {@code ACCEPT} が読む行の出どころを差し替えた構成を返す。 */
    public ProgramContext withInput(Supplier<String> value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, value,
                registers, catalog, files, programResolver, procedureHook, services);
    }

    /** 日付と時刻の特殊レジスタが見る時計。 */
    public Clock clock() {
        return clock;
    }

    /**
     * {@code FUNCTION RANDOM} の乱数列 (要件 FR-070、テスト時の固定は FR-204)。
     *
     * <p>種を与えれば<b>そこから決まる同じ並び</b>が出る。規格がそう決めている。
     * 実行の全体で 1 つ持つのは、種を与えない呼び出しが<b>前の続き</b>を返すためである。
     */
    private java.util.Random random;

    /**
     * {@code CLOSE ... WITH LOCK} で閉じたファイル (要件 FR-102)。
     *
     * <p>閉じたあと、この実行単位では<b>二度と開けない</b>。実行の全体で 1 つ持つのは、
     * 規格が「実行単位のあいだ」と決めているからである。副プログラムから開き直しても
     * 同じく断る。
     */
    private final java.util.Set<String> lockedFiles = new java.util.HashSet<>();

    /**
     * 直前の {@code WRITE} が頁の終わりに達したか (要件 FR-113)。
     *
     * <p>{@code AT END-OF-PAGE} の分岐に使う。文の結果を持ち回るのに記憶域を使わないのは、
     * <b>プログラムから見えてはならない</b>値だからである。
     */
    private boolean endOfPage;

    /** 頁の終わりに達したかを記録する。 */
    public void setEndOfPage(boolean reached) {
        endOfPage = reached;
    }

    /** 直前の {@code WRITE} が頁の終わりに達したか。 */
    public boolean endOfPage() {
        return endOfPage;
    }

    /**
     * 外から立てる切り替え (要件 FR-135)。
     *
     * <p>ジョブが立てたところをプログラムが読む。実行の全体で 1 つであり、
     * 初めはすべて切れている。参照実装と同じく 8 個持つ。
     */
    private final boolean[] switches = new boolean[8];

    /** 切り替えを立てる、または切る。 */
    public void switchState(int index, boolean on) {
        if (index >= 0 && index < switches.length) {
            switches[index] = on;
        }
    }

    /** 切り替えが立っているか。 */
    public boolean switchState(int index) {
        return index >= 0 && index < switches.length && switches[index];
    }

    /**
     * 実行時のデバッグの切り替え (要件 FR-193)。
     *
     * <p>{@code WITH DEBUGGING MODE} は<b>翻訳のとき</b>の切り替えであり、これは
     * <b>実行のとき</b>の切り替えである。切ると、7 桁目の {@code D} の行は動いたまま
     * <b>デバッグの節だけが動かなくなる</b>。参照実装ではジョブの指定で切る。
     *
     * <p>初めは立っている。翻訳したのに何も起きないほうが分かりにくいからである。
     */
    private boolean debuggingProcedures = true;

    /** デバッグの節を動かすかどうかを決める。 */
    public ProgramContext withDebuggingProcedures(boolean value) {
        this.debuggingProcedures = value;
        return this;
    }

    /** デバッグの節を動かすか。 */
    public boolean debuggingProcedures() {
        return debuggingProcedures;
    }

    /** 閉じたファイルに錠を掛ける。 */
    public void lockFile(String name) {
        lockedFiles.add(name);
    }

    /** 錠が掛かっているか。掛かっていれば {@code OPEN} は状態コード 38 になる。 */
    public boolean isFileLocked(String name) {
        return lockedFiles.contains(name);
    }

    /** 種を決めて数列を作り直す。 */
    public void seedRandom(long seed) {
        random = new java.util.Random(seed);
    }

    /**
     * 次の乱数。
     *
     * @return 0 以上 1 未満
     */
    public double nextRandom() {
        if (random == null) {
            // 種を与えずに呼ばれたときの並びは処理系が決めてよい。
            // 実行のたびに変わらないほうが試験に書けるので、固定の種から始める
            random = new java.util.Random(0);
        }
        return random.nextDouble();
    }

    /**
     * {@code ACCEPT} が読む 1 行。
     *
     * <p>入力が尽きていれば空文字を返す。参照実装も入力がなければ受取項目を変えないため、
     * 例外にはしない。
     */
    public String readLine() {
        String line = input.get();
        return line == null ? "" : line;
    }

    private static String readStandardInput() {
        try {
            if (standardInput == null) {
                standardInput = new BufferedReader(
                        new InputStreamReader(System.in, Charset.defaultCharset()));
            }
            return standardInput.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read program input", e);
        }
    }

    private static BufferedReader standardInput;

    /**
     * 名前で副プログラムを引く。まだ読み込んでいなければ読み込む。
     *
     * @param loader 呼ぶ側のクラスを読み込んだもの。生成クラスは同じところにある
     * @throws ProgramNotFoundException 読み込めない場合
     */
    public Loaded resolve(String name, ClassLoader loader) {
        ProgramId id = ProgramId.of(name);
        Loaded existing = loaded.get(id.value());
        if (existing != null) {
            return existing;
        }
        CobolProgram program = programResolver.resolve(id, loader);
        dev.cobolonjava.runtime.interop.ProgramSignature catalogSignature =
                programResolver.signature(id);
        dev.cobolonjava.runtime.interop.ProgramSignature embeddedSignature =
                program.programSignature();
        if (catalogSignature != null && embeddedSignature != null
                && !catalogSignature.equals(embeddedSignature)) {
            throw new IllegalStateException("catalog and generated class signatures disagree: "
                    + id.value());
        }
        Loaded fresh = new Loaded(program, Storage.wrap(program.initialStorage()),
                catalogSignature != null ? catalogSignature : embeddedSignature);
        loaded.put(id.value(), fresh);
        return fresh;
    }

    /**
     * 読み込んだ副プログラムを忘れる ({@code CANCEL} 相当)。
     *
     * <p>次に呼ばれたときは作業場所が初期状態から始まる。
     */
    public void forget(String name) {
        loaded.remove(ProgramId.of(name).value());
    }

    public CodePage codePage() {
        return codePage;
    }

    /**
     * {@code DISPLAY} の出力。
     *
     * @param advancing 行を改めるかどうか。{@code WITH NO ADVANCING} では改めない
     */
    public void display(byte[] bytes, boolean advancing) {
        display(bytes, advancing, false);
    }

    /**
     * 行き先を指定した {@code DISPLAY} の出力 (要件 FR-135)。
     *
     * <p>{@code UPON} に書いた呼び名が指す機能名で分かれる。標準エラー出力へ出すのは
     * <b>捕まえる先が違う</b>ためであり、標準出力と混ざらない。
     *
     * @param toError 標準エラー出力へ出すかどうか
     */
    public void display(byte[] bytes, boolean advancing, boolean toError) {
        String text = codePage.decode(bytes) + (advancing ? System.lineSeparator() : "");
        write(text, toError ? error : out);
    }

    private void write(String text) {
        write(text, out);
    }

    private void write(String text, OutputStream sink) {
        try {
            sink.write(text.getBytes(outputCharset));
            sink.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write program output", e);
        }
    }
}
