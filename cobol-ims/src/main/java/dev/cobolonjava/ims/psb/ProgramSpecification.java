package dev.cobolonjava.ims.psb;

import java.util.List;

/**
 * PSBGEN 1 本 (設計 78 §2.1)。
 *
 * @param name          {@code PSBNAME=}
 * @param language      {@code LANG=}
 * @param compatibility {@code CMPAT=YES}。バッチでも I/O PCB を先頭に渡すか
 * @param pcbs          書いた順の PCB。プログラムへ渡す PCB の並びである
 */
public record ProgramSpecification(String name, String language, boolean compatibility,
                                   List<PcbDefinition> pcbs) {

    public ProgramSpecification {
        pcbs = List.copyOf(pcbs);
    }
}
