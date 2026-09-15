package dev.cobolonjava.ims.dli;

import java.util.List;

/**
 * キューから取り出す入力の電文 (設計 78 §4)。
 *
 * <p>セグメントは本体だけを持つ。先頭の LL / ZZ は I/O PCB が I/O 域へ渡すときに付ける。
 *
 * @param logicalTerminal 送ってきた論理端末の名前。I/O PCB の先頭に置き、応答の宛先になる
 * @param segments        セグメントの本体。1 つ目は取引コードから始まる
 */
public record InputMessage(String logicalTerminal, List<byte[]> segments) {

    public InputMessage {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("an input message has at least one segment");
        }
        segments = segments.stream().map(byte[]::clone).toList();
    }
}
