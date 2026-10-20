package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.cobolonjava.cics.CicsEnvironment;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.InMemoryConversationStore;
import dev.cobolonjava.cics.NonRecoverableTaskBoundaryFactory;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.tn3270.Tn3270Server;
import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.BmsCopyBookResolver;
import dev.cobolonjava.compiler.source.CicsSystemCopyBookResolver;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 翻訳した CICS の COBOL を TN3270 の入口で動かし、x3270 系の台本で動く端末 {@code s3270} から操作する (設計 86 §6)。
 *
 * <p>Zeus の {@code tools/tn3270-test.sh} と同じ方式で、端末は自分で書いた client ではなく、外の 3270 エミュレータである。
 * {@code s3270} が無ければ飛ばす ({@code apt-get install s3270})。
 */
@Tag("V1")
class Tn3270EmulatorTest {

    private static final Path S3270 = Path.of(System.getenv().getOrDefault("S3270", "/usr/bin/s3270"));

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final String BMS = String.join("\n",
            card("GRTSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               TIOAPFX=YES,EXTATT=YES,CTRL=FREEKB", false),
            card("GRTMAP   DFHMDI SIZE=(24,80)", false),
            card("         DFHMDF POS=(1,1),LENGTH=20,INITIAL='GREETING ON TN3270',", true),
            card("               ATTRB=(PROT,BRT),COLOR=TURQUOISE", false),
            card("         DFHMDF POS=(3,1),LENGTH=11,INITIAL='YOUR NAME:',ATTRB=ASKIP", false),
            card("NAME     DFHMDF POS=(3,13),LENGTH=10,ATTRB=(UNPROT,NORM,IC),", true),
            card("               HILIGHT=UNDERLINE", false),
            card("         DFHMDF POS=(3,24),LENGTH=1,ATTRB=ASKIP", false),
            card("MSG      DFHMDF POS=(5,1),LENGTH=40,ATTRB=(PROT,NORM)", false),
            card("CNT      DFHMDF POS=(7,1),LENGTH=4,ATTRB=(PROT,NORM)", false),
            card("TERM     DFHMDF POS=(7,10),LENGTH=4,ATTRB=(PROT,NORM)", false),
            card("         DFHMDF POS=(24,1),LENGTH=30,INITIAL='ENTER=GREET  PF3=END',", true),
            card("               ATTRB=ASKIP", false),
            card("         DFHMSD TYPE=FINAL", false),
            card("         END", false)) + "\n";

    private static final String[] PROCEDURE = {
        "IF EIBCALEN = 0",
        "    MOVE 0 TO WS-COUNT",
        "    PERFORM SHOW-MAP",
        "END-IF",
        "MOVE LK-IN-COUNT TO WS-COUNT",
        "IF EIBAID = DFHPF3",
        "    EXEC CICS SEND TEXT FROM(WS-BYE) ERASE FREEKB END-EXEC",
        "    EXEC CICS RETURN END-EXEC",
        "END-IF",
        "EXEC CICS RECEIVE MAP('GRTMAP') MAPSET('GRTSET')",
        "     INTO(GRTMAPI) RESP(WS-RESP)",
        "END-EXEC",
        "IF WS-RESP = DFHRESP(MAPFAIL)",
        "    MOVE 'PLEASE TYPE A NAME' TO WS-MSG",
        "ELSE",
        "    IF NAMEI = 'ABEND'",
        "        EXEC CICS ABEND ABCODE('GRTA') END-EXEC",
        "    END-IF",
        "    STRING 'HELLO, ' DELIMITED BY SIZE",
        "           NAMEI DELIMITED BY SPACE",
        "           '!' DELIMITED BY SIZE INTO WS-MSG",
        "END-IF",
        "PERFORM SHOW-MAP.",
        "*SHOW-MAP.",
        "ADD 1 TO WS-COUNT",
        "MOVE LOW-VALUES TO GRTMAPO",
        "MOVE WS-MSG TO MSGO",
        "MOVE WS-COUNT TO CNTO",
        "MOVE EIBTRMID TO TERMO",
        "EXEC CICS SEND MAP('GRTMAP') MAPSET('GRTSET')",
        "     FROM(GRTMAPO) ERASE",
        "END-EXEC",
        "MOVE WS-COUNT TO LK-COUNT",
        "EXEC CICS RETURN TRANSID('GRT1') COMMAREA(WS-AREA)",
        "END-EXEC.",
    };

    @TempDir
    Path directory;

    private Tn3270Server server;

    private static final class Loader extends ClassLoader {

