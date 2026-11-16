package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.source.JavaSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 組み立て済み機械語と再配置情報から Java クラスを生成する。 */
final class HlasmJavaSource {

    private HlasmJavaSource() {
    }

    static String generate(String className, ObjectModule module) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        String objectCode = Base64.getMimeEncoder(76, new byte[] {'\n'})
                .encodeToString(module.text()) + "\n";
        StringBuilder tableMethods = new StringBuilder();
        String relocations = list(module.relocations().stream()
                .map(field -> "new Relocation(%s, %s)"
                        .formatted(offset(field.offset()), field.length())).toList(),
                "Relocation", "relocations", tableMethods);
        String externals = list(module.externals().stream()
                .map(field -> "new External(%s, %s, %s)"
                        .formatted(offset(field.offset()), field.length(), JavaSource.literal(field.name())))
                .toList(), "External", "externals", tableMethods);
        return """
                package %s;

                import dev.cobolonjava.hlasm.HlasmRuntime;
                import dev.cobolonjava.hlasm.ObjectModule;
                import dev.cobolonjava.hlasm.ObjectModule.External;
                import dev.cobolonjava.hlasm.ObjectModule.Relocation;
                import dev.cobolonjava.runtime.interop.ProgramSignature;
                import dev.cobolonjava.runtime.procedure.ProcedureManifest;
                import dev.cobolonjava.runtime.program.CobolProgram;
                import dev.cobolonjava.runtime.program.ProgramContext;
                import dev.cobolonjava.runtime.storage.DataView;
                import dev.cobolonjava.runtime.storage.Storage;
                import java.util.Base64;
                import java.util.List;

                public final class %s implements CobolProgram {
                    private static final String MODULE_NAME = %s;
                    private static final int ENTRY_OFFSET = %s;

                    // Assembled machine code, stored as Base64.
                    private static final String OBJECT_CODE = %s;

                    private static final List<Relocation> RELOCATIONS = %s;
                    private static final List<External> EXTERNALS = %s;

                    private static final ObjectModule MODULE = new ObjectModule(
                            MODULE_NAME, Base64.getMimeDecoder().decode(OBJECT_CODE),
                            ENTRY_OFFSET, RELOCATIONS, EXTERNALS);

                    @Override
                    public byte[] initialStorage() {
                        return MODULE.text();
                    }

                    @Override
                    public String name() {
                        return MODULE_NAME;
                    }

                    @Override
                    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                        HlasmRuntime.executeLoaded(MODULE, storage, context, arguments,
                                %s.class.getClassLoader());
                    }

                    @Override
                    public ProgramSignature programSignature() {
                        return HlasmRuntime.signatureForModule(MODULE_NAME);
                    }

                    @Override
                    public ProcedureManifest procedureManifest() {
                        return HlasmRuntime.procedureManifestForModule(MODULE_NAME);
                    }

                    public static void main(String[] args) {
                        System.exit(new %s().runMain());
                    }
                %s}
                """.formatted(ProgramSupport.GENERATED_PACKAGE, simpleName, JavaSource.literal(module.name()),
                offset(module.entryOffset()), JavaSource.textExpression(objectCode, 12), relocations,
                externals, simpleName, simpleName, tableMethods);
    }

    private static String list(List<String> entries, String type, String name, StringBuilder methods) {
        // 大きい表はクラスの静的初期化子一つに収まる JVM の命令数を超える。
        int rowsPerMethod = 512;
        if (entries.size() <= rowsPerMethod) {
            return listExpression(entries, " ".repeat(12));
        }
        List<String> calls = new ArrayList<>();
        for (int start = 0; start < entries.size(); start += rowsPerMethod) {
            int end = Math.min(start + rowsPerMethod, entries.size());
            String method = name + "Part" + (calls.size() + 1);
            calls.add(method + "()");
            methods.append("""

                        private static List<%s> %s() {
                            return %s;
                        }
                    """.formatted(type, method, listExpression(entries.subList(start, end),
                    " ".repeat(16))));
        }
        return "java.util.stream.Stream.of(\n            " + String.join(",\n            ", calls)
                + ")\n            .flatMap(List::stream).toList()";
    }

    private static String listExpression(List<String> entries, String indent) {
        return entries.isEmpty() ? "List.of()" : "List.of(\n" + indent
                + String.join(",\n" + indent, entries) + ")";
    }

    private static String offset(int offset) {
        return "0x" + Integer.toHexString(offset);
    }
}
