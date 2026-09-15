package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.FieldDefinition;
import dev.cobolonjava.ims.dbd.FieldType;
import dev.cobolonjava.ims.dbd.InsertRule;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import dev.cobolonjava.ims.psb.PcbDefinition;
import dev.cobolonjava.ims.psb.SensitiveSegment;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * DB PCB 1 つの実行時の状態と、DL/I の DB 呼び出し (設計 78 §3)。
 *
 * <h2>PCB マスク</h2>
 * <pre>
 *  0  DBD 名 (8)          8  セグメントの段 (2)    10 状態コード (2)
 * 12  PROCOPT (4)        16  予約 (4)              20 セグメント名の帰還 (8)
 * 28  キー帰還の長さ (4)  32  感知するセグメント数 (4)  36 キー帰還域 (KEYLEN)
 * </pre>
 *
 * <h2>位置</h2>
 * <p>位置は「最後に取り出したか入れたセグメント」である。DLET のあとは「消した部分木の次の手前」になり、
 * 次の GN はそのセグメントから始まる。親境界 (parentage) は GU / GN (Hold を含む) が成功したときだけ決まり、
 * GNP はそれを変えない。状態コードの発生条件と位置の決まり方は、公開仕様の説明から起こしたもので
 * 実機と突き合わせていない (暫定判断 P-154)。
 */
final class DatabasePcb {

    static final int LEVEL = 8;
    static final int STATUS = 10;
    static final int OPTIONS = 12;
    static final int SEGMENT_NAME = 20;
    static final int KEY_LENGTH = 28;
    static final int SENSITIVE_COUNT = 32;
    static final int KEY_FEEDBACK = 36;

    private final PcbDefinition.Database definition;
    private final HierarchicalDatabase database;
    private final Storage mask;
    private final CodePage codePage;
    private final BiConsumer<HierarchicalDatabase, Segment> deleter;
    /** 感知するセグメント名と、その PROCOPT (SENSEG に書かなければ PCB のもの)。 */
    private final Map<String, String> options = new HashMap<>();
    private final Predicate<SegmentDefinition> sensitive;

    /** 位置。{@code null} で {@link #atEnd} が偽なら、データベースの先頭の前。 */
    private Segment current;
    /** 真なら {@link #current} の手前にいる (DLET のあと)。 */
    private boolean before;
    private boolean atEnd;
    private Segment parentage;
    private Segment held;
    /** Hold した道 ({@code *D} で取り出した上の段と、取り出したセグメント)。上から順に並ぶ。 */
    private List<Segment> heldPath = List.of();
    private int lastLevel;
    private String lastType;

    DatabasePcb(PcbDefinition.Database definition, HierarchicalDatabase database, Storage mask, CodePage codePage,
                BiConsumer<HierarchicalDatabase, Segment> deleter) {
        this.definition = definition;
        this.database = database;
        this.mask = mask;
        this.codePage = codePage;
        this.deleter = deleter;
        for (SensitiveSegment segment : definition.segments()) {
            options.put(segment.name(), segment.processingOptions() == null
                    ? definition.processingOptions() : segment.processingOptions());
        }
        this.sensitive = type -> options.containsKey(type.name());
        initializeMask();
    }

    HierarchicalDatabase database() {
        return database;
    }

    /** マスクの初期値。段は "00"、状態は空白、キー帰還域は空白で置く (P-154)。 */
    private void initializeMask() {
        mask.whole().fill((byte) 0);
        text(0, 8, definition.dbdName());
        text(LEVEL, 2, "00");
        text(STATUS, 2, "");
        text(OPTIONS, 4, definition.processingOptions());
        text(SEGMENT_NAME, 8, "");
        integer(KEY_LENGTH, 0);
        integer(SENSITIVE_COUNT, definition.segments().size());
        text(KEY_FEEDBACK, definition.keyLength(), "");
    }