        Loader() {
            super(Tn3270EmulatorTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @BeforeEach
    void start() throws IOException {
        assumeTrue(Files.isExecutable(S3270), "s3270 is not installed");
        Files.writeString(directory.resolve("GRTSET.bms"), BMS);
        BmsCopyBookResolver bms = new BmsCopyBookResolver(directory);
        CicsSystemCopyBookResolver system = new CicsSystemCopyBookResolver();
        CopyBookResolver chain = (name, library) -> bms.resolve(name, library)
                .or(() -> system.resolve(name, library));
        CobolCompiler.Result compiled = CobolCompiler.with(chain).compile("GREET.cbl", program());
        assertTrue(compiled.succeeded(), compiled.diagnostics().toString());
        Loader loader = new Loader();
        Class<?> type = loader.define(compiled.className(), compiled.classFile());
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("GREET", () -> {
                    try {
                        return (CobolProgram) type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .build();
        CicsEnvironment environment = CicsEnvironment.unconfigured()
                .withMapsets(BmsMapsetCatalog.of(List.of(BmsParser.parse(BMS))));
        InMemoryConversationStore conversations = new InMemoryConversationStore();
        CicsTaskPolicy policy = new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30));
        CicsTaskCoordinator coordinator = new CicsTaskCoordinator(
                new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(TransId.of("GRT1"),
                        ProgramId.of("GREET"), Duration.ofSeconds(10), 64, 0, 0, 0, true))),
                conversations, new NonRecoverableTaskBoundaryFactory(conversations),
                new CobolCicsTaskProgram(CobolRuntime.builder(catalog).classLoader(loader).build(), 2,
                        environment),
                policy, ConversationId::create, Clock.systemUTC());
        server = new Tn3270Server(coordinator, conversations, CicsTerminalRegistryPort.inMemory(),
                CodePages.DEFAULT, Tn3270Server.Options.defaults().withApplid("COJTEST")
                        .withZone(ZoneOffset.UTC), Clock.systemUTC());
        server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private static String program() {
        StringBuilder out = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. GREET.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "COPY GRTSET.",
                "COPY DFHAID.",
                "01  WS-RESP  PIC S9(8) COMP.",
                "01  WS-COUNT PIC 9(4).",
                "01  WS-MSG   PIC X(40) VALUE SPACES.",
                "01  WS-BYE   PIC X(14) VALUE 'GOODBYE FROM 3'.",
                "01  WS-AREA.",
                "    05 LK-COUNT PIC 9(4).",
                "LINKAGE SECTION.",
                "01  DFHCOMMAREA.",
                "    05 LK-IN-COUNT PIC 9(4).",
                "PROCEDURE DIVISION.")) {
            out.append("       ").append(line).append('\n');
        }
        for (String line : PROCEDURE) {
            // '*' で始まる行は段落名で、A 領域 (8 桁目) に置く
            out.append(line.startsWith("*") ? "       " + line.substring(1) : "           " + line).append('\n');
        }
        return out.toString();
    }

    /** 台本を s3270 に流し、出力を返す。 */
    private String drive(String model, String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(S3270.toString(), "-model", model)
                .redirectErrorStream(true).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(script.replace("$PORT", Integer.toString(server.port())).getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("s3270 did not finish");
        }
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return log;
    }

    private static void assertShown(String log, String expected) {
        assertTrue(log.contains(expected), () -> "'" + expected + "' not shown:\n" + log);
        assertTrue(!log.contains("Timed out"), () -> "a wait timed out:\n" + log);
    }

    @Test
    @DisplayName("TN3270E の端末で TRANSID を打ち、BMS の画面に名前を入れ、疑似会話を PF3 で終える")
    void conversesOverTn3270e() throws Exception {
        String log = drive("3279-2-E", String.join("\n",
                "Connect(localhost:$PORT)",
                "Wait(10,Unlock)",
                "Query(ConnectionState)",
                "String(\"grt1\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(0,0,1,40)",
                "Ascii(2,0,1,40)",
                "Ascii(6,0,1,40)",
                "Query(Cursor)",
                "String(\"ALICE\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(4,0,1,40)",
                "Ascii(6,0,1,10)",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(4,0,1,40)",
                "Clear()",
                "Wait(10,Output)",
                "Ascii(4,0,1,40)",
                "PF(3)",
                "Wait(10,Output)",
                "Ascii(0,0,1,40)",
                "String(\"NOPE\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(0,0,2,80)",
                "Quit()", ""));
        assertShown(log, "data: connected-tn3270e");
        assertShown(log, "data:  GREETING ON TN3270");
        assertShown(log, "data:  YOUR NAME:");
        assertShown(log, "data:  0001");
        // cursor は IC の NAME の最初の位置 (3 行 14 桁、0 起点で 2 13)
        assertShown(log, "data: 2 13");
        assertShown(log, "data:  HELLO, ALICE!");
        assertShown(log, "data:  0002");
        // 端末は NAME を MDT のまま持っていないので (SEND MAP ERASE が消した)、次の Enter は MAPFAIL
        assertShown(log, "data:  PLEASE TYPE A NAME");
        assertShown(log, "data: GOODBYE FROM 3");
        assertShown(log, "Transaction 'NOPE' is not recognized.");
    }

    @Test
    @DisplayName("素の TN3270 でも動き、ABEND は DFHAC2206 を出して疑似会話を外す")
    void abendsOverPlainTn3270() throws Exception {
        String log = drive("3278-2", String.join("\n",
                "Connect(N:localhost:$PORT)",
                "Wait(10,Unlock)",
                "Query(ConnectionState)",
                "String(\"GRT1\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(0,0,1,40)",
                "String(\"ABEND\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(0,0,2,80)",
                "String(\"GRT1\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(6,0,1,10)",
                "Quit()", ""));
        assertShown(log, "data: connected-3270");
        assertShown(log, "data:  GREETING ON TN3270");
        assertShown(log, "failed with abend GRTA");
        // ABEND のあとは疑似会話の途中ではないので、打った GRT1 は新しい会話を始める
        assertShown(log, "data:  0001");
    }

    @Test
    @DisplayName("LU 名を求めた端末は、その末尾 4 文字が EIBTRMID になる")
    void namesTheTerminalAfterTheLu() throws Exception {
        String log = drive("3279-2-E", String.join("\n",
                "Connect(COJLU042@localhost:$PORT)",
                "Wait(10,Unlock)",
                "Query(ConnectionState)",
                "String(\"GRT1\")",
                "Enter()",
                "Wait(10,Output)",
                "Ascii(6,0,1,20)",
                "Quit()", ""));
        assertShown(log, "data:  0001     U042");
    }
}
