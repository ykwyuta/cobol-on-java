package dev.cobolonjava.ims.db;

import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.FieldDefinition;
import dev.cobolonjava.ims.dbd.InsertRule;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 1 つの DBD の階層型データベース (メモリの上)。
 *
 * <p><b>階層の順</b>は、セグメント、その子を型ごとに DBD に書いた順、同じ型の兄弟を並びの順、で辿る
 * 先行順である。兄弟の並びは順序フィールドがあればキーのバイトの並び (符号なし) の順、無ければ挿入規則による。
 * 根も同じ規則で並べる。HDAM の根の実機の順はランダマイザが決めるので、ここでは再現しない (P-102、P-153)。
 *
 * <p>このクラスは DL/I の位置も状態コードも持たない。それは PCB の側 ({@code dli} パッケージ) の仕事である。
 */
public final class HierarchicalDatabase {

    private final DatabaseDefinition definition;
    private final List<Segment> roots = new ArrayList<>();
    private UndoLog undoLog;
    /** 前の同期点から変わった根のキー。置き場はこの根だけを書き直す (P-160)。 */
    private final Set<ByteBuffer> changedRootKeys = new HashSet<>();

    public HierarchicalDatabase(DatabaseDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    /**
     * 以後の ISRT / REPL / DLET の取り消しを {@code log} に積む。{@link #restore} (保存したものの読み戻し) は積まない。
     */
    public void attach(UndoLog log) {
        this.undoLog = log;
    }

    /**
     * 前の同期点 ({@link #clearChanges}) から ISRT / REPL / DLET で変わった根のキー。
     * キーを持たない根は空のキーであり、そのデータベースのキーの無い根すべてを指す。
     */
    public Set<ByteBuffer> changedRootKeys() {
        return Set.copyOf(changedRootKeys);
    }

    public boolean changed() {
        return !changedRootKeys.isEmpty();
    }

    /** 同期点で確定したか、戻したあと。 */
    public void clearChanges() {
        changedRootKeys.clear();
    }

    private void changed(Segment segment) {
        Segment root = segment;
        while (root.parent() != null) {
            root = root.parent();
        }
        changedRootKeys.add(ByteBuffer.wrap(root.key()));
    }

    public DatabaseDefinition definition() {
        return definition;
    }

    /** 根を並びの順に返す。 */
    public List<Segment> roots() {
        return Collections.unmodifiableList(roots);
    }

    /** すべてのセグメントを階層の順に返す。 */
    public List<Segment> hierarchicalOrder() {
        List<Segment> out = new ArrayList<>();
        for (Segment at = first(type -> true); at != null; at = next(at, type -> true)) {
            out.add(at);
        }
        return out;
    }

    /** 階層の順で最初のセグメント。 */
    public Segment first(Predicate<SegmentDefinition> sensitive) {
        return roots.isEmpty() || !sensitive.test(definition.root()) ? null : roots.get(0);
    }

    /** 階層の順で次のセグメント。感知しない型は、その部分木ごと飛ばす。 */
    public Segment next(Segment from, Predicate<SegmentDefinition> sensitive) {
        for (SegmentDefinition childType : definition.childrenOf(from.definition())) {
            if (!sensitive.test(childType)) {
                continue;
            }
            List<Segment> twins = from.children(childType.name());
            if (!twins.isEmpty()) {
                return twins.get(0);
            }
        }
        return nextSkippingChildren(from, sensitive);
    }

    /** 部分木を飛ばした、階層の順で次のセグメント。{@code from} はまだデータベースの中になければならない。 */
    public Segment nextSkippingChildren(Segment from, Predicate<SegmentDefinition> sensitive) {
        Segment at = from;
        while (at != null) {
            List<Segment> twins = twinsOf(at);
            int index = indexOf(twins, at);
            if (index < 0) {
                throw new IllegalStateException("segment " + at.definition().name() + " is not in the database");
            }
            if (index + 1 < twins.size()) {
                return twins.get(index + 1);
            }
            Segment parent = at.parent();
            if (parent == null) {
                return null;
            }
            boolean right = false;
            for (SegmentDefinition type : definition.childrenOf(parent.definition())) {
                if (right && sensitive.test(type) && !parent.children(type.name()).isEmpty()) {
                    return parent.children(type.name()).get(0);
                }
                if (type.name().equals(at.definition().name())) {
                    right = true;
                }
            }
            at = parent;
        }
        return null;
    }

    /**
     * セグメントを入れる。
     *
     * @param parent 親。根なら {@code null}
     * @return 入れたセグメント。重ならないキーが重なれば {@code null}
     */
    public Segment insert(Segment parent, SegmentDefinition type, byte[] data) {
        return insert(parent, type, data, null);
    }

    /**
     * セグメントを入れる。
     *
     * @param rule 挿入規則の上書き (SSA の {@code *F} / {@code *L})。{@code null} なら DBD の {@code RULES=}
     * @return 入れたセグメント。重ならないキーが重なれば {@code null}
     */
    public Segment insert(Segment parent, SegmentDefinition type, byte[] data, InsertRule rule) {
        InsertRule effective = rule != null ? rule : type.insertRule();
        List<Segment> twins = twinsFor(parent, type);
        FieldDefinition sequence = type.sequenceField();
        int index;
        if (sequence == null) {
            index = effective == InsertRule.FIRST ? 0 : twins.size();
        } else {
            byte[] key = keyOf(type, data);
            int lower = bound(twins, key, false);
            int upper = bound(twins, key, true);
            if (sequence.unique() && lower < upper) {
                return null;
            }
            // HERE は LAST と同じに置く (暫定判断 P-153)
            index = effective == InsertRule.FIRST ? lower : upper;
        }
        Segment segment = new Segment(type, parent, data);
        twins.add(index, segment);
        changed(segment);
        if (undoLog != null) {
            undoLog.record(() -> {
                twins.remove(indexOf(twins, segment));
                segment.markDeleted();
            });
        }
        return segment;
    }

    /**
     * 保存した並びのまま、兄弟の末尾に置く。
     *
     * <p>挿入規則を当て直すと、FIRST の兄弟の並びが保存したときと逆になる。規則は使わず、
     * キーの順が崩れていないことだけを確かめる。崩れていれば保存したものが壊れている。
     *
     * @throws IllegalStateException キーの順が崩れているか、重ならないキーが重なっているとき
     */
    public Segment restore(Segment parent, SegmentDefinition type, byte[] data) {
        List<Segment> twins = twinsFor(parent, type);
        FieldDefinition sequence = type.sequenceField();
        if (sequence != null && !twins.isEmpty()) {
            int comparison = Arrays.compareUnsigned(twins.get(twins.size() - 1).key(), keyOf(type, data));
            if (comparison > 0 || (comparison == 0 && sequence.unique())) {
                throw new IllegalStateException("segment " + type.name() + " is out of key sequence");
            }
        }
        Segment segment = new Segment(type, parent, data);
        twins.add(segment);
        return segment;
    }

    /**
     * 置き場の同期点で、ほかの領域が確定した根を読み直すために、そのキーの根を部分木ごと外す (P-161)。
     * 取り消しの記録も変更の記録も残さない。同期点で位置を捨てたあとにだけ使う。
     *
     * @return 外した位置。そのキーの根が無ければ、キーの順で入る位置
     */
    public int detachRoots(byte[] key) {
        int lower = bound(roots, key, false);
        int upper = bound(roots, key, true);
        List<Segment> detached = roots.subList(lower, upper);
        detached.forEach(Segment::markDeleted);
        detached.clear();
        return lower;
    }

    /** {@link #detachRoots} で外した位置へ、読み直した根を置く。子は {@link #restore} で続ける。 */
    public Segment attachRoot(int index, SegmentDefinition type, byte[] data) {
        if (!type.root()) {
            throw new IllegalArgumentException("segment " + type.name() + " is not the root");
        }
        Segment segment = new Segment(type, null, data);
        roots.add(index, segment);
        return segment;
    }

    private List<Segment> twinsFor(Segment parent, SegmentDefinition type) {
        if ((parent == null) != type.root()) {
            throw new IllegalArgumentException("segment " + type.name() + " requires "
                    + (type.root() ? "no parent" : "a parent of type " + type.parent()));
        }
        if (parent != null && !parent.definition().name().equals(type.parent())) {
            throw new IllegalArgumentException("the parent of " + type.name() + " is " + type.parent()
                    + ", not " + parent.definition().name());
        }
        return parent == null ? roots : parent.mutableChildren(type.name());
    }

    /** 値を置き換える。順序フィールドを変えないことは呼ぶ側が確かめる。 */
    public void replace(Segment segment, byte[] data) {
        byte[] previous = segment.data();
        segment.replace(data);
        changed(segment);
        if (undoLog != null) {
            undoLog.record(() -> segment.replace(previous));
        }
    }

    /** セグメントとその子孫を消す。 */
    public void delete(Segment segment) {
        List<Segment> twins = twinsOf(segment);
        int index = indexOf(twins, segment);
        if (index < 0) {
            throw new IllegalStateException("segment " + segment.definition().name() + " is not in the database");
        }
        changed(segment);
        twins.remove(index);
        segment.markDeleted();
        if (undoLog != null) {
            undoLog.record(() -> {
                twins.add(index, segment);
                segment.markRestored();
            });
        }
    }

    /** 順序フィールドの値。順序フィールドを持たなければ空。 */
    public static byte[] keyOf(SegmentDefinition type, byte[] data) {
        FieldDefinition sequence = type.sequenceField();
        if (sequence == null) {
            return new byte[0];
        }
        int end = sequence.offset() + sequence.bytes();
        if (end > data.length) {
            throw new IllegalArgumentException("segment " + type.name() + " (" + data.length
                    + " bytes) does not contain its sequence field " + sequence.name());
        }
        return Arrays.copyOfRange(data, sequence.offset(), end);
    }

    private List<Segment> twinsOf(Segment segment) {
        return segment.parent() == null ? roots : segment.parent().mutableChildren(segment.definition().name());
    }

    private static int indexOf(List<Segment> twins, Segment segment) {
        for (int i = 0; i < twins.size(); i++) {
            if (twins.get(i) == segment) {
                return i;
            }
        }
        return -1;
    }

    /** キーより小さい兄弟の数 ({@code orEqual} なら、キー以下の兄弟の数)。 */
    private static int bound(List<Segment> twins, byte[] key, boolean orEqual) {
        int low = 0;
        int high = twins.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            int comparison = Arrays.compareUnsigned(twins.get(middle).key(), key);
            if (comparison < 0 || (orEqual && comparison == 0)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
