package dev.cobolonjava.ims.psb;

import static dev.cobolonjava.ims.gen.Operands.allowOnly;
import static dev.cobolonjava.ims.gen.Operands.name;
import static dev.cobolonjava.ims.gen.Operands.positive;
import static dev.cobolonjava.ims.gen.Operands.required;
import static dev.cobolonjava.ims.gen.Operands.unsupported;
import static dev.cobolonjava.ims.gen.Operands.yesNo;

import dev.cobolonjava.ims.gen.ImsGenerationException;
import dev.cobolonjava.ims.gen.MacroReader;
import dev.cobolonjava.ims.gen.MacroStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PSBGEN の原文を読む (設計 78 §2.1)。
 *
 * <p>PCB の並びはプログラムへ渡す PCB の並びそのものである。DL/I の結果に効く指定は、知らないキーワードを断る。
 * 設計 78 §1.1 の L0 (GSAM、二次索引の {@code PROCSEQ=}、複数の位置 {@code POS=M}、フィールド単位の感知
 * {@code SENFLD}) は、対応していないと告げて断る。
 */
public final class PsbParser {

    private static final Set<String> DB_KEYWORDS = Set.of(
            "TYPE", "DBDNAME", "NAME", "PROCOPT", "KEYLEN", "PCBNAME", "POS", "LIST", "SB", "EXTERNALNAME");
    private static final Set<String> TP_KEYWORDS = Set.of(
            "TYPE", "LTERM", "NAME", "MODIFY", "EXPRESS", "SAMETRM", "PCBNAME", "LIST");
    private static final Set<String> SENSEG_KEYWORDS = Set.of("NAME", "PARENT", "PROCOPT", "SSPTR");
    private static final Set<String> PSBGEN_KEYWORDS = Set.of(
            "PSBNAME", "LANG", "CMPAT", "MAXQ", "IOASIZE", "SSASIZE", "IOEROPN", "OLIC", "LOCKMAX", "GSROLLBK");
    /** {@code PROCOPT=} に書ける文字。 */
    private static final Pattern PROCESSING_OPTIONS = Pattern.compile("[GIRDAPOLSEKTNH]{1,4}");

    private PsbParser() {
    }

    public static ProgramSpecification parse(String source) {
        String psbName = null;
        String language = null;
        boolean compatibility = false;
        List<PcbDefinition> pcbs = new ArrayList<>();
        List<SensitiveSegment> segments = null;
        PcbDefinition.Database open = null;
        int openLine = 0;
        for (MacroStatement statement : MacroReader.read(source)) {
            if (psbName != null && !statement.operation().equals("END")) {
                throw new ImsGenerationException(statement.line(), "PSBGEN must be the last statement before END");
            }
            switch (statement.operation()) {
                case "PCB" -> {
                    if (open != null) {
                        pcbs.set(pcbs.size() - 1, close(open, segments, openLine));
                    }
                    open = null;
                    PcbDefinition pcb = pcb(statement);
                    pcbs.add(pcb);
                    if (pcb instanceof PcbDefinition.Database database) {
                        open = database;
                        openLine = statement.line();
                        segments = new ArrayList<>();
                    }
                }
                case "SENSEG" -> {
                    if (open == null) {
                        throw new ImsGenerationException(statement.line(), "SENSEG must follow a PCB TYPE=DB");
                    }
                    segments.add(senseg(statement, segments));
                }
                case "SENFLD" -> throw unsupported(statement, "SENFLD (field-level sensitivity)");
                case "PSBGEN" -> {
                    if (open != null) {
                        pcbs.set(pcbs.size() - 1, close(open, segments, openLine));
                        open = null;
                    }
                    Map<String, String> keywords = statement.keywords();
                    allowOnly(statement, keywords, PSBGEN_KEYWORDS);
                    psbName = name(statement, required(statement, keywords, "PSBNAME"), "PSB name");
                    language = keywords.getOrDefault("LANG", "ASSEM").toUpperCase(Locale.ROOT);
                    compatibility = yesNo(statement, keywords.get("CMPAT"), false, "CMPAT");
                }
                case "END", "PRINT", "TITLE", "EJECT", "SPACE" -> {
                }
                default -> throw new ImsGenerationException(statement.line(),
                        "unknown PSBGEN statement: " + statement.operation());
            }
        }
        if (psbName == null) {
            throw new ImsGenerationException(1, "the PSBGEN statement is missing");
        }
        return new ProgramSpecification(psbName, language, compatibility, pcbs);
    }

