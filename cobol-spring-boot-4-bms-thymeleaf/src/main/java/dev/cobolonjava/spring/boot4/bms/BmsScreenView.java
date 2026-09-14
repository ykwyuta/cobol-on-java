package dev.cobolonjava.spring.boot4.bms;

import java.util.List;
import java.util.Objects;

/**
 * Thymeleaf が描く BMS 画面 (設計 77 §4.5.2、設計 81)。
 *
 * <p>中立の {@code BmsScreenSnapshot} から作る表示専用の形である。HTML の断片や style を持たず、
 * 属性は列挙した CSS class の名前だけで表す。行と桁は 1 起点である。
 *
 * <p>配置は server が決める。画面の各行を「空白の並び」と「field」の列にして渡すので、
 * JavaScript が動かなくても等幅の文字で行と桁が揃う (設計 81 §3)。
 *
 * @param cursorRow    cursor の行。決まっていなければ 0
 * @param cursorColumn cursor の桁。決まっていなければ 0
 * @param lines        画面の行。各行は画面の幅ぶんの item を持つ
 */
public record BmsScreenView(
        String mapset,
        String map,
        int rows,
        int columns,
        int cursorRow,
        int cursorColumn,
        boolean alarm,
        boolean keyboardRestored,
        List<Segment> segments,
        List<Line> lines) {

    public BmsScreenView {
        Objects.requireNonNull(mapset, "mapset");
        Objects.requireNonNull(map, "map");
        segments = List.copyOf(segments);
        lines = List.copyOf(lines);
    }

    /** 画面の 1 行。 */
    public record Line(int number, List<Item> items) {

        public Line {
            items = List.copyOf(items);
        }
    }

    /**
     * 行の中の 1 つの並び。segment が null なら {@code blank} の空白だけを置く。
     *
     * @param blank 空白の並び。field の item では空
     */
    public record Item(String blank, Segment segment) {

        public Item {
            Objects.requireNonNull(blank, "blank");
        }
    }

    /**
     * 画面上の field 1 つの表示。
     *
     * @param parameterName 入力 field なら送信に使う名前 ({@code bms.NAME.occurrence})、出力だけなら null
     * @param row           データの先頭の行
     * @param column        データの先頭の桁 (属性 byte の次の桁)
     * @param text          表示する文字。DRK の入力 field では空、DRK の出力 field では長さぶんの空白であり、
     *                      どちらも値そのものは持たない
     * @param cssClass      列挙済みの CSS class を空白で区切ったもの。入力 field の幅 ({@code bms-len-N}) を含む
     * @param label         支援技術へ示す名前
     */
    public record Segment(
            String parameterName,
            int row,
            int column,
            int length,
            boolean input,
            boolean numeric,
            boolean dark,
            boolean modified,
            String text,
            String cssClass,
            String label) {

        public Segment {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(cssClass, "cssClass");
            Objects.requireNonNull(label, "label");
        }
    }
}
