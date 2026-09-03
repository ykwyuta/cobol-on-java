package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
 */
public final class ProgramContext {

    private final CodePage codePage;
    private final OutputStream out;
    private final Charset outputCharset;

    private ProgramContext(CodePage codePage, OutputStream out, Charset outputCharset) {
        this.codePage = codePage;
        this.out = out;
        this.outputCharset = outputCharset;
    }

    /** 端末へ書く既定の構成。 */
    public static ProgramContext standard() {
        return new ProgramContext(CodePages.DEFAULT, System.out, Charset.defaultCharset());
    }

    /** 出力を捕まえる構成。試験で使う。 */
    public static ProgramContext capturing(ByteArrayOutputStream sink) {
        return new ProgramContext(CodePages.DEFAULT, sink, StandardCharsets.UTF_8);
    }

    /** コードページを差し替えた構成を返す。 */
    public ProgramContext withCodePage(CodePage value) {
        return new ProgramContext(value, out, outputCharset);
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
