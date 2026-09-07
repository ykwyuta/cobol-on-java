package dev.cobolonjava.runtime.file;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * DD 名から実際のファイルを探す (要件 FR-112, FR-130 台)。
 *
 * <p>{@code SELECT ... ASSIGN TO 名前} の名前は<b>DD 名</b>であり、ファイルの場所そのものでは
 * ない。ホストでは JCL が DD 名とデータセットを結び付ける。ここではその対応表を持つ。
 *
 * <p>書かれていなければ、既定のディレクトリの下から<b>同じ名前で</b>探す。ジョブ実行を
 * 実装するときに、JCL からこの表を埋めることになる。
 */
public final class DataSetCatalog {

    private final Path directory;
    private final Map<String, Path> assignments = new HashMap<>();
    /** {@code OPEN OUTPUT} を末尾への書き足しとして扱う DD 名。 */
    private final java.util.Set<String> appended = new java.util.HashSet<>();
    /** DD 名ごとに割り当てた領域の大きさ (バイト)。 */
    private final Map<String, Long> limits = new HashMap<>();
    /** 区分データセットのメンバを指す DD 名。 */
    private final java.util.Set<String> members = new java.util.HashSet<>();

    public DataSetCatalog(Path directory) {
        this.directory = directory;
    }

    /** 現在のディレクトリを既定とする構成。 */
    public static DataSetCatalog standard() {
        return new DataSetCatalog(Path.of("."));
    }

    /** DD 名と実際のファイルを結び付ける。 */
    public DataSetCatalog assign(String ddName, Path path) {
        assignments.put(ddName.toUpperCase(Locale.ROOT), path);
        return this;
    }

    /**
     * その DD 名が結び付けられているか。
     *
     * <p>ジョブが書いていない DD 名は、既定のディレクトリの下の同じ名前を指すことになる。
     * ユーティリティの覚え書きのように<b>書き先がなければ出さない</b>ものが、これを見る。
     */
    public boolean isAssigned(String ddName) {
        return assignments.containsKey(ddName.toUpperCase(Locale.ROOT));
    }

    /** 既定のディレクトリ。結び付けられていない名前はこの下を指す。 */
    public Path directory() {
        return directory;
    }

    /**
     * その DD を<b>末尾への書き足し</b>として開くことにする (要件 FR-133)。
     *
     * <p>JCL の {@code DISP=MOD} である。プログラムが {@code OPEN OUTPUT} と書いていても
     * 末尾へ足す。<b>ジョブの指定がプログラムの書いたことを覆す</b>数少ない場所であり、
     * 同じプログラムを「作り直す」使い方と「積み増す」使い方の両方へ向けられる。
     */
    public DataSetCatalog appendTo(String ddName) {
        appended.add(ddName.toUpperCase(Locale.ROOT));
        return this;
    }

    /** その DD を末尾への書き足しとして開くか。 */
    public boolean appends(String ddName) {
        return appended.contains(ddName.toUpperCase(Locale.ROOT));
    }

    /**
     * その DD に割り当てる領域の大きさ (要件 FR-141)。
     *
     * <p>JCL の {@code SPACE=} である。<b>ジョブが決めることであってプログラムは知らない</b>
     * ので、データセットの属性ではなくここに持つ。属性はバイト列の切り方を決めるものであり、
     * 置き場をどれだけ取ったかとは別の話である。
     *
     * @param bytes 書ける大きさ。{@code 0} なら限りなし
     */
    public DataSetCatalog limit(String ddName, long bytes) {
        limits.put(ddName.toUpperCase(Locale.ROOT), bytes);
        return this;
    }

    /** その DD に割り当てた領域の大きさ。書かれていなければ {@code 0} (限りなし)。 */
    public long limitOf(String ddName) {
        return limits.getOrDefault(ddName.toUpperCase(Locale.ROOT), 0L);
    }

    /**
     * その DD が<b>区分データセットのメンバ</b>を指すことにする (要件 FR-113)。
     *
     * <p>{@code DSN=ライブラリ(メンバ)} である。無かったときの意味が変わる — 順編成なら
     * 「無いファイル」だが、こちらは<b>データセットはあってメンバだけが無い</b>。
     * 割当ては通っているので、開く段で {@code S013} になる。
     *
     * <p>パスからは決められない。メンバはディレクトリの下のファイルだが、順編成の
     * データセットも置き場の下のファイルであり、見分けがつかないからである。
     */
    public DataSetCatalog memberOfLibrary(String ddName) {
        members.add(ddName.toUpperCase(Locale.ROOT));
        return this;
    }

    /** その DD が区分データセットのメンバを指すか。 */
    public boolean isMemberOfLibrary(String ddName) {
        return members.contains(ddName.toUpperCase(Locale.ROOT));
    }

    /** DD 名が指すファイル。書かれていなければ既定のディレクトリの下を指す。 */
    public Path resolve(String ddName) {
        Path assigned = assignments.get(ddName.toUpperCase(Locale.ROOT));
        return assigned != null ? assigned : directory.resolve(ddName);
    }
}
