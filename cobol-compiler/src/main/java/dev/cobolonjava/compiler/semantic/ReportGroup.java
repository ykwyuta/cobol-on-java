package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.source.Origin;
import java.util.List;

/**
 * 報告集団 1 個 (要件 FR-214)。
 *
 * <p>集団は<b>行の並び</b>である。行は {@code LINE} 句が現れるたびに始まり、
 * 続く欄はその行に属する。{@code LINE} 句を持つ項目そのものが欄でもありうる
 * (RW101A は 1 個の {@code 03} に {@code LINE} と {@code COLUMN} を両方書いている)。
 *
 * <p>行の見た目は<b>普通のレコード記述</b>に落としてある。{@code recordName} が
 * その 01 レベルの名前であり、欄は {@code COLUMN} の位置に置いた基本項目である。
 * こうすると、値を入れるのは普通の {@code MOVE} で済み、編集も詰め方も
 * すでに確かめてある道を通る。
 */
public record ReportGroup(String name, Type type, List<ReportLine> lines, Origin origin) {

    public ReportGroup {
        lines = List.copyOf(lines);
    }

    /** 報告集団の種類。どこへ、いつ置くかを決める。 */
    public enum Type {
        /** 報告書の先頭に 1 度だけ。 */
        REPORT_HEADING,
        /** 各頁の先頭に。 */
        PAGE_HEADING,
        /** {@code GENERATE} が置く本文。 */
        DETAIL,
        /** 各頁の末尾に。 */
        PAGE_FOOTING,
        /** 報告書の末尾に 1 度だけ。 */
        REPORT_FOOTING;

        /** 本文の集団か。本文だけが {@code FIRST DETAIL} と {@code LAST DETAIL} に従う。 */
        public boolean isBody() {
            return this == DETAIL;
        }
    }

    /**
     * 行 1 本。
     *
     * @param placement  どの行へ置くか
     * @param recordName その行の姿を表す 01 レベルの名前
     * @param fields     その行に並ぶ欄
     */
    public record ReportLine(Placement placement, String recordName, List<ReportField> fields) {

        public ReportLine {
            fields = List.copyOf(fields);
        }
    }

    /**
     * 行の置き場所。
     *
     * @param kind 数え方
     * @param n    行数。{@link Kind#NEXT_PAGE} では使わない
     */
    public record Placement(Kind kind, int n) {

        public enum Kind {
            /** {@code LINE 5} — 頁の 5 行目。 */
            ABSOLUTE,
            /** {@code LINE PLUS 1} — いま居るところから 1 行下。 */
            RELATIVE,
            /** {@code LINE NEXT PAGE} — 次の頁の先頭。 */
            NEXT_PAGE
        }
    }

    /**
     * 行の中の欄 1 個。
     *
     * <p>{@code slotName} は行のレコードの中に作った基本項目である。行の姿は
     * ファイル節のレコード領域なので<b>{@code VALUE} で初期化されない</b>。書くたびに
     * 空白で埋め直すためでもある。だから定数の欄も、置くときに入れる。
     *
     * @param slotName 行のレコードの中の項目名
     * @param source   {@code SOURCE} に書かれた一意名。定数の欄では {@code null}
     * @param value    {@code VALUE} に書かれた定数。{@code SOURCE} の欄では {@code null}
     */
    public record ReportField(String slotName, CobolParser.IdentifierContext source,
                              LiteralValue value, Origin origin) {
    }
}
