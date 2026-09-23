package dev.cobolonjava.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.ProcessStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 効かない翻訳オプションを黙って受理しない (要件 FR-181、暫定判断 P-023)。
 *
 * <p>以前はどのオプションも記録するだけで通していた。z/OS probe の変種 (NUMPROC(PFD)、
 * ARITH(EXTEND)) をこの処理系で翻訳しても、変種どうしに差が出なかったことで見つかった。
 */
class OptionSupportTest {

    private static final String PROGRAM = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. OPTS.
                   PROCEDURE DIVISION.
                       GOBACK.
            """;

    private static CobolCompiler.Result compile(String options) {
        return CobolCompiler.standard().withOptions(ProcessStatement.parse(options))
                .compile("OPTS.cbl", PROGRAM);
    }

    private static boolean warns(CobolCompiler.Result result, String text) {
        return result.diagnostics().stream().anyMatch(d -> d.severity()
                == Diagnostic.Severity.WARNING && d.message().contains(text));
    }

    @Test
    @DisplayName("計算する値を変えるのに実装していない値は断る")
    void refusesResultChangingValuesThatAreNotImplemented() {
        for (String option : new String[] {"ARITH(EXTEND)", "NUMPROC(PFD)", "NUMPROC(MIG)",
                "TRUNC(BIN)", "TRUNC(OPT)", "APOST", "FLOAT(BINARY)", "CODEPAGE(1399)"}) {
            CobolCompiler.Result result = compile(option);
            assertFalse(result.succeeded(), option);
            assertTrue(result.diagnostics().toString().contains("not supported yet"),
                    () -> option + ": " + result.diagnostics());
        }
    }

    @Test
    @DisplayName("この処理系の振る舞いと同じ値と、効いているオプションは黙って通す")
    void acceptsImplementedValuesSilently() {
        CobolCompiler.Result result = compile(
                "ARITH(COMPAT),TRUNC(STD),NUMPROC(NOPFD),FLOAT(HEX),CODEPAGE(1047),"
                        + "SSRANGE,DYNAM,QUOTE,SOURCEFORMAT(FIXED)");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("結果を変えないものは警告して通す。Bank-of-Z の PROCESS 文も通る")
    void warnsAboutOptionsWithoutEffect() {
        CobolCompiler.Result listing = compile("LIST,MAP,XREF,FLAG(I),RENT");
        assertTrue(listing.succeeded(), () -> listing.diagnostics().toString());
        assertTrue(warns(listing, "LIST"), () -> listing.diagnostics().toString());

        CobolCompiler.Result bank = compile("CICS,NODYNAM,NSYMBOL(NATIONAL),TRUNC(STD)");
        assertTrue(bank.succeeded(), () -> bank.diagnostics().toString());
        assertTrue(warns(bank, "P-032"), () -> bank.diagnostics().toString());

        CobolCompiler.Result advancing = compile("ADV");
        assertTrue(warns(advancing, "P-063"), () -> advancing.diagnostics().toString());
    }

    @Test
    @DisplayName("知らないオプションは警告する")
    void warnsAboutUnknownOptions() {
        CobolCompiler.Result result = compile("FROBNICATE");
        assertTrue(result.succeeded());
        assertTrue(warns(result, "unknown compiler option FROBNICATE"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("ソースの CBL 文に書いた指定も同じに診断する")
    void checksOptionsWrittenInTheSource() {
        CobolCompiler.Result result = CobolCompiler.standard().compile("OPTS.cbl",
                "       CBL ARITH(EXTEND)\n" + PROGRAM);
        assertFalse(result.succeeded());
    }
}
