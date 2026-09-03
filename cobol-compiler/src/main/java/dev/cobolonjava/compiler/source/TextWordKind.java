package dev.cobolonjava.compiler.source;

/** プリプロセッサが扱う字句の種別。 */
public enum TextWordKind {

    /** COBOL 語。大文字と小文字を区別しない。 */
    WORD,
    /** 文字定数。引用符を含めて 1 個の字句として扱い、大文字と小文字を区別する。 */
    LITERAL,
    /** 区切り文字 ({@code .} {@code ,} {@code ;} {@code (} {@code )})。 */
    SEPARATOR,
    /** 擬似テキストの区切り {@code ==}。 */
    PSEUDO_DELIMITER
}
