package dev.cobolonjava.ims.dbd;

import static dev.cobolonjava.ims.gen.Operands.allowOnly;
import static dev.cobolonjava.ims.gen.Operands.name;
import static dev.cobolonjava.ims.gen.Operands.positive;
import static dev.cobolonjava.ims.gen.Operands.required;
import static dev.cobolonjava.ims.gen.Operands.unsupported;

import dev.cobolonjava.ims.gen.ImsGenerationException;
import dev.cobolonjava.ims.gen.MacroReader;
import dev.cobolonjava.ims.gen.MacroStatement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DBDGEN の原文を読む (設計 78 §2.1)。
 *
 * <p>DL/I の呼び出しの結果に効く指定 ({@code SEGM} / {@code FIELD} の形、{@code ACCESS=}、{@code RULES=}) は
 * 知らないキーワードを断る。物理的な置き方の指定 ({@code DBD} の {@code RMNAME=}、{@code DATASET} の
 * {@code DEVICE=} 等) は、RDB やファイルに置くこの処理系では意味を持たないので読み飛ばす。
 *
 * <p>設計 78 §1.1 の L0 (論理関係、二次索引、GSAM、Fast Path) は、対応していないと告げて断る。
 */
public final class DbdParser {

    private static final Set<String> SEGM_KEYWORDS = Set.of(
            "NAME", "PARENT", "BYTES", "RULES", "POINTER", "PTR", "FREQ", "EXTERNALNAME", "COMPRTN", "DSGROUP");
    private static final Set<String> FIELD_KEYWORDS = Set.of(
            "NAME", "BYTES", "START", "TYPE", "EXTERNALNAME", "DATATYPE", "SCALE");
    private static final Pattern LOGICAL_RULES = Pattern.compile("[PLVB]{1,3}");
    private static final int MAX_LEVELS = 15;

    private DbdParser() {
    }

    /** 可変の途中の形。フィールドを集めてからセグメントにする。 */
    private record Draft(String name, String parent, int level, int maxBytes, int minBytes, InsertRule rule,
                         List<FieldDefinition> fields, int line) {
    }

    public static DatabaseDefinition parse(String source) {
        String name = null;
        AccessMethod access = null;
        String dataSet = null;
        List<Draft> drafts = new ArrayList<>();
        // いま開いている階層の道。次の SEGM の親はこの道の上になければならない
        Deque<Draft> path = new ArrayDeque<>();
        for (MacroStatement statement : MacroReader.read(source)) {
            switch (statement.operation()) {
                case "DBD" -> {
                    if (name != null) {
                        throw new ImsGenerationException(statement.line(), "the DBD statement is written twice");
                    }
                    Map<String, String> keywords = statement.keywords();
                    name = name(statement, required(statement, keywords, "NAME"), "DBD name");
                    access = accessOf(statement, required(statement, keywords, "ACCESS"));
                }
                case "DATASET" -> {
                    String dd = statement.keywords().get("DD1");
                    if (dataSet == null && dd != null) {
                        dataSet = name(statement, dd, "DD name");
                    }
                }
                case "SEGM" -> {
                    requireDbd(statement, name);
                    Draft draft = segment(statement, path, drafts);
                    drafts.add(draft);
                    path.push(draft);
                }
                case "FIELD" -> {
                    if (drafts.isEmpty()) {
                        throw new ImsGenerationException(statement.line(), "FIELD must follow a SEGM statement");
                    }
                    Draft segment = drafts.get(drafts.size() - 1);
                    segment.fields().add(field(statement, segment));
                }
                case "LCHILD" -> primaryIndex(statement, access, drafts);
                case "XDFLD" -> throw unsupported(statement, "XDFLD (a secondary index)");
                // Java のアプリケーションへ値を変換する指定であり、DL/I の呼び出しには効かない
                case "DFSMARSH" -> {
                }
                case "DBDGEN", "FINISH", "END", "PRINT", "TITLE", "EJECT", "SPACE" -> {
                }
                default -> throw new ImsGenerationException(statement.line(),
                        "unknown DBDGEN statement: " + statement.operation());
            }
        }
        if (name == null) {
            throw new ImsGenerationException(1, "the DBD statement is missing");
        }
        if (drafts.isEmpty()) {
            throw new ImsGenerationException(1, "a DBD requires at least one SEGM statement");
        }
        List<SegmentDefinition> segments = new ArrayList<>();
        for (Draft draft : drafts) {
            checkSequenceFields(draft);
            segments.add(new SegmentDefinition(draft.name(), draft.parent(), draft.level(), draft.maxBytes(),
                    draft.minBytes(), draft.rule(), draft.fields()));
        }
        return new DatabaseDefinition(name, access, dataSet, segments);
    }

