package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.List;

/** 一つのプログラムと LINKAGE 引数を束ねた低レベル fixture。 */
public final class CobolProgramFixture {

    private final CobolExtension extension;
    private final String name;
    private final List<DataView> arguments = new ArrayList<>();

    CobolProgramFixture(CobolExtension extension, String name) {
        this.extension = extension;
        this.name = name;
    }

    public CobolProgramFixture byReference(DataView... values) {
        arguments.addAll(List.of(values));
        return this;
    }

    public CobolTestResult call() {
        CobolCallResult result = extension.session().call(name, argumentArray());
        return CobolTestResult.from(result, extension.output());
    }

    public CobolTestResult runMain() {
        CobolCallResult result = extension.session().runMain(name, argumentArray());
        return CobolTestResult.from(result, extension.output());
    }

    /** manifestが直接起動可能と判定した通常SECTIONだけを実行する。 */
    public CobolTestResult invokeSection(String section) {
        return extension.invokeSection(name, section, argumentArray());
    }

    public Storage workingStorage() {
        return extension.session().workingStorage(name);
    }

    public void cancel() {
        extension.session().cancel(name);
    }

    private DataView[] argumentArray() {
        return arguments.toArray(DataView[]::new);
    }
}
