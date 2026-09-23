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
        List<Diagnostic> layout = structureDiagnostics(fileName, parsed.program());
        if (!layout.isEmpty()) {
            return new Result(null, null, null, null, layout);
        }
        // COBOL と同じ名前空間に置く。ホストでは COBOL と PL/I のロードモジュールが同じロード
        // ライブラリに入り、CALL も EXEC PGM= も言語を問わず名前で引く (暫定判断 P-182 の解消)
        String className = ProgramSupport.classNameOf(parsed.program().name());
        byte[] classFile = PliClassGenerator.generate(className, fileName, preprocessed.source());
        ProgramSignature signature = PliRuntime.signature(fileName, preprocessed.source());
        ProcedureManifest procedures = PliRuntime.procedureManifest(fileName, preprocessed.source());
        return new Result(className, classFile, signature, procedures, List.of());
    }

    /**
     * 構造の配置を翻訳の時点で確かめる。この処理系がまだ置けない要素 (構造の中の POINTER、
     * 8 の倍数でない UNALIGNED のビット列) を、実行まで待たずに断る (P-184)。
     */
    private static List<Diagnostic> structureDiagnostics(String fileName,
                                                         PliSyntax.Program program) {
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        java.util.function.Consumer<List<PliSyntax.Stmt>> check = new java.util.function.Consumer<>() {
            @Override
            public void accept(List<PliSyntax.Stmt> statements) {
                for (PliSyntax.Stmt statement : statements) {
                    visit(statement);
                }
            }

            private void visit(PliSyntax.Stmt statement) {
                if (statement instanceof PliSyntax.Declare declare) {
                    List<PliSyntax.Decl> decls = declare.declarations();
                    for (int i = 0; i < decls.size(); i++) {
                        PliSyntax.Decl decl = decls.get(i);
                        if (decl.level() > 0 && decl.type() == PliSyntax.Type.GROUP) {
                            int end = i + 1;
                            while (end < decls.size() && decls.get(end).level() > decl.level()) {
                                end++;
                            }
                            try {
                                StructureMapping.map(decls.subList(i, end));
                            } catch (IllegalArgumentException unsupported) {
                                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                                        fileName, 1, 1, unsupported.getMessage()));
                            }
                            i = end - 1;
                        }
                    }
                } else if (statement instanceof PliSyntax.If branch) {
                    visit(branch.whenTrue());
                    if (branch.whenFalse() != null) visit(branch.whenFalse());
                } else if (statement instanceof PliSyntax.Loop loop) {
                    accept(loop.body());
                } else if (statement instanceof PliSyntax.IterativeLoop loop) {
                    accept(loop.body());
                } else if (statement instanceof PliSyntax.Block block) {
                    accept(block.body());
                } else if (statement instanceof PliSyntax.OnEndFile handler) {
                    accept(handler.handler());
                }
            }
        };
        check.accept(program.body());
        program.procedures().values().forEach(procedure -> check.accept(procedure.body()));
        return diagnostics;
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
