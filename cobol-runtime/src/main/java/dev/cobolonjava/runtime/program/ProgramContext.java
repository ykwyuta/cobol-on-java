package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    private ProgramContext(CodePage codePage, OutputStream out, OutputStream error,
                           Charset outputCharset, Map<String, Loaded> loaded, Clock clock,
                           Supplier<String> input) {
        this.codePage = codePage;
        this.out = out;
        this.error = error;
        this.outputCharset = outputCharset;
        this.loaded = loaded;
        this.clock = clock;
        this.input = input;
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
                ProgramContext::readStandardInput);
    }

    /** 出力を捕まえる構成。試験で使う。 */
    public static ProgramContext capturing(ByteArrayOutputStream sink) {
        return new ProgramContext(CodePages.DEFAULT, sink, sink, StandardCharsets.UTF_8,
                new HashMap<>(), Clock.systemDefaultZone(), ProgramContext::readStandardInput);
    }

    /**
     * コードページを差し替えた構成を返す。
     *
     * <p>読み込んだ副プログラムは<b>引き継ぐ</b>。同じ実行の続きだからである。
     */
    public ProgramContext withCodePage(CodePage value) {
        return new ProgramContext(value, out, error, outputCharset, loaded, clock, input);
    }

    /**
     * 時計を差し替えた構成を返す。
     *
     * <p>実行のたびに変わる値は、そのままでは試験に書けない。日付と時刻を固定するために要る。
     */
    public ProgramContext withClock(Clock value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, value, input);
    }

    /** {@code ACCEPT} が読む行の出どころを差し替えた構成を返す。 */
    public ProgramContext withInput(Supplier<String> value) {
        return new ProgramContext(codePage, out, error, outputCharset, loaded, clock, value);
    }

    /** 日付と時刻の特殊レジスタが見る時計。 */
    public Clock clock() {
        return clock;
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
