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
 * @param inline    {@code DD *} に続けて書かれたデータ。ほかは {@code null}
 * @param line      最初のカードの行番号 (1 起点)
 */
public record JclCard(String name, String operation, String operands, byte[] inline, int line) {

    /** 埋め込みデータを持たないカード。 */
    public JclCard(String name, String operation, String operands, int line) {
        this(name, operation, operands, null, line);
    }

    /** オペランドを差し替えた写し。シンボリックパラメタの置き換えが使う。 */
    public JclCard withOperands(String value) {
        return new JclCard(name, operation, value, inline, line);
    }

    /** 名前を差し替えた写し。目録手続きの展開が使う。 */
    public JclCard withName(String value) {
        return new JclCard(value, operation, operands, inline, line);
    }
}