    /** 呼び出しを動かし、状態コードを PCB に書いて返す。 */
    String call(String function, DataView io, List<DataView> ssaViews) {
        // Hold は次の REPL / DLET までしか続かない。間に別の呼び出しがあれば失われる
        if (!function.equals("REPL") && !function.equals("DLET")) {
            held = null;
            heldPath = List.of();
        }
        String status;
        try {
            status = switch (function) {
                case "GU" -> getUnique(required(io, function), parse(ssaViews), false);
                case "GHU" -> getUnique(required(io, function), parse(ssaViews), true);
                case "GN" -> getNext(required(io, function), parse(ssaViews), false);
                case "GHN" -> getNext(required(io, function), parse(ssaViews), true);
                case "GNP" -> getNextWithinParent(required(io, function), parse(ssaViews), false);
                case "GHNP" -> getNextWithinParent(required(io, function), parse(ssaViews), true);
                case "ISRT" -> insert(required(io, function), parse(ssaViews));
                case "REPL" -> replace(required(io, function), parse(ssaViews));
                case "DLET" -> delete(parse(ssaViews));
                default -> StatusCode.AD;
            };
        } catch (DliStatusException e) {
            status = e.status();
        }
        text(STATUS, 2, status);
        return status;
    }

    // ---- 取り出し ----

    private String getUnique(DataView io, List<SegmentSearchArgument> ssas, boolean hold) {
        SegmentDefinition target = ssas.isEmpty() ? database.definition().root() : last(ssas).segment();
        if (!allows('G', target) || !pathAllowed(ssas)) {
            return StatusCode.AM;
        }
        Search found = search(database.first(sensitive), target, ssas, null, Map.of());
        if (found.segment() == null) {
            parentage = null;
            notFound(null);
            return StatusCode.GE;
        }
        return retrieved(found.segment(), io, hold, true, false, ssas);
    }

    private String getNext(DataView io, List<SegmentSearchArgument> ssas, boolean hold) {
        if (!ssas.isEmpty() && (!allows('G', last(ssas).segment()) || !pathAllowed(ssas))) {
            return StatusCode.AM;
        }
        Segment start;
        Map<Integer, Segment> fixed = Map.of();
        SegmentSearchArgument first = firstWith(ssas, 'F');
        if (first != null) {
            // *F: その段の親の下の最初の出現へ戻る。親より上の段は、SSA が無ければ位置のまま (P-159)
            int level = first.segment().level();
            Segment anchor = level == 1 || current == null ? null : current.path().size() >= level - 1
                    ? current.path().get(level - 2) : null;
            if (anchor == null) {
                start = database.first(sensitive);
            } else {
                start = database.next(anchor, sensitive);
                fixed = positionFixed(anchor, ssas);
            }
        } else {
            if (atEnd) {
                notFound(null);
                return StatusCode.GB;
            }
            start = current == null ? database.first(sensitive)
                    : before ? current : database.next(current, sensitive);
        }
        if (ssas.isEmpty()) {
            if (start == null) {
                return reachedEnd();
            }
            if (!allows('G', start.definition())) {
                return StatusCode.AM;
            }
            return retrieved(start, io, hold, true, true, ssas);
        }
        Search found = search(start, last(ssas).segment(), ssas, null, fixed);
        if (found.segment() != null) {
            return retrieved(found.segment(), io, hold, true, false, ssas);
        }
        if (found.bounded()) {
            notFound(null);
            return StatusCode.GE;
        }
        return reachedEnd();
    }

    private String reachedEnd() {
        atEnd = true;
        current = null;
        before = false;
        parentage = null;
        notFound(null);
        return StatusCode.GB;
    }

    private String getNextWithinParent(DataView io, List<SegmentSearchArgument> ssas, boolean hold) {
        if (parentage == null) {
            return StatusCode.GP;
        }
        if (!ssas.isEmpty() && (!allows('G', last(ssas).segment()) || !pathAllowed(ssas))) {
            return StatusCode.AM;
        }
        Segment start;
        if (firstWith(ssas, 'F') != null) {
            // *F: 親境界の下の最初から探し直す
            start = database.next(parentage, sensitive);
        } else if (atEnd) {
            start = null;
        } else if (current != null && current.isWithin(parentage)) {
            start = before ? current : database.next(current, sensitive);
        } else if (current != null && before) {
            // 親の最後の子孫を消したあと。手前にいるのは親の外なので、下で GE になる
            start = current;
        } else {
            start = database.next(parentage, sensitive);
        }
        if (start == null || start == parentage || !start.isWithin(parentage)) {
            feedback(parentage);
            return StatusCode.GE;
        }
        if (ssas.isEmpty()) {
            if (!allows('G', start.definition())) {
                return StatusCode.AM;
            }
            return retrieved(start, io, hold, false, true, ssas);
        }
        Search found = search(start, last(ssas).segment(), ssas, parentage, Map.of());
        if (found.segment() == null) {
            feedback(parentage);
            return StatusCode.GE;
        }
        return retrieved(found.segment(), io, hold, false, false, ssas);
    }

