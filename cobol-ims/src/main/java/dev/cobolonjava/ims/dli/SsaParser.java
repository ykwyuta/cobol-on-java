package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.FieldDefinition;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import dev.cobolonjava.ims.dli.SegmentSearchArgument.Qualification;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SSA を読む (設計 78 §3.4)。
 *
 * <pre>
 * 無限定     SEGNAME␣
 * 修飾       SEGNAME(FIELDNAMEop値)      op は 2 文字、値はフィールドの長さ
 * 複数の修飾 SEGNAME(F1      EQ値*F2      GT値+F3      LT値)
 * </pre>
 *
 * <p>コマンドコードは C / D / F / L / N / P を読み、null の {@code -} と、排他の {@code Q} (級の 1 文字を含む) は
 * 読み飛ばす (P-159)。役割を確かめていない {@code U} / {@code V} は止める (P-100)。独立 AND ({@code #}) は
 * 二次索引を通したときだけ意味を持つので断る。
 */
final class SsaParser {

    private static final int NAME = 8;
    private static final int OPERATOR = 2;

    private SsaParser() {
    }

    static SegmentSearchArgument parse(DataView ssa, DatabaseDefinition dbd, Set<String> sensitive,
                                       CodePage codePage) {
        byte[] bytes = ssa.toByteArray();
        if (bytes.length < NAME) {
            throw new DliStatusException(StatusCode.AJ);
        }
        String segmentName = text(codePage, bytes, 0, NAME).stripTrailing();
        SegmentDefinition segment = dbd.segment(segmentName);
        // 感知しないセグメントは PCB から見えない。DBD に無い名前と同じに扱う (暫定判断 P-154)
        if (segment == null || !sensitive.contains(segmentName)) {
            throw new DliStatusException(StatusCode.AJ);
        }
        int p = NAME;
        StringBuilder codes = new StringBuilder();
        if (p < bytes.length && ch(codePage, bytes[p]) == '*') {
            p++;
            while (p < bytes.length && ch(codePage, bytes[p]) != '(' && ch(codePage, bytes[p]) != ' ') {
                char code = ch(codePage, bytes[p]);
                switch (code) {
                    case '-' -> {
                    }
                    case 'C', 'D', 'F', 'L', 'N', 'P' -> codes.append(code);
                    case 'Q' -> {
                        // 次の 1 文字は排他の級。排他を持たないので読み飛ばす (P-159)
                        p++;
                        if (p >= bytes.length) {
                            throw new DliStatusException(StatusCode.AJ);
                        }
                    }
                    case 'U', 'V' -> throw new DliCallException("SSA command code " + code + " on segment "
                            + segmentName + " is not supported yet; its role is not confirmed (provisional P-100)");
                    default -> throw new DliStatusException(StatusCode.AJ);
                }
                p++;
            }
        }
        if (codes.indexOf("C") >= 0) {
            return concatenated(bytes, p, segment, dbd, codes.toString(), codePage);
        }
        if (p >= bytes.length || ch(codePage, bytes[p]) == ' ') {
            return new SegmentSearchArgument(segment, List.of(), codes.toString(), null);
        }
        if (ch(codePage, bytes[p]) != '(') {
            throw new DliStatusException(StatusCode.AJ);
        }
        p++;
        List<List<Qualification>> alternatives = new ArrayList<>();
        List<Qualification> group = new ArrayList<>();
        while (true) {
            if (p + NAME + OPERATOR > bytes.length) {
                throw new DliStatusException(StatusCode.AJ);
            }
            FieldDefinition field = segment.field(text(codePage, bytes, p, NAME).strip());
            if (field == null) {
                throw new DliStatusException(StatusCode.AK);
            }
            p += NAME;
            SegmentSearchArgument.Operator operator =
                    SegmentSearchArgument.Operator.of(text(codePage, bytes, p, OPERATOR));
            if (operator == null) {
                throw new DliStatusException(StatusCode.AJ);
            }
            p += OPERATOR;
            if (p + field.bytes() >= bytes.length) {
                // 値のあとに閉じ括弧か結び付きの 1 文字が要る
                throw new DliStatusException(StatusCode.AJ);
            }
            group.add(new Qualification(field, operator, Arrays.copyOfRange(bytes, p, p + field.bytes())));
            p += field.bytes();
            char connector = ch(codePage, bytes[p]);
            p++;
            switch (connector) {
                case ')' -> {
                    alternatives.add(group);
                    return new SegmentSearchArgument(segment, alternatives, codes.toString(), null);
                }
                case '*', '&' -> {
                }
                case '+', '|' -> {
                    alternatives.add(group);
                    group = new ArrayList<>();
                }
                case '#' -> throw new DliCallException("the independent AND (#) in an SSA is not supported"
                        + " (it has meaning only through a secondary index)");
                default -> throw new DliStatusException(StatusCode.AJ);
            }
        }
    }

    /**
     * {@code SEGNAME*C(連結キー)}。括弧の中はフィールドの修飾ではなく、根からこのセグメントまでの順序フィールドを
     * つないだ値である。
     */
    private static SegmentSearchArgument concatenated(byte[] bytes, int p, SegmentDefinition segment,
                                                      DatabaseDefinition dbd, String codes, CodePage codePage) {
        int length = 0;
        for (SegmentDefinition at = segment; at != null;
             at = at.parent() == null ? null : dbd.segment(at.parent())) {
            length += at.sequenceField() == null ? 0 : at.sequenceField().bytes();
        }
        if (length == 0 || p >= bytes.length || ch(codePage, bytes[p]) != '('
                || p + 1 + length >= bytes.length || ch(codePage, bytes[p + 1 + length]) != ')') {
            throw new DliStatusException(StatusCode.AJ);
        }
        return new SegmentSearchArgument(segment, List.of(), codes,
                Arrays.copyOfRange(bytes, p + 1, p + 1 + length));
    }

    private static char ch(CodePage codePage, byte b) {
        return codePage.decode(new byte[] {b}).charAt(0);
    }

    private static String text(CodePage codePage, byte[] bytes, int from, int length) {
        return codePage.decode(Arrays.copyOfRange(bytes, from, from + length));
    }
}
