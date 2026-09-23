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
    private boolean aligned;
    private boolean blankWhenZero;
    private int occurs = 1;
    private boolean table;
    private String redefinesName;
    private LiteralValue initialValue;
    private DataItem parent;
    private final List<DataItem> children = new ArrayList<>();
    private final List<ConditionName> conditionNames = new ArrayList<>();

    private int offset;
    private int length;
    private int base;
    private DataSection section = DataSection.WORKING_STORAGE;
    private String fileName;
    private final List<String> indexNames = new ArrayList<>();
    private final List<SearchKey> searchKeys = new ArrayList<>();
    private boolean index;
    private boolean readOnly;

    DataItem(int level, String name, Origin origin) {
        this.level = level;
        this.name = name;
        this.origin = origin;
    }

    /** 条件名 (88 レベル)。記憶域を占めず、直前の項目に付く。 */
    public record ConditionName(String name, List<ValueRange> values, Origin origin) {
    }

    /** 条件名の値。{@code THRU} で範囲を書ける。範囲でなければ {@code to} は {@code null}。 */
    public record ValueRange(LiteralValue from, LiteralValue to) {
    }

    /**
     * {@code OCCURS ... ASCENDING / DESCENDING KEY} で書かれた探索の鍵。
     *
     * <p>{@code SEARCH ALL} の 2 分探索は<b>探す向きを知らなければ書けない</b>。
     * 昇順なら鍵が小さいときに後ろ半分を、降順なら前半分を見る。
     *
     * @param ascending 昇順かどうか
     * @param name      鍵になる項目の名前
     */
    public record SearchKey(boolean ascending, String name) {
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

    /**
     * {@code SYNCHRONIZED} が書かれているか (要件 FR-021)。
     *
     * <p>境界に合わせるのは 2 進・浮動小数・指標の項目だけである。表示形式と
     * パック 10 進では書いても割り付けが変わらない (暫定判断 P-111)。
     */
    public boolean aligned() {
        return aligned;
    }

    public boolean blankWhenZero() {
        return blankWhenZero;
    }

    /** 反復の回数。{@code OCCURS} がなければ 1。 */
    public int occurs() {
        return occurs;
    }

    /**
     * {@code OCCURS} 句を持つかどうか。
     *
     * <p>回数が 1 でも添字は要る。したがって {@link #occurs()} が 1 かどうかでは判別できない。
     */
    public boolean isTable() {
        return table;
    }

    /** {@code REDEFINES} で重ねる先の名前。重ねていなければ {@code null}。 */
    public String redefinesName() {
        return redefinesName;
    }

    /** {@code VALUE} 句の初期値。指定がなければ {@code null}。 */
    public LiteralValue initialValue() {
        return initialValue;
    }

    public DataItem parent() {
        return parent;
    }

    public List<DataItem> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * {@code INDEXED BY} で書かれた指標名。表の項目にだけ付く。
     */
    public List<String> indexNames() {
        return Collections.unmodifiableList(indexNames);
    }

    /** {@code OCCURS ... KEY} で書かれた探索の鍵。書かれた順に並ぶ。 */
    public List<SearchKey> searchKeys() {
        return Collections.unmodifiableList(searchKeys);
    }

    /**
     * この項目が指標名の実体かどうか。
     *
     * <p>指標名はデータ項目ではない。書き込めるのは {@code SET} だけであり、
     * {@code MOVE} の受取側にはできない。
     */
    /** {@code USAGE IS INDEX} と書かれたか。群に書けば配下の基本項目すべてに効く。 */
    public boolean indexDeclared() {
        return indexDeclared;
    }

    private boolean indexDeclared;

    /** {@code USAGE POINTER} と書かれたか。番地を持つ項目であり、SET と群の転記でだけ扱う。 */
    private boolean pointer;

    public boolean isPointer() {
        return pointer;
    }

    void markPointer() {
        this.pointer = true;
    }

    public boolean isIndex() {
        return index;
    }

    /** runtimeだけが更新でき、COBOL文の受取側にはできない項目か。 */
    public boolean readOnly() {
        for (DataItem current = this; current != null; current = current.parent) {
            if (current.readOnly) {
                return true;
            }
        }
        return false;
    }

    private boolean external;

    /**
     * {@code EXTERNAL} と書かれたか (要件 FR-014)。
     *
     * <p>書かれた 01 レベルの領域は<b>実行単位で 1 つ</b>である。同じ名前で
     * {@code EXTERNAL} と書いたどのプログラムからも、同じ中身が見える。
     */
    public boolean external() {
        return external;
    }

    void setExternal(boolean value) {
        this.external = value;
    }

    private String globalOwner;

    /**
     * {@code GLOBAL} と書かれた 01 レベルを持つプログラムの名前 (要件 FR-091)。
     *
     * <p>入れ子のプログラムでは、囲む側が {@code GLOBAL} と書いた項目を<b>囲まれた側から
     * 見える</b>。実体は囲む側が持つので、名前だけでは足りず<b>誰のものか</b>まで要る。
     * 別のプログラムが同じ名前の {@code GLOBAL} 項目を持っていても、別の領域である。
     *
     * @return {@code GLOBAL} でなければ {@code null}
     */
    public String globalOwner() {
        return globalOwner;
    }

    void setGlobalOwner(String value) {
        this.globalOwner = value;
    }

    public List<ConditionName> conditionNames() {
        return Collections.unmodifiableList(conditionNames);
    }

    /** 所属する 01 レベルの先頭からのバイト位置 (0 起点)。 */
    public int offset() {
        return offset;
    }

    /**
     * この項目が属する 01 レベルが、プログラムの記憶域上のどこから始まるか。
     * 01 レベルと独立項目以外では 0 である。
     *
     * <p>連絡節の項目では意味を持たない。記憶域の位置は<b>実行時に渡される</b>ためである。
     */
    public int base() {
        return base;
    }

    /**
     * この項目が書かれた節。01 レベルに設定され、配下の項目は根のものを引き継ぐ。
     */
    public DataSection section() {
        return record().sectionOfRecord();
    }

    private DataSection sectionOfRecord() {
        return section;
    }

    /**
     * この項目が属する {@code FD} のファイル名。{@code FILE SECTION} 以外では {@code null}。
     *
     * <p>{@code WRITE} に書くのは<b>レコード名</b>であってファイル名ではない。どのファイルへ
     * 書くのかは、レコードがどの {@code FD} の下にあるかで決まる。
     */
    public String fileName() {
        return record().fileNameOfRecord();
    }

    private String fileNameOfRecord() {
        return fileName;
    }

    /** この項目が属する 01 レベル (または独立項目)。 */
    public DataItem record() {
        DataItem current = this;
        while (current.parent() != null) {
            current = current.parent();
        }
        return current;
    }

    /** 1 回分のバイト長。{@code OCCURS} は含まない。 */
    public int length() {
        return length;
    }

    /** 反復を含めたバイト長。 */
    public int totalLength() {
        return length * occurs;
    }

    /**
     * 66 レベルの別名かどうか (要件 FR-021)。
     *
     * <p>別名は<b>記憶域を持たない</b>。すでにある記述の上に名前を重ねているだけなので、
     * 初期値を書くときに通ってはならない。通すと、名前を付けた先の初期値を消してしまう。
     */
    public boolean isAlias() {
        return alias;
    }

    private boolean alias;

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

    void setAligned(boolean value) {
        this.aligned = value;
    }

    void setBlankWhenZero(boolean value) {
        this.blankWhenZero = value;
    }

    /**
     * {@code OCCURS ... DEPENDING ON} に書かれた項目の名前。書かれていなければ {@code null}。
     *
     * <p>記憶域は<b>最大の回数</b>で取る。実行時に変わるのは「いま何個あるか」だけで
     * あり、割り付けそのものは動かない。{@code SEARCH} が端まで走る回数と、
     * この表を含む群の長さが、この項目の値で決まる。
     */
    private String occursDependingName;

    public String occursDependingName() {
        return occursDependingName;
    }

    void setOccursDependingName(String value) {
        this.occursDependingName = value;
    }

    /**
     * {@code OCCURS ... DEPENDING ON} に書かれた項目そのもの。名前を引き当てて結び付ける。
     *
     * <p>名前だけでは<b>群の長さを実行時に数えられない</b>。数えるのは翻訳の後ろの段
     * (符号生成) であり、そこには名前を引く道具が無い。データ部を読み終えた時点で
     * 引き当てておく。引き当てられない名前はここでは<b>黙って残す</b>。誤りとして
     * 報せるのは手続き部を読む側の役目であり、二重に言うと診断が散る。
     */
    private DataItem occursDepending;

    public DataItem occursDepending() {
        return occursDepending;
    }

    void setOccursDepending(DataItem value) {
        this.occursDepending = value;
    }

    void setOccurs(int value) {
        this.occurs = value;
        this.table = true;
    }

    void setRedefinesName(String value) {
        this.redefinesName = value;
    }

    void setInitialValue(LiteralValue value) {
        this.initialValue = value;
    }

    void markAlias() {
        this.alias = true;
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

    void addIndexName(String value) {
        indexNames.add(value);
    }

    void addSearchKey(SearchKey value) {
        searchKeys.add(value);
    }

    void markIndexDeclared() {
        this.indexDeclared = true;
    }

    void markIndex() {
        this.index = true;
    }

    void markReadOnly() {
        this.readOnly = true;
    }

    void setSection(DataSection value) {
        this.section = value;
    }

    void setFileName(String value) {
        this.fileName = value;
    }

    /**
     * {@code LINAGE-COUNTER} なら、それを持つファイルの名前。{@code LINAGE-COUNTER OF ファイル名} の
     * 修飾はデータ項目の親子ではなく、この名前で合わせる (85 規格 VI-2.4.4)。
     */
    private String linageFile;

    public String linageFile() {
        return linageFile;
    }

    void setLinageFile(String value) {
        this.linageFile = value;
    }

    void setBase(int value) {
        this.base = value;
    }

    @Override
    public String toString() {
        return String.format("%02d %s offset=%d length=%d%s",
                level, name == null ? "FILLER" : name, offset, length,
                occurs > 1 ? " occurs=" + occurs : "");
    }
}