    /**
     * 取り出したセグメントを I/O 域へ置き、位置と帰還域を決める。
     *
     * <p>{@code *D} を付けた上の段のセグメントを、上から順に取り出したセグメントの前に並べる。{@code *P} を付けた段が
     * あれば、親境界は (取り出したセグメントではなく) その段に置く。
     */
    private String retrieved(Segment segment, DataView io, boolean hold, boolean setsParentage,
                             boolean unqualified, List<SegmentSearchArgument> ssas) {
        List<Segment> path = new ArrayList<>();
        Segment parentageAt = null;
        for (Segment node : segment.path()) {
            SegmentSearchArgument ssa = ssaFor(ssas, node.definition());
            if (ssa != null && ssa.has('P')) {
                parentageAt = node;
            }
            if (node != segment && ssa != null && ssa.has('D')) {
                path.add(node);
            }
        }
        path.add(segment);
        int total = 0;
        for (Segment node : path) {
            total += node.data().length;
        }
        if (io.length() < total) {
            throw new DliCallException("the I/O area (" + io.length() + " bytes) is shorter than segment "
                    + segment.definition().name() + (path.size() > 1 ? " and its path" : "") + " (" + total
                    + " bytes)");
        }
        int offset = 0;
        for (Segment node : path) {
            byte[] data = node.data();
            io.subView(offset, data.length).setBytes(data);
            offset += data.length;
        }
        String status = StatusCode.OK;
        if (unqualified && lastType != null) {
            if (segment.level() < lastLevel) {
                status = StatusCode.GA;
            } else if (segment.level() == lastLevel && !segment.definition().name().equals(lastType)) {
                status = StatusCode.GK;
            }
        }
        moveTo(segment);
        if (setsParentage) {
            parentage = parentageAt != null ? parentageAt : segment;
        }
        held = hold ? segment : null;
        heldPath = hold ? List.copyOf(path) : List.of();
        feedback(segment);
        return status;
    }

    // ---- 更新 ----

    /**
     * ISRT。{@code *D} を付けた段から最後の段までを、I/O 域に上から順に並べたセグメントとしてまとめて入れる。
     * {@code *F} / {@code *L} はその段の挿入規則に勝つ。
     */
    private String insert(DataView io, List<SegmentSearchArgument> ssas) {
        if (ssas.isEmpty()) {
            return StatusCode.AJ;
        }
        int pathStart = ssas.size() - 1;
        for (int i = 0; i < ssas.size(); i++) {
            if (ssas.get(i).has('D')) {
                pathStart = i;
                break;
            }
        }
        List<SegmentSearchArgument> inserted = ssas.subList(pathStart, ssas.size());
        for (int i = 0; i < inserted.size(); i++) {
            SegmentSearchArgument ssa = inserted.get(i);
            if (!allows('I', ssa.segment())) {
                return StatusCode.AM;
            }
            // 入れるセグメントの SSA は無限定で、道は 1 段ずつ下る
            if (ssa.qualified() || (i > 0 && !ssa.segment().parent().equals(inserted.get(i - 1).segment().name()))) {
                return StatusCode.AJ;
            }
        }
        List<byte[]> values = new ArrayList<>();
        int offset = 0;
        for (SegmentSearchArgument ssa : inserted) {
            byte[] value = segmentData(ssa.segment(), io, offset);
            values.add(value);
            offset += value.length;
        }
        SegmentDefinition type = inserted.get(0).segment();
        byte[] data = values.get(0);
        boolean load = loadMode();
        Segment parent = null;
        if (!type.root()) {
            parent = parentFor(type, ssas.subList(0, pathStart));
            if (parent == null) {
                notFound(null);
                return load ? StatusCode.LD : StatusCode.GE;
            }
        } else if (load && database.definition().access().keyOrderedRoots() && !database.roots().isEmpty()) {
            byte[] lastKey = database.roots().get(database.roots().size() - 1).key();
            if (Arrays.compareUnsigned(HierarchicalDatabase.keyOf(type, data), lastKey) < 0) {
                return StatusCode.LC;
            }
        }
        Segment at = database.insert(parent, type, data, placement(inserted.get(0)));
        if (at == null) {
            return load ? StatusCode.LB : StatusCode.II;
        }
        // 下の段は入れたばかりの親の下に入るので、キーが重なることは無い
        for (int i = 1; i < inserted.size(); i++) {
            at = database.insert(at, inserted.get(i).segment(), values.get(i), placement(inserted.get(i)));
        }
        moveTo(at);
        feedback(at);
        return StatusCode.OK;
    }

