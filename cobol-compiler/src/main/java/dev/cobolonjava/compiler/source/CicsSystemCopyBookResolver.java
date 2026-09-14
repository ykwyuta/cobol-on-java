package dev.cobolonjava.compiler.source;

import dev.cobolonjava.cics.bms.CicsSystemCopybooks;
import java.util.Optional;

/**
 * CICS が提供する写し句 ({@code DFHAID} 等) を、公開仕様の値から作って返す。
 *
 * <p>利用者の置き場より後ろに連ねる。利用者が自前の写し句を置いていれば、そちらを使う。
 */
public final class CicsSystemCopyBookResolver implements CopyBookResolver {

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        if (libraryName != null) {
            return Optional.empty();
        }
        return CicsSystemCopybooks.text(textName)
                .map(text -> new CopyBook(textName.toUpperCase(java.util.Locale.ROOT)
                        + " (CICS system copybook)", text));
    }
}
