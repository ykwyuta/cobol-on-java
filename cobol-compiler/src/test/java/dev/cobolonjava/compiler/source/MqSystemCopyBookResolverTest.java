package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** IBM MQ の写し句を処理系が用意する (設計 88、暫定判断 P-250)。 */
@Tag("V1")
class MqSystemCopyBookResolverTest {

    private static final class GeneratedLoader extends ClassLoader {
        private GeneratedLoader() {
            super(MqSystemCopyBookResolverTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> data, List<String> procedure) {
        List<String> lines = new java.util.ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MQCOPY.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION."));
        lines.addAll(data);
        lines.add("01 WS-N PIC 9(4).");
        lines.add("01 WS-S PIC -9(9).");
        lines.add("PROCEDURE DIVISION.");
        lines.addAll(procedure);
        return CobolCompiler.with(new MqSystemCopyBookResolver())
                .compile("MQCOPY.cbl", lines.stream().map(line -> "       " + line + "\n")
                        .reduce("", String::concat));
    }

    private static String run(CobolCompiler.Result result) {
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        GeneratedLoader loader = new GeneratedLoader();
        Class<?> type = loader.define(result.className(), result.classFile());
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("MQCOPY", () -> {
            try {
                return (CobolProgram) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CobolSession session = CobolRuntime.builder(catalog).classLoader(loader).build().openSession(output)) {
            session.call("MQCOPY");
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("構造体の長さは公開文書の現行版の長さ (MQOD 400、MQMD 364、MQPMO 176、MQGMO 112、MQTM 684) になる")
    void structureLengthsFollowTheDocumentedLayouts() {
        String printed = run(compile(List.of(
                "01 OD. COPY CMQODV.",
                "01 MD. COPY CMQMDV.",
                "01 PMO. COPY CMQPMOV.",
                "01 GMO. COPY CMQGMOV.",
                "01 TM. COPY CMQTML."), List.of(
                "    MOVE LENGTH OF MQOD TO WS-N DISPLAY 'OD ' WS-N",
                "    MOVE LENGTH OF MQMD TO WS-N DISPLAY 'MD ' WS-N",
                "    MOVE LENGTH OF MQPMO TO WS-N DISPLAY 'PMO ' WS-N",
                "    MOVE LENGTH OF MQGMO TO WS-N DISPLAY 'GMO ' WS-N",
                "    MOVE LENGTH OF MQTM TO WS-N DISPLAY 'TM ' WS-N",
                "    GOBACK.")));
        assertEquals(List.of("OD 0400", "MD 0364", "PMO 0176", "GMO 0112", "TM 0684"), printed.lines().toList());
    }

    @Test
    @DisplayName("V の形は既定値を持ち、定数は CMQV の値で比べられる")
    void valueFormsCarryTheDocumentedInitialValues() {
        String printed = run(compile(List.of(
                "01 MD. COPY CMQMDV.",
                "01 GMO. COPY CMQGMOV.",
                "01 K. COPY CMQV."), List.of(
                "    DISPLAY MQMD-STRUCID '/' MQGMO-STRUCID '/' MQFMT-STRING '/'",
                "    MOVE MQMD-ENCODING TO WS-S DISPLAY WS-S",
                "    MOVE MQMD-EXPIRY TO WS-S DISPLAY WS-S",
                "    MOVE MQGMO-MATCHOPTIONS TO WS-S DISPLAY WS-S",
                "    MOVE MQRC-NO-MSG-AVAILABLE TO WS-S DISPLAY WS-S",
                "    IF MQMD-MSGID = MQMI-NONE DISPLAY 'NONE' END-IF",
                "    COMPUTE WS-S = MQGMO-WAIT + MQGMO-CONVERT",
                "                 + MQGMO-FAIL-IF-QUIESCING",
                "    DISPLAY WS-S",
                "    GOBACK.")));
        assertEquals(List.of("MD  /GMO /MQSTR   /", " 000000785", "-000000001", " 000000003",
                " 000002033", "NONE", " 000024577"), printed.lines().toList());
    }

    @Test
    @DisplayName("同じ写し句を 2 つの 01 の下に置き、OF で引き分けられる (CardDemo COPAUA0C の形)")
    void sameMemberTwiceIsQualified() {
        String printed = run(compile(List.of(
                "01 OD-REQUEST. COPY CMQODV.",
                "01 OD-REPLY. COPY CMQODV.",
                "01 K. COPY CMQV."), List.of(
                "    MOVE 'REQ.Q' TO MQOD-OBJECTNAME OF OD-REQUEST",
                "    MOVE 'REPLY.Q' TO MQOD-OBJECTNAME OF OD-REPLY",
                "    DISPLAY MQOD-OBJECTNAME OF OD-REQUEST(1:8) '/'",
                "            MQOD-OBJECTNAME OF OD-REPLY(1:8) '/'",
                "    GOBACK.")));
        assertEquals("REQ.Q   /REPLY.Q /", printed.strip());
    }

    @Test
    @DisplayName("L の形は初期値を書かず、どの行も 72 桁に収まる")
    void listFormsHaveNoValuesAndFitTheMargin() {
        for (String member : List.of("CMQV", "CMQODV", "CMQODL", "CMQMDV", "CMQMDL", "CMQPMOV", "CMQPMOL",
                "CMQGMOV", "CMQGMOL", "CMQTMV", "CMQTML")) {
            String text = MqSystemCopyBookResolver.text(member).orElseThrow();
            for (String line : text.lines().toList()) {
                assertTrue(line.length() <= 72 && line.startsWith("          "), member + ": " + line);
            }
            if (member.endsWith("L")) {
                assertFalse(text.contains("VALUE"), member);
            }
        }
        assertTrue(new MqSystemCopyBookResolver().resolve("CMQSDV", null).isEmpty(),
                "MQSUB をまだ持たないので MQSD の写し句は置かない");
    }
}
