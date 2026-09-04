package dev.cobolonjava.job.jcl;

/**
 * 継続をつないだ JCL の 1 文 (要件 FR-131)。
 *
 * <p>JCL は 72 桁で折り返す。オペランドの末尾がコンマなら次のカードへ続く。
 * <b>読み取りの側で 1 文へ戻して</b>から意味を取る。折り返しの都合を意味解析へ
 * 持ち込む理由がない。
 *
 * @param name      名前欄。書かれていなければ {@code null}
 * @param operation {@code JOB} / {@code EXEC} / {@code DD} など
 * @param operands  オペランド欄。継続をつないだもの
 * @param line      最初のカードの行番号 (1 起点)
 */
public record JclCard(String name, String operation, String operands, int line) {
}
