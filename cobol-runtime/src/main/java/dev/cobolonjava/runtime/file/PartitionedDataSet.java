package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 区分データセットのディレクトリ (要件 FR-113、暫定判断 P-056 の解消)。
 *
 * <p>ホストの区分データセットは、データの手前に<b>ディレクトリ</b>を持つ。メンバの名前と
 * 置き場所が並んだ表であり、<b>名前の昇順</b>に並んでいる。メンバを 1 つずつ名指して読む
 * だけならこの表は要らないが、{@code IEBCOPY} のように<b>全部のメンバを順に扱う</b>操作は
 * この並びで動く。並びが違えば出てくるバイト列が違う。
 *
 * <h2>並びはコードページの順である (要件 FR-053)</h2>
 * <p>ディレクトリの並びはメンバ名を<b>そのまま比べた</b>順である。ホストの文字は EBCDIC
 * なので、英字が数字より<b>前</b>に来る。Java の {@code String} の順は UTF-16 の順であり、
 * 数字が英字より前に来る。したがって {@code PAY1} と {@code PAYA} の並びが逆になる。
 *
 * <p>これは「どちらでもよい細かい違い」ではない。ライブラリを丸ごと写したジョブの出力が
 * <b>実機とここで別のバイト列になる</b>ということであり、写した先を読む後続のジョブまで
 * 巻き込む。並べる場所が増えるたびに書き分ければいずれ食い違うので、ここに 1 つだけ置く。
 *
 * <h2>メンバ名の決まりが、ディレクトリの中身を決める</h2>
 * <p>ライブラリは置き場のディレクトリ、メンバはその下のファイルである。すると覚え書きの
 * サイドカー ({@code PAYROLL.meta}) もその下に並ぶ。どこまでがメンバかを決めるのが
 * <b>メンバ名の決まり</b>である — ホストのメンバ名は 8 文字までで、使えるのは英大文字と
 * 数字と {@code @ # $}、先頭は数字ではない。{@code .} は入らないので、サイドカーは
 * メンバになりえない。名前の決まりを入れて初めてディレクトリが言えるようになる。
 */
public final class PartitionedDataSet {

    private PartitionedDataSet() {
    }

    /** メンバ名の長さの上限。ホストのディレクトリの項目が 8 バイトである。 */
    public static final int NAME_LENGTH = 8;

    /**
     * ホストのメンバ名として通るか (要件 FR-113)。
     *
     * <p>8 文字まで、英大文字・数字・{@code @ # $}、先頭は数字でない。実機ではジョブを
     * 読む段で弾かれるので、ステップは<b>動かない</b>。ここで通してしまうと、実機なら
     * JCL 誤りで止まるジョブがここでは動き、しかも long-name のファイルができる。
     */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = c >= 'A' && c <= 'Z';
            boolean digit = c >= '0' && c <= '9';
            boolean national = c == '@' || c == '#' || c == '$';
            if (!(letter || national || (digit && i > 0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 名前の並び順 (要件 FR-053)。
     *
     * <p>コードページのバイト列をそのまま比べる。短いほうが先に来るのは、ホストが名前を
     * 8 バイトに<b>空白で埋めて</b>持っており、空白がどの文字よりも小さいからである。
     */
    public static Comparator<String> order(CodePage codePage) {
        return (left, right) -> compare(codePage.encode(left), codePage.encode(right));
    }

    private static int compare(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int difference = (left[i] & 0xFF) - (right[i] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return left.length - right.length;
    }

    /**
     * ライブラリのメンバの一覧 (要件 FR-113)。
     *
     * <p>ディレクトリを別に持たず、置かれているファイルから引く。持たせれば
     * {@code OPEN OUTPUT} と削除のたびに合わせ込む手間が要り、<b>メンバがあるかどうかの
     * 拠り所が 2 つになる</b>。合わなくなったときにどちらが正しいとも言えない。
     * 移行の途中で手で置いたファイルがそのまま見えるのも、引くほうの利点である。
     *
     * @return 名前の昇順。区分データセットでなければ空
     */
    public static List<String> members(Path library, CodePage codePage) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(library)) {
            return names;
        }
        try (var stream = Files.list(library)) {
            stream.map(path -> path.getFileName().toString())
                    .filter(PartitionedDataSet::validName)
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + library, e);
        }
        names.sort(order(codePage));
        return names;
    }

    /** メンバの置き場所。名前は大文字で持つ。 */
    public static Path memberOf(Path library, String name) {
        return library.resolve(name.toUpperCase(Locale.ROOT));
    }
}
