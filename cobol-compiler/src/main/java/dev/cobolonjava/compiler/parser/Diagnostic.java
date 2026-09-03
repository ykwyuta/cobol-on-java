package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.Origin;

/**
 * 診断 1 件 (要件 FR-183)。
 *
 * <p>位置は元のソースを指す。{@code COPY} で展開された行なら、コピー句の
 * ファイル名と行が入る。展開後の位置を出しても、直す先が分からない。
 *
 * @param origin  元のソース上の位置。位置を特定できなければ {@code null}
 * @param message 内容
 */
public record Diagnostic(Origin origin, String message) {

    @Override
    public String toString() {
        return (origin == null ? "<unknown>" : origin.toString()) + ": " + message;
    }
}
