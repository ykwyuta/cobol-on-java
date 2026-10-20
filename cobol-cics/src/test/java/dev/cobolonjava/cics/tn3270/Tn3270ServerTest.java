package dev.cobolonjava.cics.tn3270;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.InMemoryConversationStore;
import dev.cobolonjava.cics.NonRecoverableTaskBoundaryFactory;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot.FieldState;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Telnet を byte で話す小さな端末で入口を動かす。外の 3270 エミュレータとの照合は cobol-compiler の
 * {@code Tn3270EmulatorTest} が s3270 で行う。ここでは素の TN3270 の取り決めと、端末へ出す task の画面を確かめる。
 */
class Tn3270ServerTest {

    private static final int IAC = 255;

    private final BlockingQueue<CicsTaskContext> tasks = new LinkedBlockingQueue<>();
    private final CicsTerminalRegistryPort terminals = CicsTerminalRegistryPort.inMemory();
    private Tn3270Server server;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private static final BmsScreenSnapshot MAP = new BmsScreenSnapshot("SET", "MAP", 24, 80, List.of(
            new FieldState(Optional.empty(), 1, new Position(1, 1), 4, EnumSet.of(BasicAttribute.ASKIP),
                    Optional.empty(), Optional.empty(), "NAME", false),
            new FieldState(Optional.of("NAME"), 1, new Position(1, 6), 8,
                    EnumSet.of(BasicAttribute.UNPROT, BasicAttribute.IC), Optional.empty(), Optional.empty(),
                    "", false)),
            6, true, false);

