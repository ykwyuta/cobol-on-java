package dev.cobolonjava.job;

/**
 * DD 割当 1 個 (要件 FR-130)。
 *
 * @param name   プログラムが {@code ASSIGN TO} に書いた DD 名
 * @param target 実際の行き先
 */
public record DdAssignment(String name, DdTarget target) {
}
