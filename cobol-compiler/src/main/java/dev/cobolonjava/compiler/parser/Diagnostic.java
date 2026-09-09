package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.Origin;
import java.util.List;

/**
 * 診断 1 件 (要件 FR-183)。
 *
 * <p>位置は元のソースを指す。{@code COPY} で展開された行なら、コピー句の
 * ファイル名と行が入る。展開後の位置を出しても、直す先が分からない。
 *
 * <h2>重大度は「止まるかどうか」である</h2>
 * <p>翻訳を止めるものと、告げるだけで通すものがある。規格に沿わないが意味の決まる
 * 書き方 — 廃要素、参照実装が受理しない古い書き方、宣言どうしの食い違い — は
 * <b>告げて通す</b>。止めてしまうと、その先にある本当の誤りが見えなくなる。
 *
 * @param origin   元のソース上の位置。位置を特定できなければ {@code null}
 * @param message  内容
 * @param severity 重大度
 */
public record Diagnostic(Origin origin, String message, Severity severity) {

    /** 重大度 (要件 FR-183)。参照実装の I / W / E / S / U に対応する。 */
    public enum Severity {
        /** 知らせるだけ。 */
        INFORMATIONAL,
        /** 規格に沿わないが、意味は決まる。翻訳は続く。 */
        WARNING,
        /** 翻訳を止める。 */
        ERROR,
        /** 翻訳を止める。以後の解析も当てにならない。 */
        SEVERE;

        /** 翻訳を止めるか。 */
        public boolean blocking() {
            return this == ERROR || this == SEVERE;
        }
    }

    /** 重大度を書かなければ、翻訳を止める誤りである。 */
    public Diagnostic(Origin origin, String message) {
        this(origin, message, Severity.ERROR);
    }

    /** 告げるだけで翻訳を続ける診断。 */
    public static Diagnostic warning(Origin origin, String message) {
        return new Diagnostic(origin, message, Severity.WARNING);
    }

    /** 翻訳を止める診断が 1 件でもあるか。 */
    public static boolean blocking(List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity().blocking()) {
                return true;
            }
        }
        return false;
    }

    public boolean isWarning() {
        return severity == Severity.WARNING;
    }

    /**
     * 位置を先に置く。<b>誤りには印を付けない</b>。
     *
     * <p>診断の文面を数え上げる道具は、位置を落としてから同じ理由をまとめている。
     * 位置の前に印を足すと落とせなくなるので、印は位置の後ろに置く。誤りは既定なので
     * 印を付けない — 付ければ、いままでの文面がすべて変わってしまう。
     */
    @Override
    public String toString() {
        String at = (origin == null ? "<unknown>" : origin.toString()) + ": ";
        return severity == Severity.ERROR ? at + message : at + label() + ": " + message;
    }

    private String label() {
        return switch (severity) {
            case INFORMATIONAL -> "info";
            case WARNING -> "warning";
            case ERROR -> "error";
            case SEVERE -> "severe";
        };
    }
}
