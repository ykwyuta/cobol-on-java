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
 * <p>コマンドコードは翻訳しない。近い結果を返すより止めて知らせる (設計 78 §3.4)。null のコマンドコード
 * ({@code -}) だけは何もしないので受ける。独立 AND ({@code #}) は二次索引を通したときだけ意味を持つので断る。
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
        if (p < bytes.length && ch(codePage, bytes[p]) == '*') {
            StringBuilder codes = new StringBuilder();
            p++;
            while (p < bytes.length && ch(codePage, bytes[p]) != '(' && ch(codePage, bytes[p]) != ' ') {
                codes.append(ch(codePage, bytes[p]));
                p++;
            }
            if (!codes.toString().replace("-", "").isEmpty()) {
                throw new DliCallException("SSA command code " + codes + " on segment " + segmentName
                        + " is not supported yet (design 78 section 3.4)");
            }
        }
        if (p >= bytes.length || ch(codePage, bytes[p]) == ' ') {
            return new SegmentSearchArgument(segment, List.of());
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
                    return new SegmentSearchArgument(segment, alternatives);
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

    private static char ch(CodePage codePage, byte b) {
        return codePage.decode(new byte[] {b}).charAt(0);
    }

    private static String text(CodePage codePage, byte[] bytes, int from, int length) {
        return codePage.decode(Arrays.copyOfRange(bytes, from, from + length));
    }
}
