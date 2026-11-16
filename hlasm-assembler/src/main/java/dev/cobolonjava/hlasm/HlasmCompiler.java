package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.source.JavaSource;
import dev.cobolonjava.source.ProgramOutput;
import java.io.IOException;
import java.nio.file.Path;
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
        Assembly assembly = assemble(fileName, source);
        byte[] classFile = assembly.succeeded() ? assembly.classFile() : null;
        return new Result(assembly.className(), classFile, assembly.module(), assembly.diagnostics());
    }

    public Assembly assemble(String fileName, String source) {
        CopyExpander.ProgramSource expanded;
        try {
            expanded = CopyExpander.expand(source, library);
        } catch (AssemblyException failure) {
            return new Assembly(null, null, List.of(
                    Diagnostic.error(fileName, failure.line(), failure.getMessage())));
        }
        String objectName = fileName.replaceFirst("\\.[^./\\\\]+$", "") + ".class";
        Assembler.Result assembled = Assembler.assemble(fileName, expanded.text(),
                CodePages.DEFAULT, library, expanded.libraryLines(), objectName);
        if (!assembled.succeeded()) {
            return new Assembly(null, null, assembled.diagnostics());
        }
        ObjectModule module = assembled.module();
        // COBOL・PL/I と同じ名前空間に置き、CALL と EXEC PGM= が名前で引けるようにする (P-182)
        String className = ProgramSupport.classNameOf(module.name());
        return new Assembly(className, module, assembled.diagnostics());
    }

    public record Assembly(String className, ObjectModule module, List<Diagnostic> diagnostics) {
        public Assembly {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean succeeded() {
            return module != null && className != null;
        }

        /** 組み立て済みのモジュールを出力する。 */
        public void write(Path output, ProgramOutput format) throws IOException {
            if (!succeeded()) {
                throw new IllegalStateException("cannot write a failed assembly");
            }
            if (format == ProgramOutput.JAVA) {
                JavaSource.write(output, className, HlasmJavaSource.generate(className, module));
            } else {
                format.write(output, className, classFile());
            }
        }

        private byte[] classFile() {
            return HlasmClassGenerator.generate(className, module);
        }
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
