package dev.cobolonjava.ims.gen;

/** 試験の原文を固定形式の札で組み立てる。1〜71 桁が文、72 桁が続きの印である。 */
public final class Cards {

    private Cards() {
    }

    /** 続かない札。 */
    public static String card(String text) {
        return pad(text) + " ";
    }

    /** 次の札へ続く札。 */
    public static String more(String text) {
        return pad(text) + "X";
    }

    /** 札を行に並べる。 */
    public static String deck(String... cards) {
        return String.join("\n", cards) + "\n";
    }

    private static String pad(String text) {
        if (text.length() > 71) {
            throw new IllegalArgumentException("a card holds 71 columns: " + text);
        }
        return text + " ".repeat(71 - text.length());
    }
}
