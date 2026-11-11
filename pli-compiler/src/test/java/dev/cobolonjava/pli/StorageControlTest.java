package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ALLOCATE と FREE (Enterprise PL/I Language Reference, "Storage control")。 */
class StorageControlTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(StorageControlTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static PliCompiler.Result compile(String body) {
        return PliCompiler.standard().compile("SC.pli",
                "SC: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND SC;\n");
    }

    private static String run(String body) throws Exception {
        PliCompiler.Result result = compile(body);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return String.join(",", output.toString(StandardCharsets.UTF_8).strip()
                .lines().map(String::strip).toList());
    }

    private static String rejection(String body) {
        PliCompiler.Result result = compile(body);
        assertFalse(result.succeeded(), "expected the compiler to refuse: " + body);
        return result.diagnostics().toString();
    }

    @Test
    @DisplayName("CONTROLLED は ALLOCATE ごとに世代を積み、FREE で前の世代の値に戻る")
    void controlledGenerationsStack() throws Exception {
        assertEquals("0,1 10,2 20,1 10,0", run("""
                DCL X FIXED BIN(31) CONTROLLED;
                PUT SKIP LIST(ALLOCATION(X));
                ALLOCATE X; X = 10;
                PUT SKIP LIST(ALLOCATION(X), X);
                ALLOCATE X; X = 20;
                PUT SKIP LIST(ALLOCATION(X), X);
                FREE X;
                PUT SKIP LIST(ALLOCATION(X), X);
                FREE X;
                PUT SKIP LIST(ALLOCATION(X));
                """).replaceAll(" +", " "));
    }

    @Test
    @DisplayName("ALLOCATE は INITIAL を新しい世代に効かせる")
    void allocationAppliesInitial() throws Exception {
        assertEquals("5", run("""
                DCL X FIXED BIN(31) CONTROLLED INIT(5);
                ALLOCATE X;
                PUT SKIP LIST(X);
                """));
    }

    @Test
    @DisplayName("ALLOCATE していない CONTROLLED を参照すると止まる")
    void unallocatedReferenceStops() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                DCL X FIXED BIN(31) CONTROLLED;
                X = 1;
                """));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(String.valueOf(cause.getMessage()).contains("not allocated"),
                cause::toString);
    }

    @Test
    @DisplayName("(*) の配列は ALLOCATE で上下限が決まり、引数で渡した先の ALLOCATE は呼んだ側に積まれる")
    void controlledArraysAndParameters() throws Exception {
        assertEquals("3 21,2 5 500,3 21", run("""
                DCL A(*) FIXED BIN(31) CONTROLLED;
                ALLOCATE A(3); A = 7;
                PUT SKIP LIST(DIM(A), SUM(A));
                CALL PUSH(A);
                PUT SKIP LIST(ALLOCATION(A), DIM(A), A(5));
                FREE A;
                PUT SKIP LIST(DIM(A), SUM(A));
                PUSH: PROC(B);
                  DCL B(*) FIXED BIN(31) CONTROLLED;
                  DCL I FIXED BIN(31);
                  ALLOCATE B(5);
                  DO I = 1 TO 5; B(I) = I * 100; END;
                END PUSH;
                """).replaceAll(" +", " "));
    }

    @Test
    @DisplayName("BASED(P) の ALLOCATE は記憶域を取って P に入れ、SET なら別の POINTER に入れる")
    void basedAllocationSetsTheLocator() throws Exception {
        assertEquals("17 DATA,17", run("""
                DCL P POINTER;
                DCL Q POINTER;
                DCL 1 NODE BASED(P), 2 A FIXED BIN(31), 2 B CHAR(4);
                ALLOCATE NODE;
                NODE.A = 17; NODE.B = 'DATA';
                PUT SKIP LIST(NODE.A, NODE.B);
                Q = P;
                ALLOCATE NODE SET(P);
                NODE.A = 99;
                P = Q;
                PUT SKIP LIST(NODE.A);
                FREE NODE;
                """).replaceAll(" +", " "));
    }

    @Test
    @DisplayName("ALLOCATE の名は BASED か CONTROLLED、IN の名は AREA でなければ翻訳で断る")
    void allocateTargetsAreChecked() {
        assertTrue(rejection("""
                DCL X FIXED BIN(31);
                ALLOCATE X;
                """).contains("not BASED or CONTROLLED"));
        assertTrue(rejection("""
                DCL A CHAR(100); DCL X FIXED BIN(31) BASED;
                ALLOCATE X IN(A);
                """).contains("not an AREA"));
        assertTrue(rejection("""
                DCL A(*) FIXED BIN(31);
                """).contains("(*)"));
    }

    @Test
    @DisplayName("Q->A は A の locator を一時的に Q にして読み書きし、右辺は入れ替える前に評価する")
    void locatorQualification() throws Exception {
        assertEquals("2 1,99 1", run("""
                DCL (P, Q) POINTER;
                DCL 1 NODE BASED(P), 2 A FIXED BIN(31);
                ALLOCATE NODE SET(Q);
                ALLOCATE NODE;
                NODE.A = 1;
                Q->A = NODE.A + 1;
                PUT SKIP LIST(Q->A, NODE.A);
                Q->A = 99;
                PUT SKIP LIST(Q->A, A);
                """).replaceAll(" +", " "));
    }

    @Test
    @DisplayName("NULL は X'FF000000'、SYSNULL は 0 で、別の値である。POINTERADD は同じ記憶域の中を進む")
    void nullSysnullAndPointerAdd() throws Exception {
        assertEquals("FF000000,00000000,1", run("""
                DCL (P, Q) POINTER;
                DCL 1 NODE BASED(P), 2 A FIXED BIN(31), 2 B CHAR(4);
                P = NULL();
                PUT SKIP LIST(HEX(UNSPEC(P)));
                P = SYSNULL();
                PUT SKIP LIST(HEX(UNSPEC(P)));
                ALLOCATE NODE;
                IF POINTERADD(P, 4) = ADDR(NODE.B) & NULL() ^= SYSNULL()
                  THEN PUT SKIP LIST('1');
                  ELSE PUT SKIP LIST('0');
                """));
    }

    @Test
    @DisplayName("REFER の構造は ALLOCATE で対象に上限が入り、対象を変えると後ろの要素の位置が動く")
    void referStructuresFollowTheirObject() throws Exception {
        assertEquals("3 3 20,CCCC 16,EDIT TAIL 20", run("""
                DCL N FIXED BIN(31) INIT(3), P POINTER;
                DCL 1 R BASED(P), 2 CNT FIXED BIN(31),
                  2 ITEMS(N REFER(CNT)) CHAR(4), 2 TAIL CHAR(4);
                ALLOCATE R;
                PUT SKIP LIST(CNT, DIM(ITEMS), STG(R));
                ITEMS(1) = 'AAAA'; ITEMS(2) = 'BBBB'; ITEMS(3) = 'CCCC'; TAIL = 'TAIL';
                CNT = 2;
                PUT SKIP LIST(TAIL, STG(R));
                TAIL = 'EDIT'; CNT = 3;
                PUT SKIP LIST(ITEMS(3), TAIL, CURRENTSIZE(R));
                """).replaceAll(" +", " "));
    }

    @Test
    @DisplayName("REFER は BASED の構造の中で、前に宣言したスカラーの要素だけを対象にできる")
    void referIsChecked() {
        assertTrue(rejection("""
                DCL N FIXED BIN(31);
                DCL 1 X, 2 CNT FIXED BIN(31), 2 A(N REFER(CNT)) CHAR(1);
                """).contains("BASED structure"));
        assertTrue(rejection("""
                DCL N FIXED BIN(31);
                DCL 1 X BASED, 2 A(N REFER(CNT)) CHAR(1);
                """).contains("scalar member"));
    }

    @Test
    @DisplayName("AREA の中に ALLOCATE し、OFFSET で指す。AREA の代入は中身ごと写り、EMPTY() で空になる")
    void areasAndOffsets() throws Exception {
        assertEquals("7 1,7,99 7,12", run("""
                DCL A AREA(256), B AREA(256);
                DCL O OFFSET(A), P POINTER, Q POINTER;
                DCL 1 NODE BASED(P), 2 VALUE FIXED BIN(31), 2 TEXT CHAR(4);
                ALLOCATE NODE IN(A) SET(O); P = POINTER(O, A);
                NODE.VALUE = 7; NODE.TEXT = 'DATA';
                IF OFFSET(P, A) = O THEN PUT SKIP LIST(NODE.VALUE, '1');
                B = A; P = POINTER(O, B);
                PUT SKIP LIST(NODE.VALUE);
                NODE.VALUE = 99; P = POINTER(O, A); Q = POINTER(O, B);
                PUT SKIP LIST(Q->VALUE, NODE.VALUE);
                A = EMPTY();
                ALLOCATE NODE IN(A) SET(O); P = POINTER(O, A);
                NODE.VALUE = 12;
                PUT SKIP LIST(NODE.VALUE);
                """).replaceAll(" +", " "));
    }
}