    private static InsertRule placement(SegmentSearchArgument ssa) {
        return ssa.has('F') ? InsertRule.FIRST : ssa.has('L') ? InsertRule.LAST : null;
    }

    /**
     * ISRT の親。SSA を書いた段は SSA で、書かなかった段は現在位置のその段のセグメントで決める。
     * 位置がその段に無ければ、その段は無限定とする。
     */
    private Segment parentFor(SegmentDefinition type, List<SegmentSearchArgument> parents) {
        SegmentDefinition parentType = database.definition().segment(type.parent());
        Map<Integer, Segment> fixed = new HashMap<>();
        if (current != null && !atEnd) {
            for (Segment node : current.path()) {
                if (node.level() <= parentType.level() && onPath(node.definition(), parentType)
                        && ssaFor(parents, node.definition()) == null) {
                    fixed.put(node.level(), node);
                }
            }
        }
        return search(database.first(sensitive), parentType, parents, null, fixed).segment();
    }

    /**
     * REPL。Hold した道 ({@code *D} で取り出した段を含む) を、I/O 域に上から順に並べた値で置き換える。
     * SSA は {@code *N} を付けた無限定のものだけを書け、その段は置き換えない。すべての段を確かめてから置き換える。
     */
    private String replace(DataView io, List<SegmentSearchArgument> ssas) {
        if (held == null) {
            return StatusCode.DJ;
        }
        List<Segment> path = heldPath.isEmpty() ? List.of(held) : heldPath;
        for (SegmentSearchArgument ssa : ssas) {
            if (ssa.qualified() || !ssa.commandCodes().equals("N")
                    || path.stream().noneMatch(node -> node.definition().name().equals(ssa.segment().name()))) {
                return StatusCode.AJ;
            }
        }
        List<byte[]> values = new ArrayList<>();
        int offset = 0;
        for (Segment node : path) {
            SegmentDefinition type = node.definition();
            byte[] value = segmentData(type, io, offset);
            offset += value.length;
            values.add(ssaFor(ssas, type) == null ? value : null);
            if (ssaFor(ssas, type) != null) {
                continue;
            }
            if (!allows('R', type)) {
                return StatusCode.AM;
            }
            FieldDefinition sequence = type.sequenceField();
            if (sequence != null && !Arrays.equals(HierarchicalDatabase.keyOf(type, value), node.key())) {
                return StatusCode.DA;
            }
        }
        for (int i = 0; i < path.size(); i++) {
            if (values.get(i) != null) {
                database.replace(path.get(i), values.get(i));
            }
        }
        return StatusCode.OK;
    }

    private String delete(List<SegmentSearchArgument> ssas) {
        if (!ssas.isEmpty()) {
            return StatusCode.AJ;
        }
        if (held == null) {
            return StatusCode.DJ;
        }
        if (!allows('D', held.definition())) {
            return StatusCode.AM;
        }
        deleter.accept(database, held);
        return StatusCode.OK;
    }

    /**
     * 同期点 (I/O PCB への GU、CHKP、SYNC、ROLB) で位置を捨てる (設計 78 §3.5、暫定判断 P-157)。
     * 次の GN はデータベースの先頭から始まり、GNP は親境界が無いので GP になる。
     */
    void resetPosition() {
        current = null;
        before = false;
        atEnd = false;
        parentage = null;
        held = null;
        lastLevel = 0;
        lastType = null;
    }