    @BeforeEach
    void start() throws IOException {
        CicsTaskProgramPort program = (definition, payload, task, syncpoints) -> {
            tasks.add(task);
            return new TaskCompletion(Optional.of(TransId.of("TST1")), CicsPayload.ofCommarea(new byte[] {1}))
                    .withScreen(Optional.of(new CicsTerminalScreen.MapScreen(MAP)));
        };
        InMemoryConversationStore conversations = new InMemoryConversationStore();
        CicsTaskCoordinator coordinator = new CicsTaskCoordinator(
                new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(TransId.of("TST1"),
                        ProgramId.of("TESTPGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true))),
                conversations, new NonRecoverableTaskBoundaryFactory(conversations), program,
                new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30)), ConversationId::create,
                Clock.systemUTC());
        server = new Tn3270Server(coordinator, conversations, terminals, CodePages.IBM_037,
                Tn3270Server.Options.defaults().withZone(ZoneOffset.UTC), Clock.systemUTC());
        server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
        socket.setSoTimeout(10_000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    @AfterEach
    void stop() throws IOException {
        if (socket != null) {
            socket.close();
        }
        server.close();
    }

    private void expect(int... bytes) throws IOException {
        for (int b : bytes) {
            assertEquals(b, in.read());
        }
    }

    private void send(int... bytes) throws IOException {
        for (int b : bytes) {
            out.write(b);
        }
        out.flush();
    }

    /** 素の TN3270 で取り決める。TN3270E は断る。 */
    private void negotiatePlain() throws IOException {
        expect(IAC, 253, 40);
        send(IAC, 252, 40);
        expect(IAC, 253, 24);
        send(IAC, 251, 24);
        expect(IAC, 250, 24, 1, IAC, 240);
        out.write(new byte[] {(byte) IAC, (byte) 250, 24, 0});
        out.write("IBM-3278-2".getBytes(StandardCharsets.US_ASCII));
        send(IAC, 240);
        expect(IAC, 253, 25, IAC, 251, 25, IAC, 253, 0, IAC, 251, 0);
        send(IAC, 251, 25, IAC, 253, 25, IAC, 251, 0, IAC, 253, 0);
    }

    /** IAC EOR までの 1 つの記録。 */
    private byte[] record() throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed");
            }
            if (b == IAC) {
                int next = in.read();
                if (next == 239) {
                    return data.toByteArray();
                }
                data.write(next);
            } else {
                data.write(b);
            }
        }
    }

    private void sendRecord(byte[] data) throws IOException {
        out.write(data);
        send(IAC, 239);
    }

    @Test
    @DisplayName("書式なしの画面に打った TRANSID で task を起こし、次の入力は SBA の番地を field の名前に戻して渡す")
    void startsTransactionAndReturnsFieldInput() throws Exception {
        negotiatePlain();
        // 接続すると、消した画面とキーボードを開ける WCC が来る
        assertArrayEquals(new byte[] {(byte) 0xF5, (byte) 0xC3}, record());

        ByteArrayOutputStream enter = new ByteArrayOutputStream();
        enter.writeBytes(new byte[] {0x7D, 0x40, (byte) 0xC5});
        enter.writeBytes(CodePages.IBM_037.encode("  tst1 extra"));
        sendRecord(enter.toByteArray());

        CicsTaskContext first = tasks.poll(10, TimeUnit.SECONDS);
        assertEquals(TransId.of("TST1"), first.transactionId());
        assertEquals(BmsAid.ENTER, first.terminalInput().orElseThrow().aid());
        // 端末制御の RECEIVE が読む、打たれたとおりの文字 (P-200)
        assertEquals(Optional.of("  tst1 extra"), first.terminalInput().orElseThrow().text());
        String terminal = first.terminalId().orElseThrow();
        byte[] screen = record();
        assertEquals(0xF5, screen[0] & 0xFF);

        // NAME の属性は 5 番地、データは 6 番地 (X'40C6')
        ByteArrayOutputStream typed = new ByteArrayOutputStream();
        typed.writeBytes(new byte[] {0x7D, 0x40, (byte) 0xC9, 0x11, 0x40, (byte) 0xC6});
        typed.writeBytes(CodePages.IBM_037.encode("BOB"));
        sendRecord(typed.toByteArray());

        CicsTaskContext second = tasks.poll(10, TimeUnit.SECONDS);
        BmsTerminalInput input = second.terminalInput().orElseThrow();
        assertEquals(List.of(new BmsTerminalInput.FieldInput("NAME", 1, "BOB")), input.fields());
        assertEquals(Optional.empty(), input.text(), "map の画面からの入力は書式の無い文字として渡さない");
        assertEquals(9, input.cursorOffset());
        assertEquals(Optional.of(terminal), second.terminalId());
        // 直前の画面は会話から RECEIVE MAP へ渡る
        assertEquals(Optional.of(MAP), second.screen());
        record();
    }

    @Test
    @DisplayName("端末へ出す task (START TERMID) が置いた画面は、入力を待つ間に端末へ送る")
    void deliversScreensPutByTerminalTasks() throws Exception {
        negotiatePlain();
        record();
        String terminal = registered();
        // 接続の側が入力を待つ前の短い間は、接続が端末を借りている。借りられるまで待つ (負荷が高いと空振りした)
        var owner = terminals.find(terminal, java.time.Instant.now()).orElseThrow().owner();
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var attempt = terminals.lease(terminal, owner, Duration.ofSeconds(5), java.time.Instant.now());
        while (attempt.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            attempt = terminals.lease(terminal, owner, Duration.ofSeconds(5), java.time.Instant.now());
        }
        var lease = attempt.orElseThrow();
        terminals.setScreen(lease, new CicsTerminalScreen.TextScreen("STARTED", true, false),
                java.time.Instant.now());
        terminals.release(lease, java.time.Instant.now());

        byte[] screen = record();
        assertArrayEquals(CodePages.IBM_037.encode("STARTED"), java.util.Arrays.copyOfRange(screen, 5, 12));
    }

    /** task を 1 つ起こして EIBTRMID を知る。 */
    private String registered() throws Exception {
        ByteArrayOutputStream enter = new ByteArrayOutputStream();
        enter.writeBytes(new byte[] {0x7D, 0x40, 0x40});
        enter.writeBytes(CodePages.IBM_037.encode("TST1"));
        sendRecord(enter.toByteArray());
        String id = tasks.poll(10, TimeUnit.SECONDS).terminalId().orElseThrow();
        record();
        return id;
    }

    @Test
    @DisplayName("TN3270E の端末は FUNCTIONS を空の一覧で取り決め、記録に 5 byte の header が付く")
    void negotiatesTn3270eWithoutFunctions() throws Exception {
        expect(IAC, 253, 40);
        send(IAC, 251, 40);
        expect(IAC, 250, 40, 8, 2, IAC, 240);
        out.write(new byte[] {(byte) IAC, (byte) 250, 40, 2, 7});
        out.write("IBM-3278-2-E".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        out.write("LUTEST01".getBytes(StandardCharsets.US_ASCII));
        send(IAC, 240);
        ByteArrayOutputStream is = new ByteArrayOutputStream();
        for (int i = 0; i < 5 + 12 + 1 + 8 + 2; i++) {
            is.write(in.read());
        }
        assertEquals("IBM-3278-2-E" + (char) 1 + "LUTEST01",
                new String(is.toByteArray(), 5, 21, StandardCharsets.US_ASCII));
        // BIND-IMAGE と RESPONSES を求めると、空の一覧を求め返す
        send(IAC, 250, 40, 3, 7, 0, 2, IAC, 240);
        expect(IAC, 250, 40, 3, 7, IAC, 240);
        send(IAC, 250, 40, 3, 4, IAC, 240);

        byte[] first = record();
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0, (byte) 0xF5, (byte) 0xC3}, first);
        assertTrue(terminals.find("ST01", java.time.Instant.now()).isPresent());
    }
}