    private static void requireDbd(MacroStatement statement, String name) {
        if (name == null) {
            throw new ImsGenerationException(statement.line(),
                    statement.operation() + " must follow the DBD statement");
        }
    }

    private static AccessMethod accessOf(MacroStatement statement, String value) {
        String method = statement.elements(value).get(0).toUpperCase(Locale.ROOT);
        return switch (method) {
            case "HDAM" -> AccessMethod.HDAM;
            case "PHDAM" -> AccessMethod.PHDAM;
            case "HIDAM" -> AccessMethod.HIDAM;
            case "PHIDAM" -> AccessMethod.PHIDAM;
            case "HISAM" -> AccessMethod.HISAM;
            case "SHISAM" -> AccessMethod.SHISAM;
            case "INDEX", "PSINDEX" -> throw unsupported(statement, "ACCESS=" + method + " (an index database)");
            case "LOGICAL" -> throw unsupported(statement, "ACCESS=LOGICAL (a logical database)");
            case "GSAM" -> throw unsupported(statement, "ACCESS=GSAM");
            case "DEDB", "MSDB" -> throw unsupported(statement, "ACCESS=" + method + " (Fast Path)");
            case "HSAM", "SHSAM" -> throw unsupported(statement, "ACCESS=" + method);
            default -> throw new ImsGenerationException(statement.line(), "unknown ACCESS: " + value);
        };
    }

    private static Draft segment(MacroStatement statement, Deque<Draft> path, List<Draft> drafts) {
        Map<String, String> keywords = statement.keywords();
        if (keywords.containsKey("SOURCE")) {
            throw unsupported(statement, "SEGM SOURCE= (a logical or virtual segment)");
        }
        allowOnly(statement, keywords, SEGM_KEYWORDS);
        String segmentName = name(statement, required(statement, keywords, "NAME"), "segment name");
        for (Draft known : drafts) {
            if (known.name().equals(segmentName)) {
                throw new ImsGenerationException(statement.line(), "segment " + segmentName + " is defined twice");
            }
        }

        String parent = parentOf(statement, keywords.get("PARENT"));
        int level;
        if (parent == null) {
            if (!drafts.isEmpty()) {
                throw new ImsGenerationException(statement.line(),
                        "a DBD has only one root segment; " + segmentName + " has no parent");
            }
            level = 1;
        } else {
            if (drafts.isEmpty()) {
                throw new ImsGenerationException(statement.line(), "the first SEGM must be the root segment");
            }
            // DBD は階層の順に書く。親は開いている道の上にある。道から外れた親は、順が崩れている
            while (!path.isEmpty() && !path.peek().name().equals(parent)) {
                path.pop();
            }
            if (path.isEmpty()) {
                throw new ImsGenerationException(statement.line(), "the parent of " + segmentName
                        + " is not an earlier segment on the hierarchical path: " + parent);
            }
            level = path.peek().level() + 1;
            if (level > MAX_LEVELS) {
                throw new ImsGenerationException(statement.line(), "a DBD has at most 15 levels");
            }
        }

        List<String> bytes = statement.elements(required(statement, keywords, "BYTES"));
        int maxBytes = positive(statement, bytes.get(0), "BYTES");
        int minBytes = 0;
        if (bytes.size() == 2) {
            minBytes = positive(statement, bytes.get(1), "BYTES minimum");
            if (minBytes > maxBytes) {
                throw new ImsGenerationException(statement.line(), "BYTES minimum exceeds the maximum");
            }
        } else if (bytes.size() > 2) {
            throw new ImsGenerationException(statement.line(), "BYTES takes (maximum,minimum): " + keywords.get("BYTES"));
        }
        return new Draft(segmentName, parent, level, maxBytes, minBytes,
                ruleOf(statement, keywords.get("RULES")), new ArrayList<>(), statement.line());
    }

    /** {@code PARENT=0}、{@code PARENT=名前}、{@code PARENT=((名前,SNGL))}。論理親を並べた形は断る。 */
    private static String parentOf(MacroStatement statement, String value) {
        if (value == null || value.equals("0")) {
            return null;
        }
        List<String> outer = statement.elements(value);
        if (outer.size() != 1) {
            throw unsupported(statement, "a logical parent in PARENT=");
        }
        List<String> inner = statement.elements(outer.get(0));
        if (inner.size() > 2) {
            throw unsupported(statement, "a logical parent in PARENT=");
        }
        if (inner.size() == 2) {
            String pointer = inner.get(1).toUpperCase(Locale.ROOT);
            if (!pointer.equals("SNGL") && !pointer.equals("DBLE")) {
                throw new ImsGenerationException(statement.line(), "unknown PARENT pointer: " + inner.get(1));
            }
        }
        return name(statement, inner.get(0), "parent segment name");
    }

