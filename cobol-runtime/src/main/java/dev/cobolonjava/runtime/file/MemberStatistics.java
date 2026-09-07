package dev.cobolonjava.runtime.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * メンバの統計 (要件 FR-113、暫定判断 P-059 の解消)。
 *
 * <p>ISPF がメンバを書くたびに更新する覚え書きである。版と更新レベル、作った日、
 * 最後に直した日時、行数、直した人の利用者 ID が入っている。ホストではディレクトリの
 * 項目に<b>付いて</b>おり、メンバのバイト列の中には無い。
 *
 * <h2>統計は「メンバがあるか」を決めない</h2>
 * <p>これを持たせると、メンバがあるかどうかの拠り所が「ファイルがある」と「覚え書きに
 * 載っている」の 2 つになりかねない。そうはしない — <b>一覧はいまでも置かれている
 * ファイルから引く</b> ({@link PartitionedDataSet#members}) のであって、統計はそこへ
 * 後から付く飾りである。サイドカーが無いメンバは<b>統計を持たないメンバ</b>であり、
 * 無いメンバではない。
 *
 * <p>そしてそれはホストでも同じことである。ISPF の編集で書いたメンバには統計が付くが、
 * プログラムが {@code OPEN OUTPUT} で書いたメンバには<b>付かない</b>。ISPF の一覧で
 * 統計の欄が空のメンバが混じるのはそのためである。だからここでも、書いた側が統計を
 * 作ることはしない。写す道具が<b>持っていたものを持ち越す</b>だけである。
 *
 * <h2>なぜ持つのか — ディレクトリの項目の大きさが変わる</h2>
 * <p>統計が要るのは表示のためだけではない。ホストのディレクトリの項目は、名前 8 バイトと
 * 位置 3 バイトと標識 1 バイトの<b>12 バイトに、利用者データが付いた</b>大きさである。
 * 統計はその利用者データ 30 バイトであり、付いていれば項目は 42 バイトになる。
 * 1 つのディレクトリブロック (256 バイト) に入る数が 21 と 6 で変わるということであり、
 * <b>ディレクトリを使い切って止まる場所が変わる</b>。統計を持たずに数だけ決めると、
 * 統計を入れた日に止まる場所が動く。
 *
 * <p>サイドカーは {@code メンバのパス + ".stats"} に置く。属性の {@code ".meta"} とは
 * 別のファイルである。属性を書き直す道具が統計を消してしまわないためであり、
 * どちらも書き手が違う — 属性はバイト列を切った側が、統計は編集した側が持つ。
 *
 * @param version      版。{@code VV.MM} の {@code VV}
 * @param modification 更新レベル。{@code VV.MM} の {@code MM}
 * @param created      作った日
 * @param changed      最後に直した日時
 * @param currentLines いまの行数
 * @param initialLines 作ったときの行数
 * @param modifiedLines 直した行数
 * @param userId       最後に直した人の利用者 ID
 */
public record MemberStatistics(int version, int modification, LocalDate created,
                               LocalDateTime changed, int currentLines, int initialLines,
                               int modifiedLines, String userId) {

    /**
     * ディレクトリの項目に付く大きさ (バイト)。
     *
     * <p>ホストの ISPF 統計は 30 バイトである。ディレクトリの項目の標識バイトは
     * 利用者データの長さを<b>半語の数</b>で持つので、15 半語にあたる。
     */
    public static final int LENGTH = 30;

    /** サイドカーのパス。 */
    public static Path sidecarOf(Path member) {
        return member.resolveSibling(member.getFileName() + ".stats");
    }

    /**
     * 統計を読む。
     *
     * <p>無いことは誤りではない。プログラムが書いたメンバには統計が付かないのが
     * ホストの姿である。
     *
     * @return 持っていなければ {@code null}
     */
    public static MemberStatistics read(Path member) {
        Path sidecar = sidecarOf(member);
        if (!Files.isReadable(sidecar)) {
            return null;
        }
        int version = 1;
        int modification = 0;
        LocalDate created = null;
        LocalDateTime changed = null;
        int current = 0;
        int initial = 0;
        int modified = 0;
        String user = "";
        try {
            for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
                String text = line.trim();
                int equals = text.indexOf('=');
                if (text.isEmpty() || text.startsWith("#") || equals < 0) {
                    continue;
                }
                String name = text.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                String value = text.substring(equals + 1).trim();
                switch (name) {
                    case "vvmm" -> {
                        int dot = value.indexOf('.');
                        version = number(dot < 0 ? value : value.substring(0, dot), 1);
                        modification = dot < 0 ? 0 : number(value.substring(dot + 1), 0);
                    }
                    case "created" -> created = dateOf(value);
                    case "changed" -> changed = momentOf(value);
                    case "lines" -> current = number(value, 0);
                    case "init" -> initial = number(value, 0);
                    case "mod" -> modified = number(value, 0);
                    case "id" -> user = value.toUpperCase(Locale.ROOT);
                    default -> {
                        // 知らない名前は読み飛ばす。ホストの統計が増えても読めなくならない
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + sidecar, e);
        }
        return new MemberStatistics(version, modification, created, changed,
                current, initial, modified, user);
    }

    /** 統計を書く。 */
    public void write(Path member) {
        StringBuilder sb = new StringBuilder();
        sb.append("vvmm=").append(String.format("%02d.%02d", version, modification))
                .append(System.lineSeparator());
        if (created != null) {
            sb.append("created=").append(created).append(System.lineSeparator());
        }
        if (changed != null) {
            sb.append("changed=").append(changed).append(System.lineSeparator());
        }
        sb.append("lines=").append(currentLines).append(System.lineSeparator());
        sb.append("init=").append(initialLines).append(System.lineSeparator());
        sb.append("mod=").append(modifiedLines).append(System.lineSeparator());
        sb.append("id=").append(userId).append(System.lineSeparator());
        try {
            Files.writeString(sidecarOf(member), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + sidecarOf(member), e);
        }
    }

    /**
     * 統計を写す。持っていなければ写し先のものも消す。
     *
     * <p>写し先に古い統計が残っていると、<b>中身は新しいのに統計は前のまま</b>になる。
     * 統計を見て版を判断している運用があれば、それは取り違えになる。
     */
    public static void copy(Path from, Path to) {
        MemberStatistics statistics = read(from);
        if (statistics == null) {
            forget(to);
        } else {
            statistics.write(to);
        }
    }

    /** 統計を消す。メンバを消したときに残しておくと、次に同じ名前で作った人のものになる。 */
    public static void forget(Path member) {
        try {
            Files.deleteIfExists(sidecarOf(member));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + sidecarOf(member), e);
        }
    }

    /** {@code VV.MM} の形。 */
    public String level() {
        return String.format("%02d.%02d", version, modification);
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDate dateOf(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime momentOf(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
