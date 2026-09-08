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
        SortWork work = new SortWork(keys, codePage);
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
        active.push(new Active(name, storage));
    }

    /** 割り付けまで添えてプログラムへ入ったことを記録する (要件 FR-142)。 */
    public void enter(String name, Storage storage, StorageMap map) {
        active.push(new Active(name, storage, map));
    }

    /** プログラムから正常に戻ったことを記録する。 */
    public void leave() {
        active.poll();
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
                           DataSetCatalog catalog, Map<String, DataSet> files) {
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
                registers, catalog, files);
    }

    /** 目録を差し替えた構成を返す。 */
    public ProgramContext withCatalog(DataSetCatalog value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, input,
                registers, value, files);
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
    public record Loaded(CobolProgram program, Storage storage) {
    }

    /** 端末へ書く既定の構成。 */
    public static ProgramContext standard() {
        return new ProgramContext(CodePages.DEFAULT, System.out, System.err,
                Charset.defaultCharset(), new HashMap<>(), Clock.systemDefaultZone(),
                ProgramContext::readStandardInput, Storage.allocate(SpecialRegisterArea.SIZE),
                DataSetCatalog.standard(), new HashMap<>());
    }

    /** 出力を捕まえる構成。試験で使う。 */
    public static ProgramContext capturing(ByteArrayOutputStream sink) {
        return new ProgramContext(CodePages.DEFAULT, sink, sink, StandardCharsets.UTF_8,
                new HashMap<>(), Clock.systemDefaultZone(), ProgramContext::readStandardInput,
                Storage.allocate(SpecialRegisterArea.SIZE), DataSetCatalog.standard(),
                new HashMap<>());
    }

    /**
     * コードページを差し替えた構成を返す。
     *
     * <p>読み込んだ副プログラムは<b>引き継ぐ</b>。同じ実行の続きだからである。
     */
    public ProgramContext withCodePage(CodePage value) {
        return new ProgramContext(value, out, error, outputCharset, loaded, clock, input,
                registers, catalog, files);
    }

    /**
     * 時計を差し替えた構成を返す。
     *
     * <p>実行のたびに変わる値は、そのままでは試験に書けない。日付と時刻を固定するために要る。
     */
    public ProgramContext withClock(Clock value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, value, input,
                registers, catalog, files);
    }

    /** {@code ACCEPT} が読む行の出どころを差し替えた構成を返す。 */
    public ProgramContext withInput(Supplier<String> value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, value,
                registers, catalog, files);
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
        Loaded existing = loaded.get(name);
        if (existing != null) {
            return existing;
        }
        String className = ProgramSupport.classNameOf(name);
        try {
            Class<?> type = Class.forName(className, true, loader);
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Loaded fresh = new Loaded(program, Storage.wrap(program.initialStorage()));
            loaded.put(name, fresh);
            return fresh;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new ProgramNotFoundException(name, e);
        }
    }

    /**
     * 読み込んだ副プログラムを忘れる ({@code CANCEL} 相当)。
     *
     * <p>次に呼ばれたときは作業場所が初期状態から始まる。
     */
    public void forget(String name) {
        loaded.remove(name);
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
