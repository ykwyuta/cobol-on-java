package dev.cobolonjava.runtime.program;

import java.util.Locale;

/** COBOL 主プログラムの PARM に続く Language Environment 実行時オプション。 */
public final class BatchParm {

    private BatchParm() {
    }

    public static String applyRuntimeOptions(String parm, ProgramContext context) {
        if (parm == null) {
            return null;
        }
        // Zeus vm/mod.rs と同様、最後のスラッシュを引数と実行時オプションの区切りにする。
        int delimiter = parm.lastIndexOf('/');
        if (delimiter < 0) {
            return parm;
        }
        for (String option : parm.substring(delimiter + 1).split("[ ,]+")) {
            switch (option.toUpperCase(Locale.ROOT)) {
                case "DEBUG" -> context.withDebuggingProcedures(true);
                case "NODEBUG" -> context.withDebuggingProcedures(false);
                default -> { }
            }
        }
        return parm.substring(0, delimiter);
    }
}