    /**
     * {@code RULES=(LLL,HERE)}。論理関係の規則 (1 つ目) は論理関係を持たないので読み飛ばし、挿入位置を読む。
     */
    private static InsertRule ruleOf(MacroStatement statement, String value) {
        if (value == null) {
            return InsertRule.LAST;
        }
        InsertRule rule = InsertRule.LAST;
        for (String element : statement.elements(value)) {
            String upper = element.toUpperCase(Locale.ROOT);
            switch (upper) {
                case "FIRST" -> rule = InsertRule.FIRST;
                case "LAST" -> rule = InsertRule.LAST;
                case "HERE" -> rule = InsertRule.HERE;
                default -> {
                    if (!upper.isEmpty() && !LOGICAL_RULES.matcher(upper).matches()) {
                        throw new ImsGenerationException(statement.line(), "unknown RULES: " + value);
                    }
                }
            }
        }
        return rule;
    }

    private static FieldDefinition field(MacroStatement statement, Draft segment) {
        Map<String, String> keywords = statement.keywords();
        allowOnly(statement, keywords, FIELD_KEYWORDS);
        List<String> nameParts = statement.elements(required(statement, keywords, "NAME"));
        if (nameParts.get(0).startsWith("/")) {
            throw unsupported(statement, "a system-related field " + nameParts.get(0));
        }
        String fieldName = name(statement, nameParts.get(0), "field name");
        boolean sequence = false;
        boolean unique = true;
        if (nameParts.size() > 1) {
            if (!nameParts.get(1).equalsIgnoreCase("SEQ")) {
                throw new ImsGenerationException(statement.line(), "FIELD NAME=(name,SEQ,U|M) expected: "
                        + keywords.get("NAME"));
            }
            sequence = true;
            if (nameParts.size() > 2) {
                switch (nameParts.get(2).toUpperCase(Locale.ROOT)) {
                    case "U" -> unique = true;
                    case "M" -> unique = false;
                    default -> throw new ImsGenerationException(statement.line(),
                            "the sequence field takes U or M: " + keywords.get("NAME"));
                }
            }
            if (nameParts.size() > 3) {
                throw new ImsGenerationException(statement.line(), "FIELD NAME has too many elements: "
                        + keywords.get("NAME"));
            }
        }
        if (segment.fields().stream().anyMatch(field -> field.name().equals(fieldName))) {
            throw new ImsGenerationException(statement.line(),
                    "field " + fieldName + " is defined twice in segment " + segment.name());
        }
        int bytes = positive(statement, required(statement, keywords, "BYTES"), "BYTES");
        int start = positive(statement, required(statement, keywords, "START"), "START");
        FieldType type = typeOf(statement, keywords.get("TYPE"));
        if ((type == FieldType.F && bytes != 4) || (type == FieldType.H && bytes != 2)) {
            throw new ImsGenerationException(statement.line(),
                    "TYPE=" + type + " requires BYTES=" + (type == FieldType.F ? 4 : 2));
        }
        if (start + bytes - 1 > segment.maxBytes()) {
            throw new ImsGenerationException(statement.line(), "field " + fieldName
                    + " extends beyond segment " + segment.name() + " (" + segment.maxBytes() + " bytes)");
        }
        return new FieldDefinition(fieldName, start, bytes, type, sequence, unique);
    }

    private static FieldType typeOf(MacroStatement statement, String value) {
        if (value == null) {
            return FieldType.C;
        }
        try {
            return FieldType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ImsGenerationException(statement.line(), "unknown FIELD TYPE: " + value);
        }
    }

    /** 順序フィールドは 1 つまで。 */
    private static void checkSequenceFields(Draft draft) {
        long count = draft.fields().stream().filter(FieldDefinition::sequence).count();
        if (count > 1) {
            throw new ImsGenerationException(draft.line(),
                    "segment " + draft.name() + " has more than one sequence field");
        }
    }

    /**
     * {@code LCHILD}。HIDAM / PHIDAM の根に主索引を結ぶ {@code POINTER=INDX} だけを受ける。
     *
     * <p>主索引は根をキーの順に引くためのものであり、この処理系は根をもともとキーの順に持つので、
     * 索引の DBD を読まずに済む。ほかの LCHILD は論理関係か二次索引であり、L0 として断る。
     */
    private static void primaryIndex(MacroStatement statement, AccessMethod access, List<Draft> drafts) {
        String pointer = statement.keywords().getOrDefault("POINTER", "").toUpperCase(Locale.ROOT);
        if (access == null || !access.primaryIndexed() || !pointer.equals("INDX")
                || drafts.size() != 1) {
            throw unsupported(statement, "LCHILD other than the primary index of a HIDAM root"
                    + " (a logical relationship or a secondary index)");
        }
    }
}
