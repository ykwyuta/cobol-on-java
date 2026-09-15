package dev.cobolonjava.ims.db;

import dev.cobolonjava.ims.dbd.SegmentDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * セグメントの出現 1 つ。
 *
 * <p>値は I/O 域に置く形そのままのバイト列で持つ。可変長のセグメントは先頭の 2 byte の長さ (LL) を含む。
 * DBD の {@code FIELD START=} が LL を含めて数えるので、フィールドの位置をそのまま使える。
 */
public final class Segment {

    private final SegmentDefinition definition;
    private final Segment parent;
    private byte[] data;
    private final Map<String, List<Segment>> children = new HashMap<>();
    private boolean deleted;

    Segment(SegmentDefinition definition, Segment parent, byte[] data) {
        this.definition = definition;
        this.parent = parent;
        this.data = data.clone();
    }

    public SegmentDefinition definition() {
        return definition;
    }

    /** 親。根なら {@code null}。 */
    public Segment parent() {
        return parent;
    }

    public int level() {
        return definition.level();
    }

    /** 値の写し。 */
    public byte[] data() {
        return data.clone();
    }

    /** 順序フィールドの値。順序フィールドを持たなければ空。 */
    public byte[] key() {
        return HierarchicalDatabase.keyOf(definition, data);
    }

    /** 型ごとの子を並びの順に返す。 */
    public List<Segment> children(String segmentName) {
        return Collections.unmodifiableList(children.getOrDefault(segmentName, List.of()));
    }

    /** 削除されたか。 */
    public boolean deleted() {
        return deleted;
    }

    /** このセグメントが {@code ancestor} 自身か、その子孫か。 */
    public boolean isWithin(Segment ancestor) {
        for (Segment at = this; at != null; at = at.parent) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** 根からこのセグメントまで。 */
    public List<Segment> path() {
        Deque<Segment> out = new ArrayDeque<>();
        for (Segment at = this; at != null; at = at.parent) {
            out.addFirst(at);
        }
        return new ArrayList<>(out);
    }

    List<Segment> mutableChildren(String segmentName) {
        return children.computeIfAbsent(segmentName, ignored -> new ArrayList<>());
    }

    void replace(byte[] value) {
        data = value.clone();
    }

    void markDeleted() {
        deleted = true;
        for (List<Segment> twins : children.values()) {
            for (Segment child : twins) {
                child.markDeleted();
            }
        }
    }
}
