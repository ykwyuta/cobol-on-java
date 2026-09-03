package dev.cobolonjava.compiler.source;

import java.util.Optional;

/**
 * プリプロセッサ全体の入口 (要件 FR-002, FR-003, FR-090, FR-091, FR-094)。
 *
 * <p>ARC-8 の方針に従い、文脈依存の処理をここまでで吸収する。後段の構文解析器には
 * カラムも継続行も {@code COPY} も見せない。
 *
 * <h2>処理の順序</h2>
 * <ol>
 *   <li>固定形式のカラム分解と継続行の連結 ({@link FixedFormatReader})</li>
 *   <li>{@code COPY} の展開と {@code REPLACING} の適用 ({@link CopyExpander})</li>
 *   <li>{@code REPLACE} の適用 ({@link ReplaceProcessor})</li>
 * </ol>
 *
 * <p>この順序は COBOL が定めるものである。{@code COPY} をすべて処理してから
 * {@code REPLACE} を適用するため、<b>{@code REPLACE} はコピー句から展開された語にも効く</b>。
 * 順序を逆にすると、コピー句の中身が置換の対象から漏れる。
 */
public final class Preprocessor {

    private final CopyBookResolver resolver;
    private final FixedFormatReader reader;

    public Preprocessor(CopyBookResolver resolver, FixedFormatReader reader) {
        this.resolver = resolver;
        this.reader = reader;
    }

    /** コピー句を持たない構成。{@code COPY} が現れたら誤りになる。 */
    public static Preprocessor withoutCopybooks() {
        return new Preprocessor((name, library) -> Optional.empty(), FixedFormatReader.standard());
    }

    /** 既定の読み取り器を用いる構成。 */
    public static Preprocessor with(CopyBookResolver resolver) {
        return new Preprocessor(resolver, FixedFormatReader.standard());
    }

    /**
     * ソースを正規化する。
     *
     * @param fileName 診断で示すファイル名
     * @param source   固定形式のソース
     */
    public NormalizedSource process(String fileName, String source) {
        NormalizedSource normalized = reader.normalize(fileName, source);
        NormalizedSource expanded = new CopyExpander(resolver, reader).expand(normalized);
        return ReplaceProcessor.apply(expanded);
    }
}
