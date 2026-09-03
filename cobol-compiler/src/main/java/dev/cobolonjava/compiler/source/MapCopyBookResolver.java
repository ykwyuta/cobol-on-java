package dev.cobolonjava.compiler.source;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 名前と内容の対応表からコピー句を解決する。試験と組み込み用途を想定している。
 *
 * <p>名前の照合は大文字と小文字を区別しない。COBOL 語の照合規則に合わせている。
 */
public final class MapCopyBookResolver implements CopyBookResolver {

    private final Map<String, CopyBook> books = new HashMap<>();

    /** コピー句を登録する。ファイル名は名前をそのまま用いる。 */
    public MapCopyBookResolver put(String textName, String text) {
        books.put(key(null, textName), new CopyBook(textName, text));
        return this;
    }

    /** ライブラリ名つきでコピー句を登録する。 */
    public MapCopyBookResolver put(String libraryName, String textName, String text) {
        books.put(key(libraryName, textName),
                new CopyBook(libraryName + "(" + textName + ")", text));
        return this;
    }

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        CopyBook book = books.get(key(libraryName, textName));
        if (book == null && libraryName != null) {
            // ライブラリを指定して見つからなければ、ライブラリなしの登録も見る
            book = books.get(key(null, textName));
        }
        return Optional.ofNullable(book);
    }

    private static String key(String libraryName, String textName) {
        String library = libraryName == null ? "" : libraryName.toUpperCase(Locale.ROOT);
        return library + " " + textName.toUpperCase(Locale.ROOT);
    }
}
