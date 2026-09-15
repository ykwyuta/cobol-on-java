package dev.cobolonjava.ims.dbd;

import java.util.ArrayList;
import java.util.List;

/**
 * DBDGEN 1 本 (設計 78 §2.1)。
 *
 * @param name        DBD 名
 * @param access      {@code ACCESS=}
 * @param dataSetName 最初の {@code DATASET DD1=}。書かれていなければ {@code null}
 * @param segments    階層の順 (上から下、左から右) に並べたセグメント。先頭が根である
 */
public record DatabaseDefinition(String name, AccessMethod access, String dataSetName,
                                 List<SegmentDefinition> segments) {

    public DatabaseDefinition {
        segments = List.copyOf(segments);
    }

    public SegmentDefinition root() {
        return segments.get(0);
    }

    /** 名前でセグメントを引く。無ければ {@code null}。 */
    public SegmentDefinition segment(String segmentName) {
        for (SegmentDefinition segment : segments) {
            if (segment.name().equals(segmentName)) {
                return segment;
            }
        }
        return null;
    }

    /** 子のセグメントを DBD に書いた順に返す。兄弟のセグメント型の順は階層の順である。 */
    public List<SegmentDefinition> childrenOf(SegmentDefinition parent) {
        List<SegmentDefinition> out = new ArrayList<>();
        for (SegmentDefinition segment : segments) {
            if (parent.name().equals(segment.parent())) {
                out.add(segment);
            }
        }
        return out;
    }
}
