package dev.cobolonjava.runtime.codepage;

/**
 * そのコードページで表せない文字を符号化しようとした (要件 FR-051, FR-181)。
 *
 * <p><b>なぜ例外にするか</b>: JDK の {@code String.getBytes(Charset)} は、表せない文字を
 * 黙って置換文字へ倒す。IBM-1047 で {@code "山田太郎"} を符号化すると、診断も警告もなく
 * {@code X'3F3F3F3F'} (EBCDIC の SUB) になる。日本語の資産にとってこれは
 * <b>4 文字が消えたことに気付けない</b>ということである。
 *
 * <p>{@code CodePages.forName} は未対応のコードページを
 * {@code UnsupportedCharsetException} で断っている。符号化の側だけが黙って倒れていた。
 * 断る側に揃えた。
 */
public final class UnrepresentableCharacterException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String character;
    private final String codePageName;

    UnrepresentableCharacterException(String character, String codePageName, Throwable cause) {
        super(describe(character, codePageName), cause);
        this.character = character;
        this.codePageName = codePageName;
    }

    /** 表せなかった文字。 */
    public String character() {
        return character;
    }

    /** 表せなかったコードページの名前。 */
    public String codePageName() {
        return codePageName;
    }

    private static String describe(String character, String codePageName) {
        StringBuilder sb = new StringBuilder("character '").append(character).append("' (");
        character.codePoints().forEach(cp -> sb.append(String.format("U+%04X ", cp)));
        sb.setLength(sb.length() - 1);
        return sb.append(") cannot be represented in ").append(codePageName).toString();
    }
}
