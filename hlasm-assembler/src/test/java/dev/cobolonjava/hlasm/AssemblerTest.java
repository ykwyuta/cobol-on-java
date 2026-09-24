package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 組み立てそのものを突き合わせる。
 *
 * <p>期待値は z/Architecture Principles of Operation の命令形式から作る。実行の一致とは<b>別に</b>
 * 測るのは、アセンブラが誤った機械語を作ると、Hercules も自分のインタプリタも同じ誤りを
 * 実行して一致してしまうためである。道具が処理系の成功を作ってはならない。
 */
class AssemblerTest {

    private static ObjectModule assemble(String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        return result.module();
    }

    private static Assembler.Result attempt(String... lines) {
        return Assembler.assemble("TEST.asm", String.join("\n", lines));
    }

    // --- 命令の形式 ---

    @Test
    @DisplayName("RR 形式は命令コードとレジスタ 2 つの 2 バイトである")
    void encodesRegisterToRegister() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         LR    3,4",
                "         AR    15,1",
                "         END");
        assertEquals("1834", module.hex(0, 2));
        assertEquals("1AF1", module.hex(2, 2));
    }

    @Test
    @DisplayName("RX 形式は変位 12 ビット、ベースと指標が 4 ビットずつである")
    void encodesRegisterIndexed() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         L     2,16(3,4)",
                "         ST    5,4095(0,9)",
                "         END");
        assertEquals("58234010", module.hex(0, 4));
        assertEquals("50509FFF", module.hex(4, 4));
    }

    @Test
    @DisplayName("SS 形式の長さは 1 を引いて入る")
    void encodesStorageToStorageLengthMinusOne() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         MVC   0(256,1),0(2)",
                "         AP    0(4,1),0(3,2)",
                "         END");
        // MVC の長さ欄は 8 ビット。256 バイトは 0xFF である
        assertEquals("D2FF10002000", module.hex(0, 6));
        // AP の長さ欄は 4 ビットずつ。4 バイトと 3 バイトは 0x32 である
        assertEquals("FA3210002000", module.hex(6, 6));
    }

    @Test
    @DisplayName("SI 形式は即値が第 2 バイトに入る")
    void encodesStorageImmediate() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         MVI   8(1),C' '",
                "         CLI   0(2),X'40'",
                "         END");
        assertEquals("92401008", module.hex(0, 4));
        assertEquals("95402000", module.hex(4, 4));
    }

    @Test
    @DisplayName("SRP は第 1 の長さと丸め桁が 4 ビットずつ同じバイトに入る")
    void encodesShiftAndRound() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         SRP   0(4,1),2(0),5",
                "         END");
        assertEquals("F0351000" + "0002", module.hex(0, 6));
    }

    @Test
    @DisplayName("SPM は R1 だけの RR、IPM は R1 だけの RRE である")
    void encodesProgramMaskInstructions() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         SPM   6",
                "         IPM   7",
                "         END");
        assertEquals("0460" + "B2220070", module.hex(0, 6));
    }

    @Test
    @DisplayName("SVC は命令コードと番号の 2 バイトである")
    void encodesSupervisorCall() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         SVC   35",
                "         END");
        assertEquals("0A23", module.hex(0, 2));
    }

    // --- 拡張ニーモニック ---

    @Test
    @DisplayName("拡張ニーモニックは分岐マスクを綴りに畳み込んだものである")
    void encodesExtendedBranchMnemonics() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         BR    14",
                "         BCR   15,14",
                "         END");
        // BR 14 と BCR 15,14 は同じ機械語でなければならない
        assertEquals(module.hex(0, 2), module.hex(2, 2));
        assertEquals("07FE", module.hex(0, 2));
    }

    @Test
    @DisplayName("BNE は マスク 7 の BC である")
    void encodesBranchOnNotEqual() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING TEST,15",
                "HERE     BNE   HERE",
                "         END");
        assertEquals("4770F000", module.hex(0, 4));
    }

    // --- USING の解決 ---

    @Test
    @DisplayName("USING は記号を変位とベースへ解く")
    void resolvesImplicitAddressThroughUsing() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING TEST,12",
                "         L     1,WORD",
                "WORD     DC    F'0'",
                "         END");
        // WORD は節の先頭から 4 バイト目。境界合わせで L の 4 バイトの直後に来る
        assertEquals("5810C004", module.hex(0, 4));
    }

    /**
     * 届く USING が 2 つあるときの選び方。変位の小さいほうを選ぶ。
     * ここを取り違えると組み立ては通り、実行時に別の場所を読む。
     */
    @Test
    @DisplayName("届く USING が 2 つあれば変位の小さいほうを選ぶ")
    void prefersTheSmallestDisplacement() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING TEST,12",
                "         USING FAR,11",
                "         L     1,FAR",
                "FAR      EQU   TEST+8",
                "         END");
        // 12 番では変位 8、11 番では変位 0。小さいほうの 11 番を選ぶ
        assertEquals("5810B000", module.hex(0, 4));
    }

    @Test
    @DisplayName("届く USING が無ければ断る")
    void rejectsAddressWithoutUsing() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "         L     1,WORD",
                "WORD     DC    F'0'",
                "         END");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("no USING register covers"));
    }

    @Test
    @DisplayName("DROP した USING は使えない")
    void dropRemovesTheUsing() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "         USING TEST,12",
                "         DROP  12",
                "         L     1,WORD",
                "WORD     DC    F'0'",
                "         END");
        assertFalse(result.succeeded());
    }

    @Test
    @DisplayName("USING はダミー節にも効く")
    void resolvesThroughDummySection() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING RECORD,8",
                "         MVC   RNAME,RNAME",
                "         BR    14",
                "RECORD   DSECT",
                "RKEY     DS    CL4",
                "RNAME    DS    CL20",
                "         END");
        // RNAME はダミー節の 4 バイト目、長さ 20。長さ欄は 19 (0x13)
        assertEquals("D2138004" + "8004", module.hex(0, 6));
        assertEquals(24, module.dummySections().get("RECORD"));
    }

    // --- DC / DS ---

    @Test
    @DisplayName("C は左詰めで空白埋め、X は右詰めでゼロ埋めである")
    void encodesCharacterAndHexConstants() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    CL4'AB'",
                "         DC    XL3'1F'",
                "         END");
        // EBCDIC の A=C1 B=C2、空白=40
        assertEquals("C1C24040", module.hex(0, 4));
        assertEquals("00001F", module.hex(4, 3));
    }

    @Test
    @DisplayName("F は 4 バイト、H は 2 バイトの 2 の補数である")
    void encodesBinaryConstants() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    F'-1'",
                "         DC    H'258'",
                "         END");
        assertEquals("FFFFFFFF", module.hex(0, 4));
        assertEquals("0102", module.hex(4, 2));
    }

    @Test
    @DisplayName("P はパック 10 進、Z はゾーン 10 進である")
    void encodesDecimalConstants() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    PL3'-123'",
                "         DC    ZL3'123'",
                "         END");
        assertEquals("00123D", module.hex(0, 3));
        assertEquals("F1F2C3", module.hex(3, 3));
    }

    /** z/OS probe の ASMDC1 で見つかった。以前は X'000000C7' だった。 */
    @Test
    @DisplayName("Z の明示長の余りは、ゾーンの 0 (F0) で左を埋める")
    void padsZonedConstantsWithZonedZeros() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    ZL4'7'",
                "         DC    ZL3'-12'",
                "         DC    ZL2'12345'",
                "         END");
        assertEquals("F0F0F0C7", module.hex(0, 4));
        assertEquals("F0F1D2", module.hex(4, 3));
        // 長いほうは左を切る
        assertEquals("F4C5", module.hex(7, 2));
    }

    /** z/OS probe の ASMDC1 で見つかった。以前は 2 byte で、後ろの定数が 1 つずれていた。 */
    @Test
    @DisplayName("C の中の && は 1 つのアンパサンド、'' は 1 つの引用符である")
    void collapsesDoubledAmpersandsAndQuotes() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    C'&&'",
                "         DC    C'X''Y'",
                "         DC    C'A'",
                "         END");
        assertEquals(5, module.length());
        assertEquals("50E77DE8C1", module.hex(0, 5));
    }

    /** P-171。文字列の中の L'' を属性参照と読み、定数全体を断っていた (z/OS probe の ASMDC3)。 */
    @Test
    @DisplayName("文字列の中の L'' は属性参照ではない")
    void readsAttributeLettersInsideStringsAsText() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    C'L''A'",
                "         DC    C'A''L'",
                "         DC    AL1(L'FLD)",
                "FLD      DC    CL3'ABC'",
                "         END");
        // L ' A = D3 7D C1、A ' L = C1 7D D3、L'FLD = 3
        assertEquals("D37DC1C17DD303C1C2C3", module.hex(0, 10));
    }

    @Test
    @DisplayName("F は 4 バイト境界に合わせ、明示長を書けば合わせない")
    void alignsConstantsOnTheirBoundary() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    C'A'",
                "         DC    F'1'",
                "         END");
        // C'A' の 1 バイトのあと、境界合わせで 3 バイト空く
        assertEquals(8, module.length());
        assertEquals("00000001", module.hex(4, 4));
    }

    @Test
    @DisplayName("DS 0F は場所を取らずに境界だけを合わせる")
    void alignsWithoutReservingSpace() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    C'A'",
                "         DS    0F",
                "HERE     DC    C'B'",
                "         END");
        assertEquals(4, module.symbols().get("HERE").value().value());
    }

    @Test
    @DisplayName("反復係数は同じ値を繰り返す")
    void repeatsWithDuplicationFactor() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    3XL2'FF'",
                "         END");
        assertEquals("00FF00FF00FF", module.hex(0, 6));
    }

    @Test
    @DisplayName("A は式の値を 4 バイトで持つ")
    void encodesAddressConstant() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    A(TARGET)",
                "TARGET   DC    F'0'",
                "         END");
        assertEquals("00000004", module.hex(0, 4));
    }

    /**
     * 倍率修飾子を黙って 0 として組み立てると、値が 10 の冪だけずれる。
     * 近い値を返すより断るほうがよい。
     */
    @Test
    @DisplayName("倍率と指数の修飾子は断る")
    void rejectsScaleModifier() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "         DC    PS2'1.23'",
                "         END");
        assertFalse(result.succeeded());
    }

    // --- 記号と式 ---

    @Test
    @DisplayName("EQU はレジスタの綴りを作るのに使える")
    void equateDefinesAbsoluteSymbols() {
        ObjectModule module = assemble(
                "R1       EQU   1",
                "R2       EQU   2",
                "TEST     CSECT",
                "         LR    R1,R2",
                "         END");
        assertEquals("1812", module.hex(0, 2));
    }

    @Test
    @DisplayName("同じ節の 2 点の差は絶対値になる")
    void subtractsWithinTheSameSection() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "START1   DS    CL10",
                "END1     DS    0C",
                "LEN      EQU   END1-START1",
                "         DC    A(LEN)",
                "         END");
        assertEquals("0000000A", module.hex(12, 4));
    }

    @Test
    @DisplayName("節をまたいだ記号の足し算は断る")
    void rejectsAdditionOfTwoRelocatableTerms() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "A        DS    C",
                "B        DS    C",
                "         DC    A(A+B)",
                "         END");
        assertFalse(result.succeeded());
    }

    @Test
    @DisplayName("自己定義項は X' B' C' で書ける")
    void evaluatesSelfDefiningTerms() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    A(X'10'+B'0001'+C'A')",
                "         END");
        // 0x10 + 1 + 0xC1 = 0xD2
        assertEquals("000000D2", module.hex(0, 4));
    }

    @Test
    @DisplayName("所在カウンタ * はその文の先頭を指す")
    void locationCounterPointsAtTheStatement() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         DC    F'0'",
                "         DC    A(*)",
                "         END");
        assertEquals("00000004", module.hex(4, 4));
    }

    @Test
    @DisplayName("長さ属性 L' は定義した文が決める")
    void lengthAttributeComesFromTheDefiningStatement() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "FIELD    DS    CL17",
                "         DC    A(L'FIELD)",
                "         END");
        assertEquals("00000011", module.hex(20, 4));
    }

    /** 明示長のない SS 形式の長さは、第 1 演算項の記号の長さ属性で決まる。 */
    @Test
    @DisplayName("MVC の長さは第 1 演算項の長さ属性から決まる")
    void storageToStorageLengthComesFromTheFirstOperand() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING TEST,12",
                "         MVC   TO,FROM",
                "         BR    14",
                "TO       DS    CL8",
                "FROM     DS    CL8",
                "         END");
        // 長さ 8 は長さ欄で 7
        assertEquals("D207", module.hex(0, 2));
    }

    // --- リテラル ---

    @Test
    @DisplayName("リテラルは LTORG の場所に置かれ、同じ綴りは 1 つにまとめる")
    void placesLiteralsInThePool() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING TEST,12",
                "         L     1,=F'7'",
                "         L     2,=F'7'",
                "         BR    14",
                "         LTORG",
                "         END");
        // 2 つの L は同じ場所を指す
        assertEquals(module.hex(0, 4).substring(4), module.hex(4, 4).substring(4));
        assertEquals("00000007", module.hex(12, 4));
    }

    @Test
    @DisplayName("LTORG を書かなくても END でリテラルを置く")
    void placesLiteralsAtEndWithoutLtorg() {
        ObjectModule module = assemble(
                "TEST     CSECT",
                "         USING TEST,12",
                "         L     1,=F'7'",
                "         END");
        assertEquals("00000007", module.hex(4, 4));
    }

    // --- 断ると決めたもの ---

    /**
     * 知らない命令欄はマクロ呼出しかもしれない。読み飛ばすと、展開されるはずだった命令が
     * 消えたまま組み立てが通る。断って、増分 2 で入れると分かる形にする。
     */
    @Test
    @DisplayName("知らない命令欄は読み飛ばさずに断る")
    void rejectsUnknownOperations() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "         WTO   'HELLO'",
                "         END");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("macros are not supported yet"));
    }

    @Test
    @DisplayName("ソース内マクロ定義は組み立て対象の文としては出力しない")
    void acceptsMacroDefinitions() {
        Assembler.Result result = attempt(
                "         MACRO",
                "         MYMAC",
                "         MEND",
                "TEST     CSECT",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals(0, result.module().length());
    }

    @Test
    @DisplayName("変位が 4095 を超えれば断る")
    void rejectsDisplacementOutOfRange() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "         L     1,4096(0,2)",
                "         END");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("displacement"));
    }

    @Test
    @DisplayName("同じ名前を 2 度定義すれば断る")
    void rejectsDuplicateSymbols() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "SAME     DS    C",
                "SAME     DS    C",
                "         END");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("defined twice"));
    }

    @Test
    @DisplayName("診断は原文の行を持つ")
    void diagnosticsCarryTheSourceLine() {
        Assembler.Result result = attempt(
                "TEST     CSECT",
                "         BR    14",
                "         WTO   'X'",
                "         END");
        assertEquals(3, result.diagnostics().get(0).line());
    }
}
