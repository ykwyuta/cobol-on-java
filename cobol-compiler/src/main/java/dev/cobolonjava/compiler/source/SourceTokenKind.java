package dev.cobolonjava.compiler.source;

/**
 * 構文解析器へ渡すトークンの種別 (方針 ARC-8)。
 *
 * <p>{@link TextWordKind} がプリプロセッサ内部の照合のためのものであるのに対し、
 * こちらは<b>構文解析器の入力</b>である。両者を分けているのは、
 * {@code COPY} / {@code REPLACE} の照合が「島」を切り出す前の語に対して行われるためである。
 */
public enum SourceTokenKind {

    /** COBOL 語または数字定数。予約語かどうかの判別は構文解析器の仕事である。 */
    WORD,
    /** 文字定数。引用符を含む。 */
    LITERAL,
    /** 区切り文字 ({@code .} {@code ,} {@code ;} {@code (} {@code )})。 */
    SEPARATOR,
    /**
     * {@code PICTURE} 句の文字列。{@code PIC 99.99} の {@code .} が文末ピリオドと
     * 区別できないため、丸ごと 1 個の不透明トークンとして切り出す。
     */
    PICTURE_STRING,
    /**
     * {@code EXEC ... END-EXEC} のブロック全体。中身は COBOL ではないため、
     * 構文解析器には見せず専用のトランスレータへ渡す (要件 FR-150, FR-160)。
     */
    EXEC_BLOCK
}