    /** 同じデータベースのセグメントが消される前に、位置と親境界と Hold を部分木の外へ移す。 */
    void beforeDelete(Segment deleted) {
        if (current != null && current.isWithin(deleted)) {
            Segment successor = database.nextSkippingChildren(deleted, sensitive);
            if (successor == null) {
                current = null;
                before = false;
                atEnd = true;
            } else {
                current = successor;
                before = true;
            }
        }
        if (parentage != null && parentage.isWithin(deleted)) {
            parentage = null;
        }
        if (held != null && held.isWithin(deleted)) {
            held = null;
        }
    }

    // ---- 探す ----

    private record Search(Segment segment, boolean bounded) {
    }

    /**
     * {@code start} から階層の順に、{@code target} の型で、道の上のすべての SSA を満たすセグメントを探す。
     *
     * <p>道に乗らない型と、SSA を満たさない祖先は部分木ごと飛ばす。{@code within} があれば、その部分木を
     * 出たところで止める。{@code fixed} は段ごとに決まったセグメントである (ISRT の親を位置で決める段)。
     */
    private Search search(Segment start, SegmentDefinition target, List<SegmentSearchArgument> ssas,
                          Segment within, Map<Integer, Segment> fixed) {
        RootBound bound = rootBound(ssas);
        Segment at = start;
        while (at != null) {
            if (within != null && !at.isWithin(within)) {
                return new Search(null, false);
            }
            if (at.level() == 1 && bound != null && bound.passed(at.key())) {
                return new Search(null, true);
            }
            SegmentDefinition type = at.definition();
            if (!onPath(type, target) || !levelMatches(at, ssas, fixed)) {
                at = database.nextSkippingChildren(at, sensitive);
                continue;
            }
            if (type.name().equals(target.name())) {
                if (pathMatches(at, ssas, fixed)) {
                    return new Search(at, false);
                }
                at = database.nextSkippingChildren(at, sensitive);
                continue;
            }
            at = database.next(at, sensitive);
        }
        return new Search(null, false);
    }

    /**
     * 根がキーの順に並ぶと実機でも決まっている方式 (HIDAM 等) で、根の順序フィールドに上限の修飾があれば、
     * その上限。上限を越えた根まで来たら探すのをやめて GE にする。HDAM は実機でもデータベースの終わりまで
     * 探す (GB) ので上限を持たない。バイトの並びと数の順が一致する C / X の型だけで使う。
     */
    private RootBound rootBound(List<SegmentSearchArgument> ssas) {
        if (ssas.isEmpty() || !database.definition().access().keyOrderedRoots()) {
            return null;
        }
        SegmentSearchArgument first = ssas.get(0);
        FieldDefinition sequence = first.segment().sequenceField();
        if (!first.segment().root() || first.alternatives().size() != 1 || sequence == null
                || (sequence.type() != FieldType.C && sequence.type() != FieldType.X)) {
            return null;
        }
        RootBound best = null;
        for (SegmentSearchArgument.Qualification qualification : first.alternatives().get(0)) {
            if (!qualification.field().name().equals(sequence.name())) {
                continue;
            }
            RootBound candidate = switch (qualification.operator()) {
                case EQ, LE -> new RootBound(qualification.value(), true);
                case LT -> new RootBound(qualification.value(), false);
                default -> null;
            };
            if (candidate != null && (best == null || candidate.tighterThan(best))) {
                best = candidate;
            }
        }
        return best;
    }

    private record RootBound(byte[] value, boolean inclusive) {

        boolean passed(byte[] key) {
            int comparison = Arrays.compareUnsigned(key, value);
            return inclusive ? comparison > 0 : comparison >= 0;
        }

        boolean tighterThan(RootBound other) {
            int comparison = Arrays.compareUnsigned(value, other.value);
            return comparison < 0 || (comparison == 0 && !inclusive);
        }
    }

    private boolean pathMatches(Segment segment, List<SegmentSearchArgument> ssas,
                                Map<Integer, Segment> fixed) {
        for (Segment node : segment.path()) {
            if (!levelMatches(node, ssas, fixed)) {
                return false;
            }
        }
        return true;
    }

