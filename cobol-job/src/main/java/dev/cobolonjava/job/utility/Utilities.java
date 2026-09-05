package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ユーティリティの一覧 (要件 FR-137)。
 *
 * <p>ジョブが名指すプログラムのうち、<b>翻訳された資産ではないもの</b>をここで引き当てる。
 * 引き当たらなければ、翻訳されたクラスを探しに行く。名前が重なったときにユーティリティが
 * 勝つのは、ホストでもシステムのライブラリが先に見つかるからである。
 */
public final class Utilities {

    private Utilities() {
    }

    private static final Map<String, Supplier<CobolProgram>> PROGRAMS = programs();

    private static Map<String, Supplier<CobolProgram>> programs() {
        Map<String, Supplier<CobolProgram>> out = new LinkedHashMap<>();
        out.put("IEFBR14", Iefbr14::new);
        out.put("IEBGENER", Iebgener::new);
        out.put("IDCAMS", Idcams::new);
        return Map.copyOf(out);
    }

    /**
     * 名前でユーティリティを引く。
     *
     * @return 知らない名前なら {@code null}
     */
    public static CobolProgram find(String name) {
        Supplier<CobolProgram> program = PROGRAMS.get(name.toUpperCase(Locale.ROOT));
        return program == null ? null : program.get();
    }

    /** 引き当てられる名前。 */
    public static java.util.Set<String> names() {
        return PROGRAMS.keySet();
    }
}
