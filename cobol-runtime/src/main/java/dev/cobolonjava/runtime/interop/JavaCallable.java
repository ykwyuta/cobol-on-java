package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.storage.DataView;
import java.util.List;

/** COBOL の通常の {@code CALL} へ明示登録して公開する Java 実装。 */
@FunctionalInterface
public interface JavaCallable {

    void invoke(JavaCallContext context, List<DataView> arguments) throws Exception;
}
