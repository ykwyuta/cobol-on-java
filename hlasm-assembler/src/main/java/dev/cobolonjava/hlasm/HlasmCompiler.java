package dev.cobolonjava.hlasm;

import java.util.List;
import java.util.Locale;

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
        String className = "hlasm.generated." + javaName(module.name());
        byte[] classFile = HlasmClassGenerator.generate(className, fileName, source, module.name());
        return new Result(className, classFile, module, List.of());
    }

    private static String javaName(String programName) {
        String normalized = programName.toUpperCase(Locale.ROOT).replace('-', '_');
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            result.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            result.insert(0, '_');
        }
        return result.toString();
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
