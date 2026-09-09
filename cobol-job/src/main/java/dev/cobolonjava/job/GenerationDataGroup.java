package dev.cobolonjava.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 世代データグループ (要件 FR-114、暫定判断 P-060)。
 *
 * <h2>世代は普通のデータセットである</h2>
 * <p>ホストの世代データグループは<b>入れ物ではない</b>。{@code A.B.C} という基底名を目録へ
 * 登録しておくと、実際のデータは {@code A.B.C.G0001V00}、{@code A.B.C.G0002V00} … という
 * <b>普通のデータセット</b>として並ぶ。{@code A.B.C(+1)} と書けるのは、JCL が相対番号を
 * この絶対名へ置き換えるからであって、括弧の中がメンバ名のように解釈されるわけではない。
 *
 * <p>だからここでも世代の一覧を別に持たない。置き場に並んだ名前のうち
 * {@code 基底名.GnnnnV00} の形をしていて、かつ<b>目録に載っている</b>ものが世代である。
 * 区分データセットのディレクトリと同じ考え方であり (要件 FR-113)、「その世代があるか」の
 * 答えが 2 通りにならない。
 *
 * <h2>基底だけは覚えるほかない</h2>
 * <p>覚えねばならないのは基底の定義 — 何世代まで残すか ({@code LIMIT})、あふれたときに
 * 1 つだけ外すのか全部外すのか ({@code NOEMPTY} / {@code EMPTY})、外したものを消すのか
 * 残すのか ({@code NOSCRATCH} / {@code SCRATCH}) — である。これは置き場のファイルからは
 * 引けないので、{@value #INDEX} という覚え書きに書く。
 *
 * <p>目録 ({@link SystemCatalog}) と分けてあるのは、<b>寿命が違う</b>からである。目録の
 * 項目はデータセットを消せば消えるが、基底の定義は世代が 1 つも無くなっても残る。
 * {@code DELETE 基底 GDG} と書くまで生きている。
 *
 * <h2>番号はジョブの初めに決まる</h2>
 * <p>相対番号を絶対名へ直すのは<b>ジョブを読む段で 1 度だけ</b>である。ステップ 1 が
 * {@code (+1)} で作ったあとでも、ステップ 2 の {@code (0)} はジョブが始まる前の世代を
 * 指し、ステップ 2 の {@code (+1)} はステップ 1 が作ったのと<b>同じ</b>データセットを指す。
 * ホストの JCL がそう動くからであり、ステップごとに引き直すと、実機では 1 つのファイルへ
 * 書き足すつもりのジョブが、ここでは世代を 2 つ作ってしまう。
 *
 * <p>同じ理由で、{@code DEFINE GDG} をしたジョブの中でその基底を相対番号で参照することは
 * できない。ジョブが始まった時点で基底が無いからである。ホストにも同じ制限がある。
 */
public final class GenerationDataGroup {

    /** 基底の定義を書く覚え書き。データセットの名前は点で始まらないので紛れない。 */
    static final String INDEX = ".gdg";

    /** 世代の絶対名の形。{@code GnnnnV00} が付く。 */
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)^(.+)\\.G(\\d{4})V(\\d{2})$");

    /**
     * 相対世代の形。{@code (0)}、{@code (+1)}、{@code (-1)} の括弧の中である。
     *
     * <p>桁を 3 つまでに絞ってある。ホストが数えられるのも {@code -255} から
     * {@code +255} までであり、それより長い数字だけの括弧は<b>誤ったメンバ名</b>として
     * 弾かれるほうがよい。メンバ名は数字で始まれないので、どちらに転んでも通らない。
     */
    private static final Pattern RELATIVE = Pattern.compile("[+-]?\\d{1,3}");

    /**
     * 基底の定義。
     *
     * @param limit   残す世代の数。あふれたぶんは外れる
     * @param empty   あふれたときに<b>全部</b>外すか。{@code false} なら古い 1 つだけ
     * @param scratch 外した世代を置き場からも消すか。{@code false} なら目録から外すだけ
     */
    public record Definition(String name, int limit, boolean empty, boolean scratch) {
    }

    private final Path volume;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    /** 置き場の覚え書きを読んで開く。書き換えは即座に覚え書きへ落ちる。 */
    public GenerationDataGroup(Path volume) {
        this.volume = volume;
        read();
    }

    /** その名前が世代データグループの基底として登録されているか。 */
    public boolean defined(String base) {
        return definitions.containsKey(key(base));
    }

    /** 登録されている基底の名前。 */
    public List<String> bases() {
        return new ArrayList<>(definitions.keySet());
    }

    /** 基底の定義。登録されていなければ {@code null}。 */
    public Definition definitionOf(String base) {
        return definitions.get(key(base));
    }

    /** 基底を登録する。{@code DEFINE GDG} である。 */
    public void define(Definition definition) {
        definitions.put(key(definition.name()), definition);
        flush();
    }

    /** 基底の登録を消す。{@code DELETE 基底 GDG} である。世代そのものには触れない。 */
    public boolean undefine(String base) {
        if (definitions.remove(key(base)) == null) {
            return false;
        }
        flush();
        return true;
    }

    /**
     * 絶対名を組み立てる。{@code A.B.C} と 1 なら {@code A.B.C.G0001V00} である。
     *
     * <p>0 以下の番号もそのまま形にする。{@code A.B.C(0)} と書いたのに世代が 1 つも
     * 無ければ {@code G0000V00} になり、<b>そんなデータセットは無い</b>ので割当てで
     * 弾かれる。これはホストが {@code DATA SET NOT FOUND} を出すのと同じ結末である。
     */
    public static String nameOf(String base, int generation) {
        // 9999 を越えたときホストは 1 へ戻る。ここでは戻さない (暫定判断 P-060)。
        // 戻すと古い世代を上書きすることになり、桁を越えた名前のほうがまだ気付ける
        return String.format("%s.G%04dV00", base, Math.max(generation, 0));
    }

    /** 絶対名の世代番号。{@code 基底名.GnnnnV00} の形でなければ {@code null}。 */
    public static Integer generationOf(String base, String name) {
        Matcher matcher = ABSOLUTE.matcher(name);
        if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(base)) {
            return null;
        }
        return Integer.valueOf(matcher.group(2));
    }

    /**
     * 絶対名から基底名を取り出す。{@code GnnnnV00} で終わっていなければ {@code null}。
     *
     * <p>{@code DSN=A.B.C.G0001V00} と絶対名で書いたジョブも世代を作る。そのデータセットが
     * どの群れのものかを言えるのは<b>名前の形だけ</b>である。
     */
    public static String baseOf(String name) {
        Matcher matcher = ABSOLUTE.matcher(name);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** 括弧の中が相対世代の形をしているか。{@code (0)}、{@code (+1)}、{@code (-1)} である。 */
    public static boolean relative(String qualifier) {
        return RELATIVE.matcher(qualifier).matches();
    }

    /**
     * 置き場にある世代を古い順に並べる。
     *
     * <p>数えるのは<b>目録に載っているものだけ</b>である。あふれて外した世代は置き場に
     * 残っていても ({@code NOSCRATCH}) もう群れの一員ではなく、{@code (0)} も
     * {@code (-1)} も届かない。{@code NEW} で作ったばかりで、まだ {@code CATLG} が
     * 効いていない世代も同じく数えない。ホストが<b>ロールインまで新しい世代を数えない</b>
     * のと同じである。
     */
    public static List<Integer> generations(Path volume, SystemCatalog system, String base) {
        List<Integer> numbers = new ArrayList<>();
        if (!Files.isDirectory(volume)) {
            return numbers;
        }
        try (var stream = Files.list(volume)) {
            stream.map(path -> path.getFileName().toString()).forEach(name -> {
                Integer generation = generationOf(base, name);
                if (generation != null && system.isCataloged(name)) {
                    numbers.add(generation);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + volume, e);
        }
        numbers.sort(Integer::compareTo);
        return numbers;
    }

    /**
     * いま何世代目か。1 つも無ければ {@code 0} である。
     *
     * <p>{@code (0)} が指すのがこの番号であり、{@code (+1)} はその次、{@code (-1)} は
     * 1 つ前である。
     */
    public static int current(Path volume, SystemCatalog system, String base) {
        List<Integer> numbers = generations(volume, system, base);
        return numbers.isEmpty() ? 0 : numbers.get(numbers.size() - 1);
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    private void read() {
        Path index = volume.resolve(INDEX);
        if (!Files.isReadable(index)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                Definition definition = parse(line);
                if (definition != null) {
                    definitions.put(key(definition.name()), definition);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + index, e);
        }
    }

    /** 覚え書きの 1 行は {@code 名前 限り EMPTY|NOEMPTY SCRATCH|NOSCRATCH} である。 */
    private static Definition parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Definition(parts[0], Integer.parseInt(parts[1]),
                    parts[2].equalsIgnoreCase("EMPTY"), parts[3].equalsIgnoreCase("SCRATCH"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void flush() {
        StringBuilder sb = new StringBuilder();
        for (Definition definition : definitions.values()) {
            sb.append(definition.name()).append(' ').append(definition.limit())
                    .append(definition.empty() ? " EMPTY" : " NOEMPTY")
                    .append(definition.scratch() ? " SCRATCH" : " NOSCRATCH")
                    .append('\n');
        }
        Path index = volume.resolve(INDEX);
        try {
            Files.createDirectories(volume);
            Files.writeString(index, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + index, e);
        }
    }
}