    private boolean levelMatches(Segment segment, List<SegmentSearchArgument> ssas, Map<Integer, Segment> fixed) {
        SegmentSearchArgument ssa = ssaFor(ssas, segment.definition());
        if (ssa != null) {
            if (!satisfies(segment, ssa)) {
                return false;
            }
            // *L: 同じ親の下で、あとに修飾を満たす兄弟があれば、これは最後の出現ではない
            if (ssa.has('L')) {
                List<Segment> twins = segment.parent() == null
                        ? database.roots() : segment.parent().children(segment.definition().name());
                boolean seen = false;
                for (Segment twin : twins) {
                    if (seen && satisfies(twin, ssa)) {
                        return false;
                    }
                    seen |= twin == segment;
                }
            }
        }
        Segment required = fixed.get(segment.level());
        return required == null || required == segment;
    }

    /** 修飾と、{@code *C} の連結キーを満たすか。 */
    private static boolean satisfies(Segment segment, SegmentSearchArgument ssa) {
        return ssa.matches(segment.data())
                && (ssa.concatenatedKey() == null || Arrays.equals(concatenatedKey(segment), ssa.concatenatedKey()));
    }

    private static byte[] concatenatedKey(Segment segment) {
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        for (Segment node : segment.path()) {
            key.writeBytes(node.key());
        }
        return key.toByteArray();
    }

    private static SegmentSearchArgument firstWith(List<SegmentSearchArgument> ssas, char commandCode) {
        for (SegmentSearchArgument ssa : ssas) {
            if (ssa.has(commandCode)) {
                return ssa;
            }
        }
        return null;
    }

