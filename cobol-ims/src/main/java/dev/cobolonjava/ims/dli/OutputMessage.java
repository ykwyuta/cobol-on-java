package dev.cobolonjava.ims.dli;

import java.util.List;

/**
 * プログラムが I/O PCB へ ISRT した応答の電文 (設計 78 §4)。
 *
 * @param destination 宛先の論理端末。入力の電文を送ってきた端末である
 * @param segments    セグメントの本体。LL / ZZ は外してある
 */
public record OutputMessage(String destination, List<byte[]> segments) {

    public OutputMessage {
        segments = segments.stream().map(byte[]::clone).toList();
    }
}
