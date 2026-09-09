package dev.cobolonjava.compiler.source;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code COPY} 文の展開と {@code REPLACING} の適用 (要件 FR-090)。
 *
 * <h2>照合は語単位である</h2>
 * <p>{@code REPLACING} の照合は文字列ではなく<b>語の列</b>に対して行われる。
 * 語と語の間の空白の数は照合に影響しない。擬似テキスト {@code ==...==} は
 * 語の列を書くための記法であり、任意の語の並びを指定できる。
 *
 * <h2>置換で差し込む語の出自は COPY を書いた側になる</h2>
 * <p>置換で差し込まれる語は、コピー句ではなく <b>{@code COPY} 文を書いた行</b>から来ている。
 * したがってその位置をそのまま保つ。診断が「置換をどこで指定したか」を指せるようにするためである。
 * 一方、置換されなかった語はコピー句の位置を保つ。同じ展開結果の中に 2 つのファイルの位置が
 * 混在するが、それが実際の出自である。
 *
 * <h2>入れ子の COPY</h2>
 * <p>コピー句の中の {@code COPY} も展開する。循環を検出できるよう、展開中のコピー句名を
 * 積んでおき、同じ名前が再び現れたら誤りとする。深さにも上限を設ける。
 * 循環を検出せずに展開すると、記憶域が尽きるまで止まらない。
 *
 * <h2>SUPPRESS はリストにだけ効く</h2>
 * <p>{@code COPY ... SUPPRESS} は<b>展開結果を変えない</b>。変わるのはリスト出力だけで、
 * そのコピー句から来た行が印字されなくなる。展開したファイル名を集めておき、
 * {@link #suppressedFiles()} で渡す。入れ子のコピー句も一緒に抑止される。
 */
public final class CopyExpander {

    /** 入れ子の深さの上限。循環でなくても異常に深い展開は誤りとみなす。 */
    private static final int MAX_DEPTH = 50;

    private final CopyBookResolver resolver;
    private final SourceReader reader;
    private final Set<String> suppressedFiles = new LinkedHashSet<>();

    public CopyExpander(CopyBookResolver resolver, SourceReader reader) {
        this.resolver = resolver;
        this.reader = reader;
    }

    public CopyExpander(CopyBookResolver resolver) {
        this(resolver, FixedFormatReader.standard());
    }

    /** 正規化済みソースの中の {@code COPY} をすべて展開する。 */
    public NormalizedSource expand(NormalizedSource source) {
        List<TextWord> expanded = expand(PreprocessorLexer.lex(source), new ArrayDeque<>(), false);
        return PreprocessorLexer.emit(expanded);
    }

    /**
     * 直前の展開で {@code SUPPRESS} が指定されたコピー句のファイル名。
     * リスト出力 ({@link SourceListing}) から除くために用いる。
     */
    public Set<String> suppressedFiles() {
        return Set.copyOf(suppressedFiles);
    }

    private List<TextWord> expand(List<TextWord> words, Deque<String> stack, boolean suppressed) {
        List<TextWord> out = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            if (!words.get(i).isWord("COPY")) {
                out.add(words.get(i));
                i++;
                continue;
            }
            CopyStatement statement = parseCopy(words, i);
            out.addAll(expandCopyBook(statement, stack, suppressed));
            i = statement.endIndex() + 1;
        }
        return out;
    }

    private List<TextWord> expandCopyBook(CopyStatement statement, Deque<String> stack,
                                          boolean suppressedByCaller) {
        String name = statement.textName().toUpperCase(Locale.ROOT);
        if (stack.contains(name)) {
            throw new SourceFormatException(statement.origin(),
                    "COPY " + statement.textName() + " is recursive: "
                    + String.join(" then ", stack) + " then " + name);
        }
        if (stack.size() >= MAX_DEPTH) {
            throw new SourceFormatException(statement.origin(),
                    "COPY nesting exceeds " + MAX_DEPTH + " levels");
        }

        Optional<CopyBook> book = resolver.resolve(statement.textName(), statement.libraryName());
        if (book.isEmpty()) {
            throw new SourceFormatException(statement.origin(),
                    "copybook not found: " + statement.textName()
                    + (statement.libraryName() == null ? "" : " in " + statement.libraryName()));
        }

        // デバッグ行の語も置換の照合に加わる。規格は「7 桁目の D が無いものとして
        // 照合に参加する」と決めている (85 規格 XII 2.4)。だから写し句は
        // <b>デバッグ行を生かして</b>起こす。生かさない設定なら、置換のあとで落とす
        CopyBook found = book.get();
        SourceReader bookReader = reader.withDebuggingMode();
        List<TextWord> body = PreprocessorLexer.lex(
                bookReader.normalize(found.fileName(), found.text()));

        // 入れ子のコピー句も一緒に抑止される
        boolean suppressed = suppressedByCaller || statement.suppress();
        if (suppressed) {
            suppressedFiles.add(found.fileName());
        }

        stack.push(name);
        try {
            body = expand(body, stack, suppressed);
        } finally {
            stack.pop();
        }

        List<TextWord> replaced = TextReplacements.apply(body, statement.replacements());
        if (!reader.debuggingMode()) {
            replaced = withoutDebugLines(replaced, found.fileName(),
                    bookReader.debugLines(found.text()));
        }
        if (!replaced.isEmpty()) {
            // 展開結果の先頭は、直前の語と続けて読まれないよう空白で区切る
            replaced.set(0, replaced.get(0).withPrecededBySpace(true));
        }
        return replaced;
    }

    /**
     * 置換で消えずに残ったデバッグ行の語を落とす。
     *
     * <p>{@code WITH DEBUGGING MODE} が書かれていなければ、デバッグ行は注釈と同じである。
     * 照合のあいだだけ生かしておいて、ここで落とす。落とさずに残すと、写し句の
     * デバッグ行が<b>ふつうの文としてプログラムへ入ってしまう</b>。
     *
     * <p>見分けるのは出自の行番号である。置換で差し込まれた語は {@code COPY} を
     * 書いた側から来ているので、ファイル名が違い、巻き込まれない。
     */
    private static List<TextWord> withoutDebugLines(List<TextWord> words, String fileName,
                                                    Set<Integer> debugLines) {
        if (debugLines.isEmpty()) {
            return words;
        }
        List<TextWord> out = new ArrayList<>(words.size());
        for (TextWord word : words) {
            Origin origin = word.origin();
            if (fileName.equals(origin.fileName()) && debugLines.contains(origin.line())) {
                continue;
            }
            out.add(word);
        }
        return out;
    }

    /** {@code COPY} 文を解析する。 */
    private CopyStatement parseCopy(List<TextWord> words, int start) {
        Origin origin = words.get(start).origin();
        int i = start + 1;
        if (i >= words.size()) {
            throw new SourceFormatException(origin, "COPY requires a text-name");
        }
        TextWord nameWord = words.get(i++);
        if (nameWord.kind() != TextWordKind.WORD && nameWord.kind() != TextWordKind.LITERAL) {
            throw new SourceFormatException(origin, "COPY requires a text-name");
        }
        String textName = unquote(nameWord);

        String libraryName = null;
        if (i < words.size() && (words.get(i).isWord("OF") || words.get(i).isWord("IN"))) {
            i++;
            if (i >= words.size()) {
                throw new SourceFormatException(origin, "COPY OF/IN requires a library-name");
            }
            libraryName = unquote(words.get(i++));
        }

        boolean suppress = false;
        if (i < words.size() && words.get(i).isWord("SUPPRESS")) {
            suppress = true;
            i++;
            if (i < words.size() && words.get(i).isWord("PRINTING")) {
                i++;
            }
        }

        List<TextReplacement> replacements = new ArrayList<>();
        if (i < words.size() && words.get(i).isWord("REPLACING")) {
            i++;
            while (i < words.size() && !words.get(i).isSeparator('.')) {
                TextReplacements.Operand from = TextReplacements.readOperand(words, i, origin);
                i = from.endIndex() + 1;
                if (i >= words.size() || !words.get(i).isWord("BY")) {
                    throw new SourceFormatException(origin,
                            "REPLACING requires BY after an operand");
                }
                i++;
                TextReplacements.Operand to = TextReplacements.readOperand(words, i, origin);
                i = to.endIndex() + 1;
                replacements.add(new TextReplacement(from.words(), to.words()));
            }
        }

        if (i >= words.size() || !words.get(i).isSeparator('.')) {
            throw new SourceFormatException(origin, "COPY must be terminated by a period");
        }
        return new CopyStatement(textName, libraryName, suppress, replacements, i, origin);
    }

    private static String unquote(TextWord word) {
        String text = word.text();
        if (word.kind() == TextWordKind.LITERAL && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private record CopyStatement(String textName, String libraryName, boolean suppress,
                                 List<TextReplacement> replacements, int endIndex, Origin origin) {
    }
}
