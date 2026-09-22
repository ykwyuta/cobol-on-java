package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * {@link BmsScreenSnapshot} を {@link BmsScreenView} へ変える (設計 77 §4.5.2、設計 81)。
 *
 * <p>属性は列挙した class へだけ写す。BMS の literal や field の値を class や style へ流さない。
 * DRK の field は値を画面にも DOM にも出さない。黒い文字にするだけでは機密を守れないからである。
 */
public final class BmsScreenViewFactory {

    /** 幅の class ({@code bms-len-N}) を CSS に用意している最大の桁数。27x132 の端末まで表せる。 */
    public static final int MAX_COLUMNS = 132;

    public BmsScreenView create(BmsScreenSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.columns() > MAX_COLUMNS) {
            throw new IllegalArgumentException("screens wider than " + MAX_COLUMNS + " columns are not supported");
        }
        List<BmsScreenView.Segment> segments = new ArrayList<>();
        for (BmsScreenSnapshot.FieldState field : snapshot.fields()) {
            if (field.length() == 0) {
                continue;
            }
            // 属性 byte の次の桁からデータが始まる
            int row = field.position().row();
            int column = field.position().column() + 1;
            if (column > snapshot.columns()) {
                row++;
                column = 1;
            }
            if (row > snapshot.rows() || column + field.length() - 1 > snapshot.columns()) {
                // 画面端をまたぐ field は 1 つの論理 field を複数の visual segment に分ける設計 (§4.5.2) だが、
                // まだ持たない。位置をずらして描くと別の桁の field に見えるので断る
                throw new IllegalArgumentException("field " + field.name().orElse("(literal)")
                        + " wraps past the screen edge at row " + row + ", column " + column);
            }
            boolean input = field.attributes().contains(BasicAttribute.UNPROT) && field.name().isPresent();
            boolean dark = field.attributes().contains(BasicAttribute.DRK);
            boolean numeric = field.attributes().contains(BasicAttribute.NUM);
            // 名前の無い非保護 field は RECEIVE MAP の記号マップへ届かない。送らせないよう出力にする
            String parameterName = input
                    ? "bms." + field.name().orElseThrow().toUpperCase(Locale.ROOT) + "." + field.occurrence()
                    : null;
            int labelRow = row;
            int labelColumn = column;
            // DRK の値は出さない。ただし出力 field を空にすると幅が 0 になり、同じ行のあとの field が
            // 左へずれる (spike で BNK1CAM の DUMMY が 0 桁になった)。出力は長さぶんの空白で cell を保つ。
            // 入力 field の幅は bms-len-N が決めるので、値は空でよい
            //
            // 入力 field は、表示のために詰めた末尾の空白を値に出さない。画面の記録は field を
            // 空白で埋めており、そのまま value にすると maxlength と同じ文字数になって 1 文字も
            // 打てない (3270 は上書きなので空白の上に打てるが、HTML の入力欄は挿入である)。
            // ブラウザで測って分かった。server は受けた値を field の桁まで詰め直すので、
            // 送る側が末尾の空白を落としても記号マップは変わらない (BmsInputDecoder)
            String text = dark ? (input ? "" : " ".repeat(field.length()))
                    : input ? field.data().stripTrailing() : field.data();
            segments.add(new BmsScreenView.Segment(parameterName, row, column, field.length(), input, numeric,
                    dark, field.modified(), text, cssClass(field, input),
                    field.name().map(name -> name + (field.occurrence() > 1 ? " " + field.occurrence() : ""))
                            .orElse("row " + labelRow + " column " + labelColumn)));
        }
        int cursorRow = 0;
        int cursorColumn = 0;
        if (snapshot.cursorOffset() >= 0) {
            cursorRow = snapshot.cursorOffset() / snapshot.columns() + 1;
            cursorColumn = snapshot.cursorOffset() % snapshot.columns() + 1;
        }
        return new BmsScreenView(snapshot.mapset(), snapshot.map(), snapshot.rows(), snapshot.columns(),
                cursorRow, cursorColumn, snapshot.alarm(), snapshot.keyboardRestored(), segments,
                lines(segments, snapshot.rows(), snapshot.columns()));
    }

    /** 各行を画面の幅ぶんの空白と field の並びにする。field が重なれば断る。 */
    private static List<BmsScreenView.Line> lines(List<BmsScreenView.Segment> segments, int rows, int columns) {
        List<BmsScreenView.Segment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparingInt(BmsScreenView.Segment::row)
                .thenComparingInt(BmsScreenView.Segment::column));
        List<BmsScreenView.Line> lines = new ArrayList<>();
        int next = 0;
        for (int row = 1; row <= rows; row++) {
            List<BmsScreenView.Item> items = new ArrayList<>();
            int column = 1;
            while (next < ordered.size() && ordered.get(next).row() == row) {
                BmsScreenView.Segment segment = ordered.get(next++);
                if (segment.column() < column) {
                    // 重なった field をずらして描くと、利用者に見える桁と記号マップの field が食い違う
                    throw new IllegalArgumentException("fields overlap at row " + row + ", column " + segment.column());
                }
                if (segment.column() > column) {
                    items.add(new BmsScreenView.Item(" ".repeat(segment.column() - column), null));
                }
                items.add(new BmsScreenView.Item("", segment));
                column = segment.column() + segment.length();
            }
            if (column <= columns) {
                items.add(new BmsScreenView.Item(" ".repeat(columns - column + 1), null));
            }
            lines.add(new BmsScreenView.Line(row, items));
        }
        return lines;
    }

    private static String cssClass(BmsScreenSnapshot.FieldState field, boolean input) {
        StringJoiner out = new StringJoiner(" ");
        out.add("bms-field");
        if (field.attributes().contains(BasicAttribute.ASKIP)) {
            out.add("bms-askip");
        } else {
            out.add(input ? "bms-unprotected" : "bms-protected");
        }
        if (field.attributes().contains(BasicAttribute.NUM)) {
            out.add("bms-numeric");
        }
        if (field.attributes().contains(BasicAttribute.BRT)) {
            out.add("bms-bright");
        } else if (field.attributes().contains(BasicAttribute.DRK)) {
            out.add("bms-dark");
        } else {
            out.add("bms-normal");
        }
        field.color().ifPresent(color -> out.add("bms-color-" + color.name().toLowerCase(Locale.ROOT)));
        field.highlight().ifPresent(highlight -> out.add("bms-hl-" + highlight.name().toLowerCase(Locale.ROOT)));
        if (input) {
            // 入力要素の幅は文字では決まらないので、桁数の class で等幅の cell に揃える
            out.add("bms-len-" + field.length());
        }
        return out.toString();
    }
}
