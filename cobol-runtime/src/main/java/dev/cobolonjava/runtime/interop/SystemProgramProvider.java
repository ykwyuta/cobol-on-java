package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;

/**
 * ジョブが名指すプログラムのうち、翻訳した資産でもジョブ実行のユーティリティでもないものを、
 * 別のモジュールから差し込む口 (例: IMS の {@code DFSRRC00}、設計 78 §7.1)。
 *
 * <p>{@link java.util.ServiceLoader} で引く。ジョブ実行はこの口の実装を知らずに済み、差し込む側も
 * ジョブ実行に依存せずに済む。どちらも依存するのは {@code cobol-runtime} だけである。
 */
public interface SystemProgramProvider {

    /**
     * 名前でプログラムを引く。
     *
     * @param name   ジョブが名指したプログラムの名前
     * @param loader 翻訳したプログラムを読み込む読み込み器。差し込んだプログラムが資産を呼ぶときに使う
     * @return 知らない名前なら {@code null}
     */
    CobolProgram find(String name, ClassLoader loader);
}
