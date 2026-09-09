package dev.cobolonjava.compiler.source;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * プリプロセッサ全体の入口 (要件 FR-002, FR-003, FR-090, FR-091, FR-094)。
 *
 * <p>ARC-8 の方針に従い、文脈依存の処理をここまでで吸収する。後段の構文解析器には
 * カラムも継続行も {@code COPY} も見せない。
 *
 * <h2>処理の順序</h2>
 * <ol>
 *   <li>プロセス文 {@code CBL} / {@code PROCESS} の読み取り ({@link ProcessStatement})</li>
 *   <li>参照形式の読み取りと継続行の連結 ({@link SourceReader})</li>
 *   <li>{@code COPY} の展開と {@code REPLACING} の適用 ({@link CopyExpander})</li>
 *   <li>{@code REPLACE} の適用 ({@link ReplaceProcessor})</li>
 *   <li>コンパイラ指示文の処理 ({@link DirectiveProcessor})</li>
 *   <li>「島」の切り出しとトークン化 ({@link Tokenizer})</li>
 * </ol>
 *
 * <p>この順序は COBOL が定めるものである。{@code COPY} をすべて処理してから
 * {@code REPLACE} を適用するため、<b>{@code REPLACE} はコピー句から展開された語にも効く</b>。
 * 順序を逆にすると、コピー句の中身が置換の対象から漏れる。
 *
 * <p>コンパイラ指示文を最後に置くのは、<b>コピー句の中の {@code >>IF} を処理し、
 * かつ主ソースの {@code >>DEFINE} をそこへ届ける</b>ためである (暫定判断 P-020)。
 */
public final class Preprocessor {

    private final CopyBookResolver resolver;
    private final SourceReader reader;
    private final Map<String, String> parameters;

    public Preprocessor(CopyBookResolver resolver, SourceReader reader,
                        Map<String, String> parameters) {
        this.resolver = resolver;
        this.reader = reader;
        this.parameters = Map.copyOf(parameters);
    }

    public Preprocessor(CopyBookResolver resolver, SourceReader reader) {
        this(resolver, reader, Map.of());
    }

    /**
     * {@code >>DEFINE 名 AS PARAMETER} に供給する値を差し替えた構成を返す。
     * 翻訳時オプションから与えられる。
     */
    public Preprocessor withParameters(Map<String, String> values) {
        return new Preprocessor(resolver, reader, values);
    }

    /** コピー句を持たない構成。{@code COPY} が現れたら誤りになる。 */
    public static Preprocessor withoutCopybooks() {
        return new Preprocessor((name, library) -> Optional.empty(), FixedFormatReader.standard());
    }

    /** 既定の読み取り器 (固定形式) を用いる構成。 */
    public static Preprocessor with(CopyBookResolver resolver) {
        return new Preprocessor(resolver, FixedFormatReader.standard());
    }

    /** 読み取り器を指定する構成。自由形式のソースを読むときに用いる。 */
    public static Preprocessor with(CopyBookResolver resolver, SourceReader reader) {
        return new Preprocessor(resolver, reader);
    }

    /**
     * ソースを正規化する。
     *
     * @param fileName 診断で示すファイル名
     * @param source   固定形式のソース
     */
    public NormalizedSource process(String fileName, String source) {
        ProcessStatement.Scan scan = ProcessStatement.scan(source);
        SourceReader effective = readerFor(scan.options());
        if (debuggingMode(source)) {
            // 7 桁目の D の行を生かす。書かれていなければ注釈と同じである (要件 FR-193)
            effective = effective.withDebuggingMode();
        }
        NormalizedSource normalized = effective.normalize(fileName, scan.source());
        NormalizedSource expanded = new CopyExpander(resolver, effective).expand(normalized);
        NormalizedSource replaced = ReplaceProcessor.apply(expanded);
        return DirectiveProcessor.apply(replaced, parameters);
    }

    /** ソースに書かれたプロセス文のオプションを読む (要件 FR-093)。 */
    public CompilerOptions optionsOf(String source) {
        return ProcessStatement.scan(source).options();
    }

    /**
     * 実際に用いる読み取り器。プロセス文の {@code SOURCEFORMAT} が指定されていれば
     * それに従い、指定がなければ構成された読み取り器を用いる。
     *
     * <p>指定が構成と同じ形式であれば、構成された読み取り器をそのまま使う。
     * デバッグ行の扱いなど、形式以外の設定を落とさないためである。
     */
    private SourceReader readerFor(CompilerOptions options) {
        return options.sourceFormat()
                .map(format -> format == reader.format() ? reader : format.reader())
                .orElse(reader);
    }

    /**
     * {@code WITH DEBUGGING MODE} が書かれているか (要件 FR-193)。
     *
     * <p>7 桁目の {@code D} の行を生かすかどうかが、これで決まる。
     * <b>読み取りより前に知らなければならない</b>ので、正規化する前の字面を見る。
     * 環境部の構文木を待っていては、その行はすでに落ちている。
     *
     * <p>注釈行は見ない。7 桁目が {@code *} か {@code /} の行、および自由形式の
     * {@code *>} から始まる行である。説明の文に書かれた語で誤って有効にしないためである。
     */
    public static boolean debuggingMode(String source) {
        for (String line : source.split("\n", -1)) {
            String text = line.stripTrailing();
            if (text.length() >= 7) {
                char indicator = text.charAt(6);
                if (indicator == '*' || indicator == '/') {
                    continue;
                }
            }
            String trimmed = text.stripLeading();
            if (trimmed.startsWith("*>")) {
                continue;
            }
            if (text.toUpperCase(Locale.ROOT).replace(" ", "").contains("WITHDEBUGGINGMODE")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 展開後ソースのリストを組み立てる (要件 FR-094)。
     *
     * <p>{@code COPY ... SUPPRESS} が指定されたコピー句の行は落とす。抑止はリストにだけ
     * 効くものであり、展開結果は {@link #process} と同じである。
     *
     * @param fileName 主ソースのファイル名
     * @param source   固定形式または自由形式のソース
     */
    public String listing(String fileName, String source) {
        ProcessStatement.Scan scan = ProcessStatement.scan(source);
        SourceReader effective = readerFor(scan.options());
        NormalizedSource normalized = effective.normalize(fileName, scan.source());
        CopyExpander expander = new CopyExpander(resolver, effective);
        NormalizedSource expanded = expander.expand(normalized);
        NormalizedSource result =
                DirectiveProcessor.apply(ReplaceProcessor.apply(expanded), parameters);
        return SourceListing.render(result, fileName, expander.suppressedFiles());
    }

    /**
     * ソースを構文解析器へ渡すトークン列にする。処理系の前段としてはこちらが入口である。
     *
     * @param fileName 診断で示すファイル名
     * @param source   固定形式のソース
     */
    public List<SourceToken> tokenize(String fileName, String source) {
        return Tokenizer.tokenize(process(fileName, source));
    }
}
