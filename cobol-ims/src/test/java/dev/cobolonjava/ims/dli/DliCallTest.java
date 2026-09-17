package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DL/I の DB 呼び出し (設計 78 §3、暫定判断 P-154)。
 *
 * <p>DL/I には外の基準が無い (P-099)。ここで固定しているのは公開仕様の説明から起こした振る舞いであり、
 * 実機と突き合わせていない。<b>自分で書いた規則は、自分の試験では破れない</b> (覚え書き 3)。
 */
@Tag("V1")
class DliCallTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    /** CUST (キー 4、重ならない) の下に ACCT (キー 4、重なってよい、FIRST) と NOTE (キー無し)。 */
    private static String dbd(String access) {
        return deck(
                card("         DBD   NAME=BANKDB,ACCESS=" + access),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
                card("         FIELD NAME=CITY,BYTES=6,START=5"),
                card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=8,RULES=(LLL,FIRST)"),
                card("         FIELD NAME=(ACCTNO,SEQ,M),BYTES=4,START=1"),
                card("         FIELD NAME=BAL,BYTES=4,START=5,TYPE=F"),
                card("         SEGM  NAME=NOTE,PARENT=CUST,BYTES=6"),
                card("         DBDGEN"));
    }

    private static String psb(String processingOptions, int keyLength) {
        return deck(
                card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=" + processingOptions + ",KEYLEN=" + keyLength),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         SENSEG NAME=ACCT,PARENT=CUST"),
                card("         SENSEG NAME=NOTE,PARENT=CUST"),
                card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL"));
    }

    /** 1 つの PCB へ DL/I を呼ぶ。 */
    private static final class Pcb {

        private final ImsRegion region;
        private final DataView mask;
        private final Storage io = Storage.allocate(64);

        Pcb(String access, String processingOptions) {
            region = new ImsRegion(PsbParser.parse(psb(processingOptions, 8)),
                    List.of(new HierarchicalDatabase(DbdParser.parse(dbd(access)))), EBCDIC);
            mask = region.programArguments()[0];
        }

        String call(String function, DataView area, byte[]... ssas) {
            List<DataView> arguments = new ArrayList<>();
            arguments.add(Storage.wrap(text(pad(function, 4))).whole());
            arguments.add(mask);
            if (area != null) {
                arguments.add(area);
            }
            for (byte[] ssa : ssas) {
                arguments.add(Storage.wrap(ssa).whole());
            }
            region.call(arguments);
            return status();
        }

        String get(String function, byte[]... ssas) {
            io.whole().fill((byte) 0x40);
            return call(function, io.whole(), ssas);
        }

        String insert(byte[] data, byte[]... ssas) {
            return call("ISRT", Storage.wrap(data.clone()).whole(), ssas);
        }

        String replace(byte[] data) {
            return call("REPL", Storage.wrap(data.clone()).whole());
        }

        String delete() {
            return call("DLET", io.whole());
        }

        String read(int length) {
            return EBCDIC.decode(io.view(0, length).toByteArray());
        }

        int balance() {
            return ByteBuffer.wrap(io.view(4, 4).toByteArray()).getInt();
        }

        String status() {
            return maskText(DatabasePcb.STATUS, 2);
        }

        String level() {
            return maskText(DatabasePcb.LEVEL, 2);
        }

        String segmentName() {
            return maskText(DatabasePcb.SEGMENT_NAME, 8).strip();
        }

        String keyFeedback() {
            int length = ByteBuffer.wrap(mask.subView(DatabasePcb.KEY_LENGTH, 4).toByteArray()).getInt();
            return EBCDIC.decode(mask.subView(DatabasePcb.KEY_FEEDBACK, length).toByteArray());
        }

        HierarchicalDatabase database() {
            return region.database("BANKDB");
        }

        private String maskText(int offset, int length) {
            return EBCDIC.decode(mask.subView(offset, length).toByteArray());
        }
    }

    private static byte[] text(String value) {
        return EBCDIC.encode(value);
    }

    private static String pad(String value, int length) {
        return value + " ".repeat(length - value.length());
    }

    private static byte[] int4(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] cust(String number, String city) {
        return text(number + pad(city, 6));
    }

    private static byte[] acct(String number, int balance) {
        return concat(text(number), int4(balance));
    }

    private static byte[] note(String value) {
        return text(pad(value, 6));
    }

    private static byte[] unqualified(String segment) {
        return text(pad(segment, 8) + " ");
    }

    /** 修飾した SSA。文字列は EBCDIC にし、byte[] はそのまま並べる。 */
    private static byte[] ssa(String segment, Object... parts) {
        List<byte[]> bytes = new ArrayList<>();
        bytes.add(text(pad(segment, 8) + "("));
        for (Object part : parts) {
            bytes.add(part instanceof byte[] raw ? raw : text((String) part));
        }
        bytes.add(text(")"));
        return concat(bytes.toArray(byte[][]::new));
    }

    /** コマンドコードを付けた SSA。部品が無ければ無限定である。 */
    private static byte[] coded(String segment, String codes, Object... parts) {
        if (parts.length == 0) {
            return text(pad(segment, 8) + "*" + codes + " ");
        }
        List<byte[]> bytes = new ArrayList<>();
        bytes.add(text(pad(segment, 8) + "*" + codes + "("));
        for (Object part : parts) {
            bytes.add(part instanceof byte[] raw ? raw : text((String) part));
        }
        bytes.add(text(")"));
        return concat(bytes.toArray(byte[][]::new));
    }

    private static int intAt(Pcb pcb, int offset) {
        return ByteBuffer.wrap(pcb.io.view(offset, 4).toByteArray()).getInt();
    }

    private static Pcb bank(String access) {
        return bank(access, "A");
    }

    /** 顧客 2 人。0001 は口座 2 つ (キーの逆順に入れる) と覚え書き、0002 は口座 1 つ。 */
    private static Pcb bank(String access, String processingOptions) {
        Pcb pcb = new Pcb(access, processingOptions);
        assertEquals("  ", pcb.insert(cust("0001", "OSAKA"), unqualified("CUST")));
        assertEquals("  ", pcb.insert(acct("A002", -5), unqualified("ACCT")));
        assertEquals("  ", pcb.insert(acct("A001", 100), unqualified("ACCT")));
        assertEquals("  ", pcb.insert(note("N1"), unqualified("NOTE")));
        assertEquals("  ", pcb.insert(cust("0002", "TOKYO"), unqualified("CUST")));
        assertEquals("  ", pcb.insert(acct("A001", 7), unqualified("ACCT")));
        return pcb;
    }

    @Test
    @DisplayName("根はキーの順に並び、GN はデータベースの終わりで GB を返し続ける")
    void rootsAreInKeyOrderAndGetNextEndsWithGb() {
        Pcb pcb = new Pcb("HIDAM", "A");
        pcb.insert(cust("0003", "NAGOYA"), unqualified("CUST"));
        pcb.insert(cust("0001", "OSAKA"), unqualified("CUST"));
        pcb.insert(cust("0002", "TOKYO"), unqualified("CUST"));

        assertEquals("  ", pcb.get("GU"));
        assertEquals("0001OSAKA ", pcb.read(10));
        assertEquals("  ", pcb.get("GN", unqualified("CUST")));
        assertEquals("0002TOKYO ", pcb.read(10));
        assertEquals("  ", pcb.get("GN", unqualified("CUST")));
        assertEquals("0003NAGOYA", pcb.read(10));
        assertEquals("GB", pcb.get("GN", unqualified("CUST")));
        assertEquals("GB", pcb.get("GN", unqualified("CUST")));
    }

    @Test
    @DisplayName("無限定の GN は階層の順に辿り、段を上がれば GA、同じ段で型が変われば GK を返す")
    void unqualifiedGetNextWalksTheHierarchy() {
        Pcb pcb = bank("HIDAM");
        List<String> walked = new ArrayList<>();

        String status = pcb.get("GU");
        while (!status.equals("GB")) {
            walked.add(status + pcb.segmentName() + ":" + pcb.read(4));
            status = pcb.get("GN");
        }

        assertEquals(List.of("  CUST:0001", "  ACCT:A001", "  ACCT:A002", "GKNOTE:N1  ", "GACUST:0002",
                "  ACCT:A001"), walked);
    }

    @Test
    @DisplayName("キーが重なる兄弟は FIRST なら前に、キーを持たない兄弟は既定の LAST なら後ろに入る")
    void duplicatesFollowTheInsertRule() {
        Pcb pcb = new Pcb("HIDAM", "A");
        pcb.insert(cust("0001", "OSAKA"), unqualified("CUST"));
        pcb.insert(acct("A001", 1), unqualified("ACCT"));
        pcb.insert(acct("A001", 2), unqualified("ACCT"));
        pcb.insert(note("N1"), unqualified("NOTE"));
        pcb.insert(note("N2"), unqualified("NOTE"));

        assertEquals("  ", pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0001")));
        assertEquals("  ", pcb.get("GNP", unqualified("ACCT")));
        assertEquals(2, pcb.balance());
        assertEquals("  ", pcb.get("GNP", unqualified("ACCT")));
        assertEquals(1, pcb.balance());
        assertEquals("  ", pcb.get("GNP", unqualified("NOTE")));
        assertEquals("N1", pcb.read(2));
        assertEquals("  ", pcb.get("GNP", unqualified("NOTE")));
        assertEquals("N2", pcb.read(2));
        assertEquals("GE", pcb.get("GNP", unqualified("NOTE")));
    }

    @Test
    @DisplayName("GU は SSA を書いた段をすべて満たし、段・セグメント名・連結キーを帰還域に書く")
    void qualifiedGetUniqueFillsTheFeedback() {
        Pcb pcb = bank("HIDAM");

        assertEquals("  ", pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0002"), ssa("ACCT", "ACCTNO  = ", "A001")));
        assertEquals(7, pcb.balance());
        assertEquals("02", pcb.level());
        assertEquals("ACCT", pcb.segmentName());
        assertEquals("0002A001", pcb.keyFeedback());

        // 上の段の SSA を書かなければ、その段は無限定である
        assertEquals("  ", pcb.get("GU", ssa("ACCT", "ACCTNO  = ", "A002")));
        assertEquals("0001A002", pcb.keyFeedback());

        assertEquals("GE", pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0009")));
        assertEquals("00", pcb.level());
    }

    @Test
    @DisplayName("GNP は親境界の下だけを辿り、親境界が無ければ GP を返す")
    void getNextWithinParentStaysUnderTheParentage() {
        Pcb pcb = bank("HIDAM");
        // ISRT は親境界を決めない (P-154)
        assertEquals("GP", pcb.get("GNP"));

        assertEquals("  ", pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0001")));
        assertEquals("  ", pcb.get("GNP"));
        assertEquals("A001", pcb.read(4));
        assertEquals("  ", pcb.get("GNP"));
        assertEquals("A002", pcb.read(4));
        assertEquals("GK", pcb.get("GNP"));
        assertEquals("N1", pcb.read(2));
        assertEquals("GE", pcb.get("GNP"));
        assertEquals("01", pcb.level());
        assertEquals("CUST", pcb.segmentName());
    }

    @Test
    @DisplayName("REPL と DLET は直前の Get Hold を求め、REPL で順序フィールドを変えれば DA を返す")
    void replaceAndDeleteRequireAPrecedingGetHold() {
        Pcb pcb = bank("HIDAM");
        byte[] first = ssa("CUST", "CUSTNO  EQ", "0001");

        assertEquals("  ", pcb.get("GU", first));
        assertEquals("DJ", pcb.replace(cust("0001", "KOBE")));

        assertEquals("  ", pcb.get("GHU", first));
        assertEquals("  ", pcb.replace(cust("0001", "KOBE")));
        assertEquals("  ", pcb.get("GU", first));
        assertEquals("0001KOBE  ", pcb.read(10));

        assertEquals("  ", pcb.get("GHU", first));
        assertEquals("DA", pcb.replace(cust("0009", "KOBE")));

        // 間に別の呼び出しがあれば Hold は失われる
        assertEquals("  ", pcb.get("GHU", first));
        assertEquals("  ", pcb.get("GN"));
        assertEquals("DJ", pcb.replace(cust("0001", "NARA")));
    }

    @Test
    @DisplayName("DLET は子孫ごと消し、次の GN は消した部分木の次から始まる")
    void deleteRemovesDependents() {
        Pcb pcb = bank("HIDAM");

        assertEquals("  ", pcb.get("GHU", ssa("CUST", "CUSTNO  EQ", "0001")));
        assertEquals("  ", pcb.delete());
        assertEquals("  ", pcb.get("GN"));
        assertEquals("0002TOKYO ", pcb.read(10));
        assertEquals("GE", pcb.get("GU", ssa("ACCT", "ACCTNO  = ", "A002")));
        assertEquals(2, pcb.database().hierarchicalOrder().size());
    }

    @Test
    @DisplayName("重ならないキーを重ねた ISRT は II を返し、何も入れない")
    void duplicateUniqueKeyIsII() {
        Pcb pcb = new Pcb("HIDAM", "A");

        assertEquals("  ", pcb.insert(cust("0001", "OSAKA"), unqualified("CUST")));
        assertEquals("II", pcb.insert(cust("0001", "TOKYO"), unqualified("CUST")));
        assertEquals(1, pcb.database().roots().size());
    }

    @Test
    @DisplayName("PROCOPT が許さない呼び出しは AM。読み込み (L) は ISRT だけを許し、HIDAM では根のキーの順を求める")
    void processingOptionsLimitTheCalls() {
        Pcb readOnly = new Pcb("HIDAM", "G");
        assertEquals("AM", readOnly.insert(cust("0001", "OSAKA"), unqualified("CUST")));

        Pcb load = new Pcb("HIDAM", "L");
        assertEquals("  ", load.insert(cust("0002", "TOKYO"), unqualified("CUST")));
        assertEquals("LC", load.insert(cust("0001", "OSAKA"), unqualified("CUST")));
        assertEquals("LB", load.insert(cust("0002", "KOBE"), unqualified("CUST")));
        assertEquals("AM", load.get("GU"));

        // HDAM の読み込みは根の順を求めない。親の無い子は LD
        Pcb hashed = new Pcb("HDAM", "L");
        assertEquals("LD", hashed.insert(acct("A001", 1), unqualified("ACCT")));
        assertEquals("  ", hashed.insert(cust("0002", "TOKYO"), unqualified("CUST")));
        assertEquals("  ", hashed.insert(cust("0001", "OSAKA"), unqualified("CUST")));
    }

    @Test
    @DisplayName("根の順序フィールドの上限を越えた GN は、根がキーの順に並ぶ方式なら GE、HDAM なら GB")
    void qualifiedGetNextPastTheRootKey() {
        for (String access : List.of("HIDAM", "HDAM")) {
            Pcb pcb = new Pcb(access, "A");
            pcb.insert(cust("0001", "OSAKA"), unqualified("CUST"));
            pcb.insert(cust("0002", "TOKYO"), unqualified("CUST"));
            byte[] first = ssa("CUST", "CUSTNO  EQ", "0001");

            assertEquals("  ", pcb.get("GU", first));
            assertEquals(access.equals("HIDAM") ? "GE" : "GB", pcb.get("GN", first), access);
        }
    }

    @Test
    @DisplayName("修飾は AND を OR より強く結び、数の型は数として比べる")
    void booleanQualificationsAndNumericFields() {
        Pcb pcb = new Pcb("HIDAM", "A");
        pcb.insert(cust("0001", "OSAKA"), unqualified("CUST"));
        pcb.insert(cust("0002", "TOKYO"), unqualified("CUST"));
        pcb.insert(cust("0003", "NAGOYA"), unqualified("CUST"));
        pcb.insert(cust("0004", "KYOTO"), unqualified("CUST"));
        byte[] selection = ssa("CUST", "CUSTNO  >=", "0002", "*", "CITY    NE", "TOKYO ", "+", "CUSTNO  = ", "0001");

        // ISRT は位置を入れたセグメントに置くので、GU で先頭から始める
        List<String> found = new ArrayList<>();
        String status = pcb.get("GU", selection);
        while (status.equals("  ")) {
            found.add(pcb.read(4));
            status = pcb.get("GN", selection);
        }
        assertEquals(List.of("0001", "0003", "0004"), found);
        assertEquals("GB", pcb.status());

        byte[] parent = ssa("CUST", "CUSTNO  EQ", "0004");
        assertEquals("  ", pcb.insert(acct("A001", -5), parent, unqualified("ACCT")));
        assertEquals("  ", pcb.insert(acct("A002", 200), parent, unqualified("ACCT")));
        // バイトの並びで比べれば -5 (X'FFFFFFFB') が 100 より大きくなり、A001 を返してしまう
        assertEquals("  ", pcb.get("GU", ssa("ACCT", "BAL     GT", int4(100))));
        assertEquals(200, pcb.balance());
    }

    @Test
    @DisplayName("SSA の形の誤りは AJ、知らないフィールドは AK、知らない機能は AD。コマンドコードと独立 AND は断る")
    void malformedAndUnsupportedCalls() {
        Pcb pcb = bank("HIDAM");

        assertEquals("AK", pcb.get("GU", ssa("CUST", "NOSUCH  EQ", "0001")));
        assertEquals("AJ", pcb.get("GU", unqualified("NOSUCH")));
        assertEquals("AJ", pcb.get("GU", ssa("CUST", "CUSTNO  XX", "0001")));
        assertEquals("AJ", pcb.get("GU", unqualified("ACCT"), unqualified("CUST")));
        assertEquals("AD", pcb.call("XXXX", null));

        DliCallException commandCode = assertThrows(DliCallException.class,
                () -> pcb.get("GU", text("CUST    *U(CUSTNO  EQ0001)")));
        assertTrue(commandCode.getMessage().contains("command code U"), commandCode.getMessage());
        assertEquals("AJ", pcb.get("GU", text("CUST    *Z(CUSTNO  EQ0001)")));
        // Q (排他) は級の 1 文字ごと読み飛ばす
        assertEquals("  ", pcb.get("GU", coded("CUST", "QA", "CUSTNO  EQ", "0001")));
        assertThrows(DliCallException.class,
                () -> pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0001", "#", "CITY    EQ", "OSAKA ")));
    }

    @Test
    @DisplayName("*F は親の下の最初の出現へ戻って探し、*L は修飾を満たす最後の出現を返す (P-159)")
    void firstAndLastOccurrence() {
        Pcb pcb = bank("HIDAM");
        byte[] first = ssa("CUST", "CUSTNO  EQ", "0001");

        assertEquals("  ", pcb.get("GU", first));
        assertEquals("  ", pcb.get("GN", unqualified("ACCT")));
        assertEquals("  ", pcb.get("GN", unqualified("ACCT")));
        assertEquals("A002", pcb.read(4));
        assertEquals("  ", pcb.get("GN", coded("ACCT", "F")));
        assertEquals("A001", pcb.read(4));

        assertEquals("  ", pcb.get("GU", first, coded("ACCT", "L")));
        assertEquals("A002", pcb.read(4));
        assertEquals("  ", pcb.get("GU", first, coded("ACCT", "L", "BAL     GT", int4(0))));
        assertEquals("A001", pcb.read(4));
    }

    @Test
    @DisplayName("*C は連結キーで修飾し、*P は親境界をその段に置く (P-159)")
    void concatenatedKeyAndParentage() {
        Pcb pcb = bank("HIDAM");

        assertEquals("  ", pcb.get("GU", coded("ACCT", "C", "0002A001")));
        assertEquals(7, pcb.balance());
        assertEquals("GE", pcb.get("GU", coded("ACCT", "C", "0003A001")));

        // 親境界が ACCT A002 なら、その下に子は無い
        assertEquals("  ", pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0001"), ssa("ACCT", "ACCTNO  EQ", "A002")));
        assertEquals("GE", pcb.get("GNP"));
        assertEquals("  ", pcb.get("GU", coded("CUST", "P", "CUSTNO  EQ", "0001"),
                ssa("ACCT", "ACCTNO  EQ", "A002")));
        assertEquals("GK", pcb.get("GNP"));
        assertEquals("N1", pcb.read(2));
    }

    @Test
    @DisplayName("*D は道の上のセグメントを上から順に I/O 域へ並べ、PROCOPT に P が無ければ AM (P-159)")
    void pathRetrieval() {
        byte[] customer = coded("CUST", "D", "CUSTNO  EQ", "0002");
        byte[] account = ssa("ACCT", "ACCTNO  EQ", "A001");
        assertEquals("AM", bank("HIDAM").get("GU", customer, account));

        Pcb pcb = bank("HIDAM", "AP");
        assertEquals("  ", pcb.get("GU", customer, account));
        assertEquals("0002TOKYO A001", pcb.read(14));
        assertEquals(7, intAt(pcb, 14));
        assertEquals("ACCT", pcb.segmentName());
    }

    @Test
    @DisplayName("Get Hold の道を REPL で置き換え、*N を付けた段は置き換えない (P-159)")
    void pathReplace() {
        Pcb pcb = bank("HIDAM", "AP");
        byte[] customer = coded("CUST", "D", "CUSTNO  EQ", "0001");
        byte[] account = ssa("ACCT", "ACCTNO  EQ", "A001");

        assertEquals("  ", pcb.get("GHU", customer, account));
        assertEquals("  ", pcb.replace(concat(cust("0001", "KOBE"), acct("A001", 555))));
        assertEquals("  ", pcb.get("GHU", customer, account));
        assertEquals("0001KOBE  ", pcb.read(10));
        assertEquals(555, intAt(pcb, 14));

        assertEquals("  ", pcb.call("REPL", Storage.wrap(concat(cust("0001", "NARA"), acct("A001", 1))).whole(),
                coded("CUST", "N")));
        assertEquals("  ", pcb.get("GU", customer, account));
        assertEquals("0001KOBE  ", pcb.read(10));
        assertEquals(1, intAt(pcb, 14));
    }

    @Test
    @DisplayName("*D の ISRT は I/O 域の道のセグメントをまとめて入れ、*F は挿入規則に勝つ (P-159)")
    void pathInsertAndPlacement() {
        Pcb pcb = bank("HIDAM");

        assertEquals("  ", pcb.insert(concat(cust("0003", "NARA"), acct("A009", 9)), coded("CUST", "D"),
                unqualified("ACCT")));
        assertEquals("0003A009", pcb.keyFeedback());
        assertEquals("  ", pcb.get("GU", ssa("ACCT", "ACCTNO  EQ", "A009")));
        assertEquals(9, pcb.balance());

        // NOTE はキーを持たず、既定の挿入規則は LAST である
        assertEquals("  ", pcb.insert(note("N0"), ssa("CUST", "CUSTNO  EQ", "0001"), coded("NOTE", "F")));
        assertEquals("  ", pcb.get("GU", ssa("CUST", "CUSTNO  EQ", "0001"), unqualified("NOTE")));
        assertEquals("N0", pcb.read(2));
    }

    @Test
    @DisplayName("先頭に数を置いた呼び方を受け、数が合わなければ止める")
    void parameterCountForm() {
        Pcb pcb = bank("HIDAM");
        DataView io = Storage.allocate(10).whole();

        pcb.region.call(List.of(Storage.wrap(int4(3)).whole(), Storage.wrap(text("GU  ")).whole(), pcb.mask, io));

        assertEquals("  ", pcb.status());
        assertEquals("0001OSAKA ", EBCDIC.decode(io.toByteArray()));
        assertThrows(DliCallException.class, () -> pcb.region.call(
                List.of(Storage.wrap(int4(5)).whole(), Storage.wrap(text("GU  ")).whole(), pcb.mask, io)));
    }

    @Test
    @DisplayName("I/O 域がセグメントより短い呼び出しと、PCB でないものを渡した呼び出しは止める")
    void unsafeCallsStop() {
        Pcb pcb = new Pcb("HIDAM", "A");

        assertThrows(DliCallException.class, () -> pcb.insert(text("0001"), unqualified("CUST")));
        assertThrows(DliCallException.class, () -> pcb.region.call(List.of(Storage.wrap(text("GU  ")).whole(),
                Storage.allocate(64).whole(), Storage.allocate(10).whole())));
    }

    @Test
    @DisplayName("PSB が DBD と食い違うか、DBD が渡されていなければ領域を作らない")
    void theRegionValidatesThePsbAgainstTheDbd() {
        HierarchicalDatabase database = new HierarchicalDatabase(DbdParser.parse(dbd("HIDAM")));

        IllegalArgumentException shortKey = assertThrows(IllegalArgumentException.class,
                () -> new ImsRegion(PsbParser.parse(psb("A", 4)), List.of(database), EBCDIC));
        assertTrue(shortKey.getMessage().contains("KEYLEN"), shortKey.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new ImsRegion(PsbParser.parse(psb("A", 8)), List.of(), EBCDIC));
    }
}