    private static PcbDefinition.Database close(PcbDefinition.Database pcb, List<SensitiveSegment> segments,
                                                int line) {
        if (segments.isEmpty()) {
            throw new ImsGenerationException(line, "PCB " + pcb.dbdName() + " has no SENSEG");
        }
        return new PcbDefinition.Database(pcb.name(), pcb.dbdName(), pcb.processingOptions(), pcb.keyLength(),
                segments);
    }

    private static PcbDefinition pcb(MacroStatement statement) {
        Map<String, String> keywords = statement.keywords();
        String type = required(statement, keywords, "TYPE").toUpperCase(Locale.ROOT);
        String label = keywords.containsKey("PCBNAME")
                ? name(statement, keywords.get("PCBNAME"), "PCBNAME") : statement.label();
        return switch (type) {
            case "DB" -> {
                if (keywords.containsKey("PROCSEQ")) {
                    throw unsupported(statement, "PROCSEQ= (processing through a secondary index)");
                }
                allowOnly(statement, keywords, DB_KEYWORDS);
                String position = keywords.getOrDefault("POS", "S").toUpperCase(Locale.ROOT);
                if (position.startsWith("M")) {
                    throw unsupported(statement, "POS=M (multiple positioning)");
                }
                if (!position.startsWith("S")) {
                    throw new ImsGenerationException(statement.line(), "unknown POS: " + keywords.get("POS"));
                }
                String dbd = keywords.containsKey("DBDNAME") ? keywords.get("DBDNAME") : keywords.get("NAME");
                if (dbd == null) {
                    throw new ImsGenerationException(statement.line(), "PCB TYPE=DB requires DBDNAME=");
                }
                String options = processingOptions(statement, keywords.getOrDefault("PROCOPT", "A"));
                int keyLength = positive(statement, required(statement, keywords, "KEYLEN"), "KEYLEN");
                yield new PcbDefinition.Database(label, name(statement, dbd, "DBD name"), options, keyLength,
                        List.of());
            }
            case "TP" -> {
                allowOnly(statement, keywords, TP_KEYWORDS);
                String terminal = keywords.containsKey("LTERM") ? keywords.get("LTERM") : keywords.get("NAME");
                boolean modifiable = yesNo(statement, keywords.get("MODIFY"), false, "MODIFY");
                if (terminal == null && !modifiable) {
                    throw new ImsGenerationException(statement.line(),
                            "PCB TYPE=TP requires LTERM= or MODIFY=YES");
                }
                yield new PcbDefinition.Terminal(label,
                        terminal == null ? null : name(statement, terminal, "LTERM"), modifiable,
                        yesNo(statement, keywords.get("EXPRESS"), false, "EXPRESS"));
            }
            case "GSAM" -> throw unsupported(statement, "PCB TYPE=GSAM");
            default -> throw new ImsGenerationException(statement.line(), "unknown PCB TYPE: " + type);
        };
    }

    private static SensitiveSegment senseg(MacroStatement statement, List<SensitiveSegment> earlier) {
        Map<String, String> keywords = statement.keywords();
        if (keywords.containsKey("INDICES")) {
            throw unsupported(statement, "SENSEG INDICES= (a secondary index)");
        }
        allowOnly(statement, keywords, SENSEG_KEYWORDS);
        String segmentName = name(statement, required(statement, keywords, "NAME"), "segment name");
        String parentText = keywords.get("PARENT");
        String parent = parentText == null || parentText.equals("0")
                ? null : name(statement, parentText, "parent segment name");
        if (parent == null && !earlier.isEmpty()) {
            throw new ImsGenerationException(statement.line(),
                    "only the first SENSEG of a PCB can be the root: " + segmentName);
        }
        if (parent != null && earlier.stream().noneMatch(segment -> segment.name().equals(parent))) {
            throw new ImsGenerationException(statement.line(),
                    "the parent of SENSEG " + segmentName + " must be an earlier SENSEG: " + parent);
        }
        String options = keywords.containsKey("PROCOPT")
                ? processingOptions(statement, keywords.get("PROCOPT")) : null;
        return new SensitiveSegment(segmentName, parent, options);
    }

    private static String processingOptions(MacroStatement statement, String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (!PROCESSING_OPTIONS.matcher(upper).matches()) {
            throw new ImsGenerationException(statement.line(), "unknown PROCOPT: " + value);
        }
        return upper;
    }
}
