package dev.cobolonjava.compiler.source;

import java.util.Locale;
import java.util.Optional;

/**
 * Db2 の precompiler が提供する {@code INCLUDE} 名 ({@code SQLCA}) を返す (要件 FR-151, FR-153)。
 *
 * <p>形は Db2 for z/OS の公開文書にある COBOL 向け SQLCA の宣言による。IBM 製品の原文は参照しない。
 * 利用者の置き場より後ろに連ねる。資産が自前の SQLCA を置いていれば、そちらを使う。
 */
public final class Db2SystemCopyBookResolver implements CopyBookResolver {

    /**
     * SQLCA (136 byte)。
     *
     * <p>2 進項目は COMP-5 とした。9 桁の値を桁で切り詰めずに持つためである。byte 並びは
     * COMP と同じ big endian である (暫定判断 P-120)。
     */
    private static final String SQLCA = String.join("\n",
            "       01  SQLCA.",
            "           05  SQLCAID     PIC X(8).",
            "           05  SQLCABC     PIC S9(9) COMP-5.",
            "           05  SQLCODE     PIC S9(9) COMP-5.",
            "           05  SQLERRM.",
            "               49  SQLERRML PIC S9(4) COMP-5.",
            "               49  SQLERRMC PIC X(70).",
            "           05  SQLERRP     PIC X(8).",
            "           05  SQLERRD     OCCURS 6 TIMES PIC S9(9) COMP-5.",
            "           05  SQLWARN.",
            "               10  SQLWARN0 PIC X.",
            "               10  SQLWARN1 PIC X.",
            "               10  SQLWARN2 PIC X.",
            "               10  SQLWARN3 PIC X.",
            "               10  SQLWARN4 PIC X.",
            "               10  SQLWARN5 PIC X.",
            "               10  SQLWARN6 PIC X.",
            "               10  SQLWARN7 PIC X.",
            "           05  SQLEXT.",
            "               10  SQLWARN8 PIC X.",
            "               10  SQLWARN9 PIC X.",
            "               10  SQLWARNA PIC X.",
            "               10  SQLSTATE PIC X(5).") + "\n";

    /**
     * SQLDA (暫定判断 P-146)。
     *
     * <p>欄の名前と型は Db2 の公開文書にある SQLDA の説明 (SQLDAID 8 byte、SQLDABC 4 byte、SQLN / SQLD 2 byte、SQLVAR の
     * SQLTYPE / SQLLEN 2 byte、SQLDATA / SQLIND の番地、SQLNAME の長さと 30 文字) による。番地は P-123 の 4 byte の POINTER。
     * {@code OCCURS DEPENDING ON} をまだ持たないので、SQLVAR は Db2 for z/OS の上限の 750 個を固定で置く。
     * 動的 SQL (PREPARE / DESCRIBE / USING DESCRIPTOR) はまだ無いので、置いた記述子を SQL が読み書きすることは無い。
     */
    private static final String SQLDA = String.join("\n",
            "       01  SQLDA.",
            "           05  SQLDAID     PIC X(8).",
            "           05  SQLDABC     PIC S9(9) COMP-5.",
            "           05  SQLN        PIC S9(4) COMP-5.",
            "           05  SQLD        PIC S9(4) COMP-5.",
            "           05  SQLVAR      OCCURS 750 TIMES.",
            "               10  SQLTYPE PIC S9(4) COMP-5.",
            "               10  SQLLEN  PIC S9(4) COMP-5.",
            "               10  SQLDATA POINTER.",
            "               10  SQLIND  POINTER.",
            "               10  SQLNAME.",
            "                   49  SQLNAMEL PIC S9(4) COMP-5.",
            "                   49  SQLNAMEC PIC X(30).") + "\n";

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        if (libraryName != null) {
            return Optional.empty();
        }
        return switch (textName.toUpperCase(Locale.ROOT)) {
            case "SQLCA" -> Optional.of(new CopyBook("SQLCA (Db2 system include)", SQLCA));
            case "SQLDA" -> Optional.of(new CopyBook("SQLDA (Db2 system include)", SQLDA));
            default -> Optional.empty();
        };
    }
}
