package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.FieldDefinition;
import dev.cobolonjava.ims.dbd.FieldType;
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
        if (!allows('G', target)) {
            return StatusCode.AM;
        }
        Search found = search(database.first(sensitive), target, ssas, null, Map.of());
        if (found.segment() == null) {
            parentage = null;
            notFound(null);
            return StatusCode.GE;
        }
        return retrieved(found.segment(), io, hold, true, false);
    }

    private String getNext(DataView io, List<SegmentSearchArgument> ssas, boolean hold) {
        if (!ssas.isEmpty() && !allows('G', last(ssas).segment())) {
            return StatusCode.AM;
        }
        if (atEnd) {
            notFound(null);
            return StatusCode.GB;
        }
        Segment start = current == null ? database.first(sensitive)
                : before ? current : database.next(current, sensitive);
        if (ssas.isEmpty()) {
            if (start == null) {
                return reachedEnd();
            }
            if (!allows('G', start.definition())) {
                return StatusCode.AM;
            }
            return retrieved(start, io, hold, true, true);
        }
        Search found = search(start, last(ssas).segment(), ssas, null, Map.of());
        if (found.segment() != null) {
            return retrieved(found.segment(), io, hold, true, false);
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
        if (!ssas.isEmpty() && !allows('G', last(ssas).segment())) {
            return StatusCode.AM;
        }
        Segment start;
        if (atEnd) {
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
            return retrieved(start, io, hold, false, true);
        }
        Search found = search(start, last(ssas).segment(), ssas, parentage, Map.of());
        if (found.segment() == null) {
            feedback(parentage);
            return StatusCode.GE;
        }
        return retrieved(found.segment(), io, hold, false, false);
    }

    private String retrieved(Segment segment, DataView io, boolean hold, boolean setsParentage,
                             boolean unqualified) {
        byte[] data = segment.data();
        if (io.length() < data.length) {
            throw new DliCallException("the I/O area (" + io.length() + " bytes) is shorter than segment "
                    + segment.definition().name() + " (" + data.length + " bytes)");
        }
        io.subView(0, data.length).setBytes(data);
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
            parentage = segment;
        }
        held = hold ? segment : null;
        feedback(segment);
        return status;
    }

    // ---- 更新 ----

    private String insert(DataView io, List<SegmentSearchArgument> ssas) {
        if (ssas.isEmpty()) {
            return StatusCode.AJ;
        }
        SegmentSearchArgument target = last(ssas);
        SegmentDefinition type = target.segment();
        if (!allows('I', type)) {
            return StatusCode.AM;
        }
        // 入れるセグメント自身の SSA は無限定でなければならない
        if (target.qualified()) {
            return StatusCode.AJ;
        }
        byte[] data = segmentData(type, io);
        boolean load = loadMode();
        Segment parent = null;
        if (!type.root()) {
            parent = parentFor(type, ssas.subList(0, ssas.size() - 1));
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
        Segment inserted = database.insert(parent, type, data);
        if (inserted == null) {
            return load ? StatusCode.LB : StatusCode.II;
        }
        moveTo(inserted);
        feedback(inserted);
        return StatusCode.OK;
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

    private String replace(DataView io, List<SegmentSearchArgument> ssas) {
        if (!ssas.isEmpty()) {
            return StatusCode.AJ;
        }
        if (held == null) {
            return StatusCode.DJ;
        }
        if (!allows('R', held.definition())) {
            return StatusCode.AM;
        }
        byte[] data = segmentData(held.definition(), io);
        FieldDefinition sequence = held.definition().sequenceField();
        if (sequence != null && !Arrays.equals(HierarchicalDatabase.keyOf(held.definition(), data), held.key())) {
            return StatusCode.DA;
        }
        database.replace(held, data);
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

    private boolean pathMatches(Segment segment, List<SegmentSearchArgument> ssas, Map<Integer, Segment> fixed) {
        for (Segment node : segment.path()) {
            if (!levelMatches(node, ssas, fixed)) {
                return false;
            }
        }
        return true;
    }

    private static boolean levelMatches(Segment segment, List<SegmentSearchArgument> ssas,
                                        Map<Integer, Segment> fixed) {
        SegmentSearchArgument ssa = ssaFor(ssas, segment.definition());
        if (ssa != null && !ssa.matches(segment.data())) {
            return false;
        }
        Segment required = fixed.get(segment.level());
        return required == null || required == segment;
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
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        for (Segment node : segment.path()) {
            key.writeBytes(node.key());
        }
        byte[] concatenated = key.toByteArray();
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
