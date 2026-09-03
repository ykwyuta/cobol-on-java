package dev.cobolonjava.compiler.source;

/** ソースの参照形式 (要件 FR-002)。 */
public enum SourceFormat {

    /** 固定形式。1-6 桁が一連番号、7 桁目が標識、8-72 桁が本文。 */
    FIXED,
    /** 自由形式。カラムの区分がなく、行の全体が本文。 */
    FREE;

    /** この形式の既定の読み取り器。 */
    public SourceReader reader() {
        return this == FREE ? FreeFormatReader.standard() : FixedFormatReader.standard();
    }
}
