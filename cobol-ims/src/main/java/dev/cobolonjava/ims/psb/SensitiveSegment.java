package dev.cobolonjava.ims.psb;

/**
 * PSBGEN の {@code SENSEG} 文 1 つ。
 *
 * @param name              セグメント名
 * @param parent            親のセグメント名。根なら {@code null}
 * @param processingOptions セグメントの {@code PROCOPT=}。書かれていなければ {@code null} (PCB のものを使う)
 */
public record SensitiveSegment(String name, String parent, String processingOptions) {
}
