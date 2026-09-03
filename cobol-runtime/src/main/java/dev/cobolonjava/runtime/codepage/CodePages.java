package dev.cobolonjava.runtime.codepage;

import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * コードページのレジストリ (要件 FR-051)。
 *
 * <p><b>暫定対応</b>: IBM-1390 / IBM-1399 (JIS X 0213:2004 拡張を含む日本語混在コードページ) は
 * JDK 標準のチャーセットに存在しないため、現時点では未対応である。要件 FR-051 が求める独自変換表の
 * 同梱は未実施であり、{@code docs/decisions/provisional.md} の P-002 として記録している。
 */
public final class CodePages {

    /** Latin-1 / Open Systems 用 EBCDIC。 */
    public static final CodePage IBM_1047 = ebcdic("IBM-1047", "IBM1047");

    /** 米国・カナダ EBCDIC。 */
    public static final CodePage IBM_037 = ebcdic("IBM-037", "IBM037");

    /** 日本語 (カタカナ) 混在 EBCDIC。 */
    public static final CodePage IBM_930 = ebcdic("IBM-930", "x-IBM930");

    /** 日本語 (英小文字) 混在 EBCDIC。 */
    public static final CodePage IBM_939 = ebcdic("IBM-939", "x-IBM939");

    /** 検証・デバッグ用の ASCII。ホスト互換の対象ではない。 */
    public static final CodePage ASCII = new CodePage("ASCII", Charset.forName("US-ASCII"), 0x3);

    /** 明示されない場合の既定 (要件 FR-050: 実行時の内部文字コードは EBCDIC)。 */
    public static final CodePage DEFAULT = IBM_1047;

    private static final Map<String, CodePage> BY_NAME = new ConcurrentHashMap<>();

    static {
        for (CodePage cp : new CodePage[] {IBM_1047, IBM_037, IBM_930, IBM_939, ASCII}) {
            BY_NAME.put(normalize(cp.name()), cp);
        }
        BY_NAME.put("1047", IBM_1047);
        BY_NAME.put("37", IBM_037);
        BY_NAME.put("037", IBM_037);
        BY_NAME.put("930", IBM_930);
        BY_NAME.put("939", IBM_939);
    }

    private CodePages() {
    }

    /**
     * 名前からコードページを引く。{@code CODEPAGE(1047)} のような数値指定も受け付ける。
     *
     * @throws UnsupportedCharsetException 未対応のコードページが指定された場合。
     *         要件 FR-181 の「未実装のオプションは無視せず未対応と診断する」に従い、黙って既定へ倒さない。
     */
    public static CodePage forName(String name) {
        CodePage cp = BY_NAME.get(normalize(name));
        if (cp == null) {
            throw new UnsupportedCharsetException(
                    "code page not supported by this runtime: " + name);
        }
        return cp;
    }

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").replace("CP", "").replace("IBM", "");
    }

    private static CodePage ebcdic(String name, String charsetName) {
        return new CodePage(name, Charset.forName(charsetName), 0xF);
    }
}
