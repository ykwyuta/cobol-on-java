package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.picture.Picture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * データ記述項 1 個に対応する項目 (要件 FR-020, FR-030, FR-031)。
 *
 * <p>記憶域の割り付け、すなわち<b>どのバイトがどの項目か</b>を持つ。
 * 大きさの計算はランタイムの {@link Picture} と {@link Usage} にそのまま任せる。
 * コンパイラが独自に桁数からバイト数を計算すると、ランタイムとずれる余地ができる。
 *
 * <p>{@link #offset()} は所属する 01 レベルの先頭からの相対位置である。
 * {@code REDEFINES} で重ねた項目は、重ねた先と同じ位置を指す。
 */
public final class DataItem {

    private final int level;
    private final String name;
    private final Origin origin;

    private Picture picture;
    private Usage usage;
    private SignPosition signPosition = SignPosition.UNSIGNED;
    private boolean justified;
    private boolean blankWhenZero;
    private int occurs = 1;
    private String redefinesName;
    private DataItem parent;
    private final List<DataItem> children = new ArrayList<>();
    private final List<ConditionName> conditionNames = new ArrayList<>();

    private int offset;
    private int length;

    DataItem(int level, String name, Origin origin) {
        this.level = level;
        this.name = name;
        this.origin = origin;
    }

    /** 条件名 (88 レベル)。記憶域を占めず、直前の項目に付く。 */
    public record ConditionName(String name, List<ValueRange> values, Origin origin) {
    }

    /** 条件名の値。{@code THRU} で範囲を書ける。範囲でなければ {@code to} は {@code null}。 */
    public record ValueRange(String from, String to) {
    }

    public int level() {
        return level;
    }

    /** 項目名。{@code FILLER} と名前なしは {@code null} になる。 */
    public String name() {
        return name;
    }

    public Origin origin() {
        return origin;
    }

    public Picture picture() {
        return picture;
    }

    public Usage usage() {
        return usage;
    }

    public SignPosition signPosition() {
        return signPosition;
    }

    public boolean justified() {
        return justified;
    }

    public boolean blankWhenZero() {
        return blankWhenZero;
    }

    /** 反復の回数。{@code OCCURS} がなければ 1。 */
    public int occurs() {
        return occurs;
    }

    /** {@code REDEFINES} で重ねる先の名前。重ねていなければ {@code null}。 */
    public String redefinesName() {
        return redefinesName;
    }

    public DataItem parent() {
        return parent;
    }

    public List<DataItem> children() {
        return Collections.unmodifiableList(children);
    }

    public List<ConditionName> conditionNames() {
        return Collections.unmodifiableList(conditionNames);
    }

    /** 所属する 01 レベルの先頭からのバイト位置 (0 起点)。 */
    public int offset() {
        return offset;
    }

    /** 1 回分のバイト長。{@code OCCURS} は含まない。 */
    public int length() {
        return length;
    }

    /** 反復を含めたバイト長。 */
    public int totalLength() {
        return length * occurs;
    }

    /** 下位の項目を持たない項目かどうか。 */
    public boolean isElementary() {
        return children.isEmpty();
    }

    // ---- 組み立て。DataDivisionBuilder だけが使う ----

    void setPicture(Picture value) {
        this.picture = value;
    }

    void setUsage(Usage value) {
        this.usage = value;
    }

    void setSignPosition(SignPosition value) {
        this.signPosition = value;
    }

    void setJustified(boolean value) {
        this.justified = value;
    }

    void setBlankWhenZero(boolean value) {
        this.blankWhenZero = value;
    }

    void setOccurs(int value) {
        this.occurs = value;
    }

    void setRedefinesName(String value) {
        this.redefinesName = value;
    }

    void addChild(DataItem child) {
        child.parent = this;
        children.add(child);
    }

    void addConditionName(ConditionName value) {
        conditionNames.add(value);
    }

    void setOffset(int value) {
        this.offset = value;
    }

    void setLength(int value) {
        this.length = value;
    }

    @Override
    public String toString() {
        return String.format("%02d %s offset=%d length=%d%s",
                level, name == null ? "FILLER" : name, offset, length,
                occurs > 1 ? " occurs=" + occurs : "");
    }
}
