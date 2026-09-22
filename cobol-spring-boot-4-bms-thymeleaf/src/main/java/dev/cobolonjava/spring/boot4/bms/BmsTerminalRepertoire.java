package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePageRepertoire;
import java.util.List;
import java.util.Objects;

/**
 * 端末が受け付ける文字の一覧を、ブラウザが読める形にする (設計 81 §5.1)。
 *
 * <p>ブラウザの入力欄は Unicode を何でも受ける。コードページに無い文字は、送ったあと
 * RECEIVE MAP が符号化するところで初めて断られる。利用者から見れば<b>打てたのに送れない</b>で
 * あり、実機の 3270 (キーボードがその文字を出さない) とは違う。client が同じ判断をするには、
 * どの文字が入るかを知っている必要がある。それをこの JSON で渡す。
 *
 * <p>一覧は {@link CodePageRepertoire} が<b>実行時と同じ charset</b> から作るので、server が
 * 受ける文字と client が通す文字はずれない。ただし client は<b>最終判定ではない</b>。要求は
 * 改変できるので、server は RECEIVE MAP で同じことを確かめ直す (ADR-0010)。
 *
 * <h2>形</h2>
 *
 * <pre>
 * {"codePage":"IBM-930","shifted":true,"single":"0-ff","double":"a6-a8,b0-b1,391-3a1,..."}
 * </pre>
 *
 * <p>範囲は符号位置の 16 進で、両端を含む。1 つだけの範囲は端を書かない。IBM-930 の DBCS は
 * 4000 を超える範囲になるので、配列より短いこの形にした (JSON で約 29KB)。
 *
 * <p>JSON は<b>コードページごとに 1 度だけ</b>作る。走査は 1 秒ほどかかるので、最初の要求が
 * それを払う。以後は覚えたものを返し、ブラウザにも期限つきで持たせる。
 */
public final class BmsTerminalRepertoire {

    private final CodePage codePage;
    private volatile String json;

    public BmsTerminalRepertoire(CodePage codePage) {
        this.codePage = Objects.requireNonNull(codePage, "codePage");
    }

    /** ブラウザへ渡すコードページ。実行時のコードページと同じでなければならない。 */
    public CodePage codePage() {
        return codePage;
    }

    public String json() {
        String current = json;
        if (current == null) {
            CodePageRepertoire repertoire = CodePageRepertoire.of(codePage);
            current = "{\"codePage\":\"" + codePage.name() + "\""
                    + ",\"shifted\":" + repertoire.shifted()
                    + ",\"single\":\"" + ranges(repertoire.singleByte()) + "\""
                    + ",\"double\":\"" + ranges(repertoire.doubleByte()) + "\"}";
            json = current;
        }
        return current;
    }

    private static String ranges(List<CodePageRepertoire.Range> ranges) {
        StringBuilder out = new StringBuilder();
        for (CodePageRepertoire.Range range : ranges) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(Integer.toHexString(range.from()));
            if (range.to() != range.from()) {
                out.append('-').append(Integer.toHexString(range.to()));
            }
        }
        return out.toString();
    }
}
