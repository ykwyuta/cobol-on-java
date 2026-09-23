package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import java.util.List;
import java.util.Objects;

/** PL/I ソース 1 本を JVM クラスへ翻訳する入口。 */
public final class PliCompiler {

    private final PliPreprocessor preprocessor;

    public PliCompiler(PliPreprocessor preprocessor) {
        this.preprocessor = Objects.requireNonNull(preprocessor, "preprocessor");
    }

    public static PliCompiler standard() {
        return new PliCompiler(PliPreprocessor.withoutIncludes());
    }

    public Result compile(String fileName, String source) {
        PliPreprocessor.Result preprocessed = preprocessor.process(fileName, source);
        if (!preprocessed.succeeded()) {
            return new Result(null, null, null, null, preprocessed.diagnostics());
        }
        PliSyntax.ParseResult parsed = PliSyntax.parse(fileName, preprocessed.source());
        if (!parsed.succeeded()) {
            return new Result(null, null, null, null, parsed.diagnostics());
        }
        // COBOL と同じ名前空間に置く。ホストでは COBOL と PL/I のロードモジュールが同じロード
        // ライブラリに入り、CALL も EXEC PGM= も言語を問わず名前で引く (暫定判断 P-182 の解消)
        String className = ProgramSupport.classNameOf(parsed.program().name());
        byte[] classFile = PliClassGenerator.generate(className, fileName, preprocessed.source());
        ProgramSignature signature = PliRuntime.signature(fileName, preprocessed.source());
        ProcedureManifest procedures = PliRuntime.procedureManifest(fileName, preprocessed.source());
        return new Result(className, classFile, signature, procedures, List.of());
    }

    public record Result(String className, byte[] classFile, ProgramSignature programSignature,
                         ProcedureManifest procedureManifest,
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
