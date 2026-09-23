package dev.cobolonjava.verify.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.dli.ImsRegion;
import dev.cobolonjava.ims.dli.InMemoryMessageQueue;
import dev.cobolonjava.ims.dli.InputMessage;
import dev.cobolonjava.ims.dli.OutputMessage;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.pli.PliCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bank-of-Z の IMS の PL/I 資産 {@code IBLOGIN.pli} を、同じリポジトリの DBD (CUSTOMER) と
 * PSB (IBLOGIN) で原文のまま<b>動かす</b>。これまでは翻訳できることしか測っていなかった。
 *
 * <p>電文は COBOL 版 ({@code IBLOGIN1.cbl}) と同じ形 (取引コード 8 桁、顧客番号 9 桁、パスワード
 * 16 桁) で送る。PL/I 版は PLITDLI の電文の長さの欄が 4 byte (LLLL) であることに合わせて
 * {@code LL_IN}・{@code ZZ_IN}・{@code TRAN_CODE CHAR(9)}・{@code IN_CUSTID CHAR(10)} と宣言しており、
 * 同じ電文を同じ位置で読む。
 */
class BankOfZPliLoginTest {

    private static final Path IMS = Path.of("..", "reference", "Bank-of-Z-main", "src", "base",
            "ims");

    @BeforeEach
    void requireBankOfZ() {
        Assumptions.assumeTrue(Files.isDirectory(IMS), "Bank-of-Z is not checked out");
    }

    @Test
    @DisplayName("パスワード違い・顧客なし・成功・ログイン済みの 4 通に、それぞれの応答を返し、顧客を更新する")
    void answersEachLoginMessage() throws Exception {
        DatabaseDefinition dbd = DbdParser.parse(Files.readString(IMS.resolve("DBD/CUSTOMER.asm")));
        HierarchicalDatabase database = new HierarchicalDatabase(dbd);
        database.insert(null, dbd.root(), customer(16918, "password", '0'));
        InMemoryMessageQueue queue = new InMemoryMessageQueue()
                .offer(login("LTERM001", "16918", "wrong"))
                .offer(login("LTERM002", "99999", "password"))
                .offer(login("LTERM003", "16918", "password"))
                .offer(login("LTERM004", "16918", "password"));
        ImsRegion region = new ImsRegion(PsbParser.parse(Files.readString(
                IMS.resolve("PSB/IBLOGIN.asm"))), List.of(database), CodePages.DEFAULT, true,
                queue, Clock.systemUTC());

        PliCompiler.Result compiled = PliCompiler.standard().compile("IBLOGIN.pli",
                Files.readString(IMS.resolve("pli/IBLOGIN.pli"), StandardCharsets.UTF_8));
        assertTrue(compiled.succeeded(), () -> compiled.diagnostics().toString());
        CobolProgram program = (CobolProgram) new Loader()
                .define(compiled.className(), compiled.classFile())
                .getDeclaredConstructor().newInstance();
        ProgramCatalog catalog = region.register(ProgramCatalog.builder()
                .cobolProgram("IBLOGIN", program.programSignature(), () -> program)).build();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession(output)) {
            session.call("IBLOGIN", region.programArguments(program.programSignature()));
        }
        region.finish(true);

        // 応答は ISRT の LLLL (= SIZE(OUTPUT_AREA) - 2 = 36) から本文 32 桁を取る
        List<OutputMessage> sent = queue.sent();
        assertEquals(List.of("LTERM001", "LTERM002", "LTERM003", "LTERM004"),
                sent.stream().map(OutputMessage::destination).toList());
        assertEquals(List.of("PASSWORD INVALID", "CUSTOMER DOES NOT EXIST", "LOGIN SUCCESSFUL",
                        "CUSTOMER ALREADY LOGGED IN"),
                sent.stream().map(message -> CodePages.DEFAULT.decode(message.segments().get(0)))
                        .map(String::stripTrailing).toList());
        assertTrue(sent.stream().allMatch(message -> message.segments().get(0).length == 32));

        // REPL で状態が 1 になり、最終ログインが資産の形 (yyyy_MM_dd HH:mm:ss:SSS) で入る
        String segment = CodePages.DEFAULT.decode(
                database.roots().get(0).data()).substring(4);
        assertEquals('1', segment.charAt(238 - 4));
        assertTrue(segment.substring(256 - 4).matches(
                "\\d{4}_\\d{2}_\\d{2} \\d{2}:\\d{2}:\\d{2}:\\d{3}"), segment.substring(256 - 4));

        List<String> lines = output.toString(StandardCharsets.UTF_8).lines()
                .map(line -> line.replace("\f", "")).toList();
        // POINTER は HEX (8 桁) で書く
        assertTrue(lines.stream().anyMatch(line -> line.matches("LTERMPCB_PTR = {10}[0-9A-F]{8}")),
                () -> String.join("\n", lines));
        // DB PCB の DBD 名は IMS が置いたもの。BASED の宣言で消さない
        assertTrue(lines.contains(String.format("%-24s%s", "DBNAME =", "CUSTOMER")));
        // PIC'(9)9' へ入れた顧客番号は 9 桁の数字になる。構造は要素ごとに tab へ並ぶ
        assertTrue(lines.contains(String.format("%-24s%s", "CUSTID_NUM = ", "000016918")));
        // 5 つ目の要素 EQ は 97 桁目、CUSTID (FIXED BIN(31)) の番の tab 121 は行幅を超えるので次の行
        int ssa = lines.indexOf(String.format("%-24s%-24s%-24s%-24s%s", "SSA =", "CUSTOMER", "(",
                "CUSTID  ", "EQ"));
        assertTrue(ssa >= 0, () -> String.join("\n", lines));
        assertEquals(String.format("%14s", "16918"), lines.get(ssa + 1).substring(0, 14));
        assertTrue(lines.contains("NO MORE INPUT MESSAGE FROM QUEUE ...."));
    }

    private static InputMessage login(String terminal, String customer, String password) {
        return new InputMessage(terminal, List.of(CodePages.DEFAULT.encode(
                "IBLOGIN " + String.format("%-9s%-16s", customer, password))));
    }

    /** CUSTOMER の 279 byte。CUSTID は 4 byte の 2 進 (DATATYPE=INT)、ほかは文字。 */
    private static byte[] customer(int id, String password, char status) {
        ByteBuffer segment = ByteBuffer.allocate(279);
        segment.putInt(id);
        segment.put(CodePages.DEFAULT.encode(String.format(
                "%-50s%-50s%-80s%-25s%-2s%-15s%-12s%s%-16s%s%-23s",
                "SMITH", "JOHN", "1 HIGH STREET", "LEEDS", "WY", "LS1 1AA", "555-0100",
                status, password, "P", "2026-01-01 00:00:00.000")));
        return segment.array();
    }

    private static final class Loader extends ClassLoader {
        Loader() {
            super(BankOfZPliLoginTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
