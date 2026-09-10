package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.procedure.ProcedureHook;
import java.io.OutputStream;
import java.util.Objects;

/** 複数セッションで共有できる、不変の COBOL 実行設定。 */
public final class CobolRuntime {

    private final ProgramCatalog catalog;
    private final CodePage codePage;
    private final ClassLoader classLoader;
    private final ProcedureHook procedureHook;

    private CobolRuntime(ProgramCatalog catalog, CodePage codePage, ClassLoader classLoader,
                         ProcedureHook procedureHook) {
        this.catalog = catalog;
        this.codePage = codePage;
        this.classLoader = classLoader;
        this.procedureHook = procedureHook;
    }

    public static Builder builder(ProgramCatalog catalog) {
        return new Builder(catalog);
    }

    public ProgramCatalog catalog() {
        return catalog;
    }

    public CodePage codePage() {
        return codePage;
    }

    /** 標準入出力を使う実行単位を開く。 */
    public CobolSession openSession() {
        return openSession(RuntimeServices.EMPTY);
    }

    /** task固有のsubsystem serviceを持つ実行単位を開く。 */
    public CobolSession openSession(RuntimeServices services) {
        Objects.requireNonNull(services, "services");
        return new CobolSession(context(ProgramContext.standard()).withServices(services),
                classLoader, catalog.revision());
    }

    /** 標準出力と標準エラーを同じ stream へ捕捉する実行単位を開く。 */
    public CobolSession openSession(OutputStream output) {
        Objects.requireNonNull(output, "output");
        return new CobolSession(context(ProgramContext.standard().withOutput(output)),
                classLoader, catalog.revision());
    }

    /** 捕捉出力とtask固有subsystem serviceを持つ実行単位を開く。 */
    public CobolSession openSession(OutputStream output, RuntimeServices services) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(services, "services");
        return new CobolSession(
                context(ProgramContext.standard().withOutput(output)).withServices(services),
                classLoader, catalog.revision());
    }

    private ProgramContext context(ProgramContext base) {
        return base.withCodePage(codePage).withProgramResolver(catalog)
                .withProcedureHook(procedureHook);
    }

    public static final class Builder {

        private final ProgramCatalog catalog;
        private CodePage codePage = CodePages.DEFAULT;
        private ClassLoader classLoader = defaultClassLoader();
        private ProcedureHook procedureHook = ProcedureHook.NOOP;

        private Builder(ProgramCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        public Builder defaultCodePage(CodePage value) {
            codePage = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder classLoader(ClassLoader value) {
            classLoader = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder procedureHook(ProcedureHook value) {
            procedureHook = Objects.requireNonNull(value, "value");
            return this;
        }

        public CobolRuntime build() {
            return new CobolRuntime(catalog, codePage, classLoader, procedureHook);
        }

        private static ClassLoader defaultClassLoader() {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            return context != null ? context : CobolRuntime.class.getClassLoader();
        }
    }
}