    /** 道の取り出し ({@code *D}) は、その段の PROCOPT に P が要る。 */
    private boolean pathAllowed(List<SegmentSearchArgument> ssas) {
        for (SegmentSearchArgument ssa : ssas) {
            if (ssa.has('D') && options.get(ssa.segment().name()).indexOf('P') < 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code anchor} の道のうち、SSA を書いていない段を位置のセグメントに固める。 */
    private static Map<Integer, Segment> positionFixed(Segment anchor, List<SegmentSearchArgument> ssas) {
        Map<Integer, Segment> fixed = new HashMap<>();
        for (Segment node : anchor.path()) {
            if (ssaFor(ssas, node.definition()) == null) {
                fixed.put(node.level(), node);
            }
        }
        return fixed;
    }

    private static SegmentSearchArgument ssaFor(List<SegmentSearchArgument> ssas, SegmentDefinition type) {
        for (SegmentSearchArgument ssa : ssas) {
            if (ssa.segment().name().equals(type.name())) {
                return ssa;
            }
        }
        return null;
    }

    /** {@code type} が {@code target} 自身か、その祖先の型か。 */
    private boolean onPath(SegmentDefinition type, SegmentDefinition target) {
        for (SegmentDefinition at = target; at != null;
             at = at.parent() == null ? null : database.definition().segment(at.parent())) {
            if (at.name().equals(type.name())) {
                return true;
            }
        }
        return false;
    }

    // ---- 共通 ----

    private List<SegmentSearchArgument> parse(List<DataView> ssaViews) {
        List<SegmentSearchArgument> out = new ArrayList<>();
        for (DataView view : ssaViews) {
            SegmentSearchArgument ssa = SsaParser.parse(view, database.definition(), options.keySet(), codePage);
            // SSA は上の段から下の段へ、同じ道の上に並べる
            if (!out.isEmpty()) {
                SegmentDefinition previous = last(out).segment();
                if (ssa.segment().level() <= previous.level() || !onPath(previous, ssa.segment())) {
                    throw new DliStatusException(StatusCode.AJ);
                }
            }
            out.add(ssa);
        }
        return out;
    }

    /**
     * PROCOPT が呼び出しを許すか。R と D は G を含む。L (読み込み) は ISRT だけを許す。
     * I だけで G を含むかは突き合わせていないので、含まないとする (P-154)。
     */
    private boolean allows(char call, SegmentDefinition segment) {
        String granted = options.get(segment.name());
        boolean load = granted.indexOf('L') >= 0;
        return switch (call) {
            case 'G' -> !load && containsAny(granted, "GARD");
            case 'I' -> load || containsAny(granted, "IA");
            case 'R' -> !load && containsAny(granted, "RA");
            case 'D' -> !load && containsAny(granted, "DA");
            default -> false;
        };
    }

    private boolean loadMode() {
        return definition.processingOptions().indexOf('L') >= 0;
    }

    private static boolean containsAny(String text, String letters) {
        for (int i = 0; i < letters.length(); i++) {
            if (text.indexOf(letters.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** I/O 域の {@code offset} からセグメントの値を取る。道の呼び出しでは上の段から順に並ぶ。 */
    private static byte[] segmentData(SegmentDefinition type, DataView io, int offset) {
        if (offset == 0) {
            return segmentData(type, io);
        }
        if (offset >= io.length()) {
            throw new DliCallException("the I/O area (" + io.length() + " bytes) ends before segment " + type.name());
        }
        return segmentData(type, io.subView(offset, io.length() - offset));
    }

    /** I/O 域からセグメントの値を取る。可変長なら先頭の LL が長さである。 */
    private static byte[] segmentData(SegmentDefinition type, DataView io) {
        if (type.variableLength()) {
            if (io.length() < 2) {
                throw new DliCallException("the I/O area is too short for the length of segment " + type.name());
            }
            int length = ((io.get(0) & 0xFF) << 8) | (io.get(1) & 0xFF);
            if (length < type.minBytes() || length > type.maxBytes()) {
                throw new DliCallException("the length " + length + " of variable-length segment " + type.name()
                        + " is outside BYTES=(" + type.maxBytes() + "," + type.minBytes() + ")");
            }
            if (io.length() < length) {
                throw new DliCallException("the I/O area (" + io.length() + " bytes) is shorter than the length "
                        + length + " of segment " + type.name());
            }
            return io.subView(0, length).toByteArray();
        }
        if (io.length() < type.maxBytes()) {
            throw new DliCallException("the I/O area (" + io.length() + " bytes) is shorter than segment "
                    + type.name() + " (" + type.maxBytes() + " bytes)");
        }
        return io.subView(0, type.maxBytes()).toByteArray();
    }

    private static DataView required(DataView io, String function) {
        if (io == null) {
            throw new DliCallException(function + " requires an I/O area");
        }
        return io;
    }

    private static SegmentSearchArgument last(List<SegmentSearchArgument> ssas) {
        return ssas.get(ssas.size() - 1);
    }

    private void moveTo(Segment segment) {
        current = segment;
        before = false;
        atEnd = false;
        lastLevel = segment.level();
        lastType = segment.definition().name();
    }

    /** 段、セグメント名、連結キーを帰還域へ書く。キー帰還域の残りは書き換えない (P-154)。 */
    private void feedback(Segment segment) {
        text(LEVEL, 2, String.format("%02d", segment.level()));
        text(SEGMENT_NAME, 8, segment.definition().name());
        byte[] concatenated = concatenatedKey(segment);
        integer(KEY_LENGTH, concatenated.length);
        int room = Math.min(concatenated.length, definition.keyLength());
        mask.view(KEY_FEEDBACK, room).setBytes(Arrays.copyOf(concatenated, room));
    }

    /** 見つからなかった。満たした段が無ければ段を "00" にする (P-154)。 */
    private void notFound(Segment deepest) {
        if (deepest != null) {
            feedback(deepest);
            return;
        }
        text(LEVEL, 2, "00");
        text(SEGMENT_NAME, 8, "");
        integer(KEY_LENGTH, 0);
    }

    private void text(int offset, int length, String value) {
        String padded = value.length() >= length ? value.substring(0, length)
                : value + " ".repeat(length - value.length());
        mask.view(offset, length).setBytes(codePage.encode(padded));
    }

    private void integer(int offset, int value) {
        DataView view = mask.view(offset, 4);
        view.set(0, (byte) (value >>> 24));
        view.set(1, (byte) (value >>> 16));
        view.set(2, (byte) (value >>> 8));
        view.set(3, (byte) value);
    }
}
