package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.List;

/** HLASM の原文 1 本を JVM クラスへ翻訳する入口。 */
public final class HlasmCompiler {

    private final SourceLibrary library;

    public HlasmCompiler() {
        this(SourceLibrary.empty());
    }

    public HlasmCompiler(SourceLibrary library) {
        this.library = java.util.Objects.requireNonNull(library);
    }

    public static HlasmCompiler standard() {
        return new HlasmCompiler();
    }

    public static HlasmCompiler withLibrary(SourceLibrary library) {
        return new HlasmCompiler(library);
    }

    public Result compile(String fileName, String source) {
        String expanded;
        try {
            expanded = CopyExpander.expand(source, library);
        } catch (AssemblyException failure) {
            return new Result(null, null, null, List.of(
                    Diagnostic.error(fileName, failure.line(), failure.getMessage())));
        }
        Assembler.Result assembled = Assembler.assemble(fileName, expanded,
                CodePages.DEFAULT, library);
        if (!assembled.succeeded()) {
            return new Result(null, null, null, assembled.diagnostics());
        }
        ObjectModule module = assembled.module();
        // COBOL・PL/I と同じ名前空間に置き、CALL と EXEC PGM= が名前で引けるようにする (P-182)
        String className = ProgramSupport.classNameOf(module.name());
        // 実行時に SYSLIB を再探索しないよう、組立て済みの機械語を生成クラスへ持たせる。
        byte[] classFile = HlasmClassGenerator.generate(className, module);
        return new Result(className, classFile, module, assembled.diagnostics());
    }

    public record Result(String className, byte[] classFile, ObjectModule module,
                         List<Diagnostic> diagnostics) {

        public Result {
            diagnostics = List.copyOf(diagnostics);
            classFile = classFile == null ? null : classFile.clone();
        }

        public boolean succeeded() {
            return className != null && classFile != null && diagnostics.stream()
                    .noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }

        @Override
        public byte[] classFile() {
            return classFile == null ? null : classFile.clone();
        }
    }
}
