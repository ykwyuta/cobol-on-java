package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.source.JavaSource;

/** 展開済み PL/I を実行する Java クラスを生成する。 */
final class PliJavaSource {

    private PliJavaSource() {
    }

    static String generate(String className, String fileName, String source) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        return """
                package %s;

                import dev.cobolonjava.pli.PliRuntime;
                import dev.cobolonjava.runtime.interop.ProgramSignature;
                import dev.cobolonjava.runtime.procedure.ProcedureManifest;
                import dev.cobolonjava.runtime.program.CobolProgram;
                import dev.cobolonjava.runtime.program.ProgramContext;
                import dev.cobolonjava.runtime.storage.DataView;
                import dev.cobolonjava.runtime.storage.Storage;

                public final class %s implements CobolProgram {
                    private static final String SOURCE_FILE = %s;

                    // PL/I source after preprocessing, including expanded INCLUDE members.
                    private static final String PLI_SOURCE = %s;

                    @Override
                    public byte[] initialStorage() {
                        return new byte[0];
                    }

                    @Override
                    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                        PliRuntime.execute(SOURCE_FILE, PLI_SOURCE, context, arguments,
                                %s.class.getClassLoader());
                    }

                    @Override
                    public ProgramSignature programSignature() {
                        return PliRuntime.signature(SOURCE_FILE, PLI_SOURCE);
                    }

                    @Override
                    public ProcedureManifest procedureManifest() {
                        return PliRuntime.procedureManifest(SOURCE_FILE, PLI_SOURCE);
                    }

                    public static void main(String[] args) {
                        System.exit(new %s().runMain());
                    }
                }
                """.formatted(ProgramSupport.GENERATED_PACKAGE, simpleName, JavaSource.literal(fileName),
                JavaSource.textExpression(source, 12), simpleName, simpleName);
    }
}
