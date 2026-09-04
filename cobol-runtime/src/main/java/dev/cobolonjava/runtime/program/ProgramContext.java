package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 実行中のプログラムが外へ触れるための入口 (要件 FR-062)。
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
    private final Charset outputCharset;
    /** 呼び出しをまたいで残る副プログラム。名前から引く。 */
    private final Map<String, Loaded> loaded;

    private ProgramContext(CodePage codePage, OutputStream out, Charset outputCharset,
                           Map<String, Loaded> loaded) {
        this.codePage = codePage;
        this.out = out;
        this.outputCharset = outputCharset;
        this.loaded = loaded;
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
        return new ProgramContext(CodePages.DEFAULT, System.out, Charset.defaultCharset(),
                new HashMap<>());
    }

    /** 出力を捕まえる構成。試験で使う。 */
    public static ProgramContext capturing(ByteArrayOutputStream sink) {
        return new ProgramContext(CodePages.DEFAULT, sink, StandardCharsets.UTF_8,
                new HashMap<>());
    }

    /**
     * コードページを差し替えた構成を返す。
     *
     * <p>読み込んだ副プログラムは<b>引き継ぐ</b>。同じ実行の続きだからである。
     */
    public ProgramContext withCodePage(CodePage value) {
        return new ProgramContext(value, out, outputCharset, loaded);
    }

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
        write(codePage.decode(bytes));
        if (advancing) {
            write(System.lineSeparator());
        }
    }

    private void write(String text) {
        try {
            out.write(text.getBytes(outputCharset));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write program output", e);
        }
    }
}
