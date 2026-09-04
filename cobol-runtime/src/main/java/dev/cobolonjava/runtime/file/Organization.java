package dev.cobolonjava.runtime.file;

/**
 * ファイル編成 (要件 FR-100)。
 *
 * <p>編成が決めるのは<b>レコードをどう探すか</b>である。前から順にたどるのか、番号で引くのか、
 * 鍵で引くのか。レコード様式 ({@link RecordFormat}) が決めるのは「どこで切れるか」であり、
 * 別のことがらである。
 */
public enum Organization {

    /** 順編成。前から順にたどる。ホストの QSAM / PS、VSAM ESDS。 */
    SEQUENTIAL,

    /** 行順編成。改行で区切られたテキスト。 */
    LINE_SEQUENTIAL,

    /** 相対編成。1 起点の番号で引く。ホストの VSAM RRDS。 */
    RELATIVE,

    /** 索引編成。レコードの中の鍵で引く。ホストの VSAM KSDS。 */
    INDEXED
}
