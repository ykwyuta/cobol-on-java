package dev.cobolonjava.compiler.source;

import java.util.Locale;
import java.util.Optional;

/**
 * Language Environment が提供する写し句 ({@code CEEIGZCT}) を返す (暫定判断 P-139)。
 *
 * <p>IBM の {@code CEEIGZCT} は、feedback code の記号名 (CEE000、CEE2EB 等) を condition token の先頭 8 byte に対する
 * 88 レベルとして定義する。原文は参照しない。ここで置くのは CEE000 だけである。成功の token が 8 byte すべて
 * binary zero であることは公開文書に書かれているが、ほかの記号名の Case-Sev-Ctl の byte が z/OS で何になるかを
 * 確かめていないからである。ほかの記号名を書いた資産は、名前が定義されていないとして翻訳が止まる。
 *
 * <p>資産は {@code 02 Condition-Token-Value.} の直後に {@code COPY CEEIGZCT.} を書く (公開文書の例)。
 * 利用者の置き場より後ろに連ねる。
 */
public final class LanguageEnvironmentCopyBookResolver implements CopyBookResolver {

    private static final String CEEIGZCT = "           88  CEE000  VALUE X'0000000000000000'.\n";

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        if (libraryName != null) {
            return Optional.empty();
        }
        return textName.toUpperCase(Locale.ROOT).equals("CEEIGZCT")
                ? Optional.of(new CopyBook("CEEIGZCT (Language Environment system copybook)", CEEIGZCT))
                : Optional.empty();
    }
}
