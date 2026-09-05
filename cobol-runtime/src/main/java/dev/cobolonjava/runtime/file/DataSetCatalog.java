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

    /** DD 名が指すファイル。書かれていなければ既定のディレクトリの下を指す。 */
    public Path resolve(String ddName) {
        Path assigned = assignments.get(ddName.toUpperCase(Locale.ROOT));
        return assigned != null ? assigned : directory.resolve(ddName);
    }
}
