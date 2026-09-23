package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.program.ProgramSupport;
import java.util.List;

/** HLASM の原文 1 本を JVM クラスへ翻訳する入口。 */
public final class HlasmCompiler {

    public HlasmCompiler() {
    }

    public static HlasmCompiler standard() {
        return new HlasmCompiler();
    }

    public Result compile(String fileName, String source) {
        Assembler.Result assembled = Assembler.assemble(fileName, source);
        if (!assembled.succeeded()) {
            return new Result(null, null, null, assembled.diagnostics());
        }
        ObjectModule module = assembled.module();
        // COBOL・PL/I と同じ名前空間に置き、CALL と EXEC PGM= が名前で引けるようにする (P-182)
        String className = ProgramSupport.classNameOf(module.name());
        byte[] classFile = HlasmClassGenerator.generate(className, fileName, source, module.name());
        return new Result(className, classFile, module, List.of());
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
