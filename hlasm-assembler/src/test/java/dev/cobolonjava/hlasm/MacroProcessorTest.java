package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MacroProcessorTest {

    private static Assembler.Result assemble(String... lines) {
        return Assembler.assemble("macro.asm", String.join("\n", lines));
    }

    @Test
    void expandsNameAndPositionalParametersBeforeBothAssemblyPasses() {
        Assembler.Result result = assemble(
                "         MACRO",
                "&NAME    FIELD &VALUE",
                "&NAME    DC    F'&VALUE'",
                "         MEND",
                "TEST     CSECT",
                "FIRST    FIELD 7",
                "SECOND   FIELD 9",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("0000000700000009", result.module().hex(0, 8));
        assertEquals(0, result.module().symbols().get("FIRST").value().value());
        assertEquals(4, result.module().symbols().get("SECOND").value().value());
    }

    @Test
    void expandsKeywordDefaultsOverridesAndNestedCalls() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         INNER &VALUE",
                "         DC    F'&VALUE'",
                "         MEND",
                "         MACRO",
                "         OUTER &VALUE=3",
                "         INNER &VALUE",
                "         MEND",
                "TEST     CSECT",
                "         OUTER",
                "         OUTER VALUE=8",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("0000000300000008", result.module().hex(0, 8));
    }

    @Test
    void equalsInsidePositionalStringIsNotAKeywordArgument() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         EMIT  &VALUE",
                "         DC    &VALUE",
                "         MEND",
                "TEST     CSECT",
                "         EMIT  C'A=B'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C17EC2", result.module().hex(0, 3));
    }

    @Test
    void selectsConditionalModelStatementsAndExitsMacro() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         PICK  &K",
                "         AIF   ('&K' EQ 'A').YES",
                "         DC    C'B'",
                "         AGO   .DONE",
                ".YES     ANOP",
                "         DC    C'A'",
                ".DONE    ANOP",
                "         MEXIT",
                "         DC    C'X'",
                "         MEND",
                "TEST     CSECT",
                "         PICK  A",
                "         PICK  B",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C1C2", result.module().hex(0, 2));
    }

    @Test
    void setSymbolsAndBackwardConditionalBranchProduceFiniteRepetition() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&N       SETA  0",
                ".LOOP    ANOP",
                "         DC    C'&N'",
                "&N       SETA  &N+1",
                "         AIF   (&N LT 3).LOOP",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("F0F1F2", result.module().hex(0, 3));
    }

    @Test
    void assignsUniqueSystemIndexAndKeepsOrdinaryAmpersands() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         MARK",
                "M&SYSNDX DC    C'X'",
                "         MEND",
                "TEST     CSECT",
                "&N       SETA  1",
                "         DC    C'&&'",
                "         MARK",
                "         MARK",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("50E7E7", result.module().hex(0, 3));
        assertEquals(1, result.module().symbols().get("M0001").value().value());
        assertEquals(2, result.module().symbols().get("M0002").value().value());
    }

    @Test
    void sequenceSymbolsAreLocalAndLogicalExpressionsUseNumericArithmetic() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         ONE",
                "         AGO   .NEXT",
                "         DC    C'X'",
                ".NEXT    ANOP",
                "         DC    C'A'",
                "         MEND",
                "         MACRO",
                "         TWO",
                "&V       SETA  2",
                "         AIF   ((&V+1 LT 3) OR NOT (&V EQ 2)).NEXT",
                "         DC    C'B'",
                ".NEXT    ANOP",
                "         MEND",
                "TEST     CSECT",
                "         ONE",
                "         TWO",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C1C2", result.module().hex(0, 2));
    }

    @Test
    void rejectsUnknownVariablesAndRunawayExpansion() {
        Assembler.Result unknown = assemble(
                "         MACRO",
                "         BAD",
                "         DC    F'&MISSING'",
                "         MEND",
                "TEST     CSECT",
                "         BAD",
                "         END");
        assertFalse(unknown.succeeded());
        assertEquals(3, unknown.diagnostics().get(0).line());
        assertTrue(unknown.diagnostics().get(0).message().contains("&MISSING"));

        Assembler.Result recursive = assemble(
                "         MACRO",
                "         RECUR",
                "         RECUR",
                "         MEND",
                "TEST     CSECT",
                "         RECUR",
                "         END");
        assertFalse(recursive.succeeded());
        assertTrue(recursive.diagnostics().get(0).message().contains("nesting"));
    }

    @Test
    void typeAttributeWithUnknownSymbolIsU() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         CHECK &P",
                "         AIF   (T'&P EQ 'U').DONE",
                ".DONE    ANOP",
                "         MEND",
                "TEST     CSECT",
                "         CHECK ABC",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
    }

    @Test
    void typeAttributeSelectsDefinedDataAndOmittedMacroOperand() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "FIELD    DC    F'7'",
                "         MACRO",
                "         SELECT &P,&EMPTY=",
                "&TYPE    SETC  T'&P",
                "&OMIT    SETC  T'&EMPTY",
                "         AIF   (T'&P NE 'F').BAD",
                "         AIF   (T'&EMPTY NE 'O').BAD",
                "         AIF   ('&TYPE' NE 'F').BAD",
                "         AIF   ('&OMIT' NE 'O').BAD",
                "         DC    C'Y'",
                "         AGO   .DONE",
                ".BAD     ANOP",
                "         DC    C'N'",
                ".DONE    ANOP",
                "         MEND",
                "         SELECT FIELD",
                "&DIRECT  SETC  T'FIELD",
                "         DC    C'&DIRECT'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000007E8C6", result.module().hex(0, 6));
    }

    @Test
    void typeAttributeOfNumericMacroOperandIsN() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         CHECK &P",
                "&TYPE    SETC  T'&P",
                "         DC    C'&TYPE'",
                "         MEND",
                "TEST     CSECT",
                "         CHECK 42",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("D5", result.module().hex(0, 1));
    }

    @Test
    void typeAttributeDistinguishesMachineInstructionAndUnknownText() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "LAB      LR    0,0",
                "         MACRO",
                "         CHECK &P",
                "&TYPE    SETC  T'&P",
                "         DC    C'&TYPE'",
                "         MEND",
                "         CHECK LAB",
                "         CHECK ?",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("1800C9E4", result.module().hex(0, 4));
    }

    @Test
    void actrCountsOnlyTakenBranchesInEachMacroCall() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         ONCE",
                "         ACTR  2",
                "         AIF   (0).NEVER",
                "         AGO   .DONE",
                ".NEVER   ANOP",
                "         DC    C'X'",
                ".DONE    ANOP",
                "         DC    C'A'",
                "         MEND",
                "TEST     CSECT",
                "         ONCE",
                "         ONCE",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C1C1", result.module().hex(0, 2));

        Assembler.Result exhausted = assemble(
                "TEST     CSECT",
                "         ACTR  1",
                "         AGO   .DONE",
                ".DONE    ANOP",
                "         END");
        assertFalse(exhausted.succeeded());
        assertTrue(exhausted.diagnostics().get(0).message().contains("ACTR"));
    }

    @Test
    void mnoteSeverityDistinguishesMessagesFromErrors() {
        Assembler.Result accepted = assemble(
                "TEST     CSECT",
                "         MNOTE 0,'INFO'",
                "         MNOTE 4,'WARN'",
                "         MNOTE *,'COMMENT'",
                "         MNOTE 'COMMENT'",
                "         BR    14",
                "         END");
        assertTrue(accepted.succeeded(), () -> accepted.diagnostics().toString());
        assertEquals(2, accepted.diagnostics().size());
        assertEquals(Diagnostic.Severity.INFO, accepted.diagnostics().get(0).severity());
        assertEquals(Diagnostic.Severity.WARNING, accepted.diagnostics().get(1).severity());

        Assembler.Result rejected = assemble(
                "TEST     CSECT", "         MNOTE 8,'BAD'", "         END");
        assertFalse(rejected.succeeded());
        assertEquals(Diagnostic.Severity.ERROR, rejected.diagnostics().get(0).severity());
    }

    @Test
    void countAndNumberAttributesUseMacroOperandTextAndSublist() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         MEASURE &TEXT,&LIST",
                "&LEN     SETA  K'&TEXT",
                "&COUNT   SETA  N'&LIST",
                "         DC    F'&LEN'",
                "         DC    F'&COUNT'",
                "         MEND",
                "TEST     CSECT",
                "         MEASURE ABC,(X,,Y)",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("0000000300000003", result.module().hex(0, 8));

        Assembler.Result invalid = assemble(
                "TEST     CSECT", "&VALUE   SETC  'ABC'",
                "&COUNT   SETA  N'&VALUE", "         END");
        assertFalse(invalid.succeeded());
        assertTrue(invalid.diagnostics().get(0).message().contains("N' requires"));
    }

    @Test
    void subscriptedSetSymbolsHaveDefaultsAndOpenEndedIndices() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "         LCLA  &ITEM(2),&I",
                "&I       SETA  2",
                "&ITEM(1) SETA  11",
                "&ITEM(&I+1) SETA  33",
                "         DC    F'&ITEM(1)'",
                "         DC    F'&ITEM(2)'",
                "         DC    F'&ITEM(3)'",
                "&MAX     SETA  N'&ITEM",
                "         DC    F'&MAX'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("0000000B000000000000002100000003", result.module().hex(0, 16));
    }

    @Test
    void globalSetArrayIsVisibleInCalledMacro() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         EMIT",
                "         DC    C'&WORD(2)'",
                "         MEND",
                "TEST     CSECT",
                "         GBLC  &WORD(1)",
                "&WORD(2) SETC  'HI'",
                "         EMIT",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C8C9", result.module().hex(0, 2));
    }

    @Test
    void localSetArraysResetPerMacroCallWhileGlobalArraysPersist() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         STEP",
                "         LCLA  &LOCAL(1)",
                "&LOCAL(1) SETA &LOCAL(1)+1",
                "&GLOBAL(1) SETA &GLOBAL(1)+1",
                "         DC    F'&LOCAL(1)'",
                "         DC    F'&GLOBAL(1)'",
                "         MEND",
                "TEST     CSECT",
                "         GBLA  &GLOBAL(1)",
                "         STEP",
                "         STEP",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000001000000010000000100000002", result.module().hex(0, 16));
    }

    @Test
    void setArrayRejectsZeroSubscriptAndScalarUse() {
        Assembler.Result zero = assemble("TEST     CSECT", "         LCLA  &A(2)",
                "&A(0)    SETA  1", "         END");
        assertFalse(zero.succeeded());
        assertTrue(zero.diagnostics().get(0).message().contains("subscript must be positive"));

        Assembler.Result scalar = assemble("TEST     CSECT", "         LCLA  &A(2)",
                "         DC    F'&A'", "         END");
        assertFalse(scalar.succeeded());
        assertTrue(scalar.diagnostics().get(0).message().contains("requires a subscript"));
    }

    @Test
    void subscriptedSetStatementImplicitlyDeclaresArray() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&FLAG(4) SETB  (1)",
                "         DC    C'&FLAG(4)'",
                "&MAX     SETA  N'&FLAG",
                "         DC    F'&MAX'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("F100000000000004", result.module().hex(0, 8));
    }

    @Test
    void setArrayDeclarationRejectsLocalGlobalCollisionAndTypeChange() {
        Assembler.Result scope = assemble(
                "TEST     CSECT", "         GBLA  &A(2)",
                "         LCLA  &A(2)", "         END");
        assertFalse(scope.succeeded());
        assertTrue(scope.diagnostics().get(0).message().contains("both local and global"));

        Assembler.Result kind = assemble(
                "TEST     CSECT", "         LCLA  &A(2)",
                "         LCLC  &A(2)", "         END");
        assertFalse(kind.succeeded());
        assertTrue(kind.diagnostics().get(0).message().contains("type does not match"));
    }

    @Test
    void macroParameterSublistSelectsNestedAndMissingEntries() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         PICK &P,&I=1",
                "&J       SETA  &I+1",
                "&COUNT   SETA  N'&P",
                "&INNER   SETA  N'&P(3)",
                "&WIDTH   SETA  K'&P(2)",
                "&TYPE    SETC  T'&P(2)",
                "&EMPTY   SETC  T'&P(4)",
                "         DC    C'&P(&J)'",
                "         DC    C'&P(3,2)'",
                "         DC    F'&COUNT'",
                "         DC    F'&INNER'",
                "         DC    F'&WIDTH'",
                "         DC    C'&TYPE&EMPTY'",
                "         MEND",
                "TEST     CSECT",
                "         PICK  (A,42,(C,7),),I=1",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("F4F2F700000000040000000200000002D5D6",
                result.module().hex(0, 18));
    }

    @Test
    void subscriptedScalarMacroArgumentUsesFirstEntryAndNullForLaterEntries() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         PICK &P",
                "         DC    C'&P(1)'",
                "         DC    C'X&P(2)Y'",
                "         MEND",
                "TEST     CSECT",
                "         PICK  Z",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("E9E7E8", result.module().hex(0, 3));
    }

    @Test
    void keywordDefaultSublistCanBeSubscriptedAndZeroIsRejected() {
        Assembler.Result accepted = assemble(
                "         MACRO",
                "         PICK &P=(X,Y)",
                "         DC    C'&P(2)'",
                "         MEND",
                "TEST     CSECT",
                "         PICK",
                "         END");
        assertTrue(accepted.succeeded(), () -> accepted.diagnostics().toString());
        assertEquals("E8", accepted.module().hex(0, 1));

        Assembler.Result rejected = assemble(
                "         MACRO",
                "         PICK &P",
                "         DC    C'&P(0)'",
                "         MEND",
                "TEST     CSECT",
                "         PICK  (X,Y)",
                "         END");
        assertFalse(rejected.succeeded());
        assertTrue(rejected.diagnostics().get(0).message().contains("subscript must be positive"));
    }

    @Test
    void syslistSeesExtraPositionalOperandsAndNestedSublists() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         CHECK &FIRST",
                "&COUNT   SETA  N'&SYSLIST",
                "&SIZE    SETA  N'&SYSLIST(3)",
                "&INNER   SETA  N'&SYSLIST(3,2)",
                "&WIDTH   SETA  K'&SYSLIST(2)",
                "&TYPE    SETC  T'&SYSLIST(2)",
                "&OMIT    SETC  T'&SYSLIST(4)",
                "&LAST    SETC  &SYSLIST(N'&SYSLIST)",
                "         DC    C'&SYSLIST(0)&SYSLIST(3,2,2)Y&SYSLIST(7)Z&LAST'",
                "         DC    C'&COUNT&SIZE&INNER&WIDTH&TYPE&OMIT'",
                "         MEND",
                "TEST     CSECT",
                "X        CHECK A,42,(C,(D,E),),,Z",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("E7C5E8E9E9F5F3F2F2D5D6", result.module().hex(0, 11));
    }

    @Test
    void syslistCountsNoOperandsAndRejectsOpenCodeUse() {
        Assembler.Result empty = assemble(
                "         MACRO",
                "         CHECK",
                "&COUNT   SETA  N'&SYSLIST",
                "         DC    C'&COUNT'",
                "         MEND",
                "TEST     CSECT",
                "         CHECK",
                "         END");
        assertTrue(empty.succeeded(), () -> empty.diagnostics().toString());
        assertEquals("F0", empty.module().hex(0, 1));

        Assembler.Result openCode = assemble("TEST     CSECT",
                "         DC    C'&SYSLIST(1)'", "         END");
        assertFalse(openCode.succeeded());
        assertTrue(openCode.diagnostics().get(0).message().contains("only in a macro"));
    }

    @Test
    void setcSubstringSupportsLengthStarConcatenationAndAif() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&SOURCE  SETC  'ABCDE'",
                "&PREFIX  SETC  '&SOURCE'(2,2).'XY'",
                "&TAIL    SETC  '&SOURCE'(4,*)",
                "&BEYOND  SETC  '&SOURCE'(7,1)",
                "         AIF   ('&SOURCE'(1,3) EQ 'ABC').GOOD",
                "         DC    C'N'",
                "         AGO   .DONE",
                ".GOOD    ANOP",
                "         DC    C'&PREFIX&TAIL.Z&BEYOND'",
                ".DONE    ANOP",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C2C3E7E8C4C5E9", result.module().hex(0, 7));
    }

    @Test
    void setcSubstringRejectsZeroStart() {
        Assembler.Result result = assemble("TEST     CSECT",
                "&BAD     SETC  'ABC'(0,1)", "         END");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("invalid substring"));
    }

    @Test
    void lookaheadFindsStaticDataAfterMacroCallButDefinedAttributeDoesNot() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         INSPECT",
                "&TYPE    SETC  T'AFTER",
                "&LENGTH  SETA  L'AFTER",
                "&BEFORE  SETA  D'AFTER",
                "&OPCODE  SETC  O'MVC",
                "&MISSING SETC  O'NOPE",
                "         DC    C'&TYPE&OPCODE&MISSING'",
                "         DC    F'&LENGTH'",
                "         DC    F'&BEFORE'",
                "         MEND",
                "TEST     CSECT",
                "         INSPECT",
                "AFTER    DC    F'7'",
                "&DEFINED SETA  D'AFTER",
                "         DC    F'&DEFINED'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C6D6E40000000004000000000000000700000001",
                result.module().hex(0, 20));
    }

    @Test
    void systemVariablesExposeAssemblyDateAndMacroNesting() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         INNER",
                "         DC    C'&SYSNEST&SYSMAC'",
                "         MEND",
                "         MACRO",
                "         OUTER",
                "&TYPE    SETC  T'&SYSNDX",
                "         DC    C'&SYSNEST&TYPE'",
                "         INNER",
                "         MEND",
                "TEST     CSECT",
                "&COUNT   SETA  K'&SYSDATC",
                "&NUMBER  SETA  N'&SYSDATC",
                "         DC    C'&COUNT&NUMBER&SYSASM'",
                "         OUTER",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("80COBOL-ON-JAVA1N2INNER",
                new String(result.module().text(),
                        dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));

        Assembler.Result localOutsideMacro = assemble("TEST     CSECT",
                "         DC    C'&SYSNEST'", "         END");
        assertFalse(localOutsideMacro.succeeded());
    }

    @Test
    void characterBuiltinsConvertEBCDICBitsAndCase() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&UP      SETC  UPPER('aBc')",
                "&LOW     SETC  LOWER('DeF')",
                "&HEX     SETC  C2X('12')",
                "&TEXT    SETC  X2C('F1F2')",
                "&BITS    SETC  A2B(5)",
                "&DEC     SETC  B2D('11110001')",
                "&NEG     SETC  SIGNED(-7)",
                "&DHEX    SETC  D2X('-7')",
                "&JOIN    SETC  UPPER('x').LOWER('Y')",
                "&LOGICAL SETC  (UPPER 'ab')",
                "&COPIES  SETC  (3)'Z'",
                "&ADJOIN  SETC  'Q'LOWER('R')",
                "&ADJFN   SETC  UPPER('u')LOWER('V')",
                "&FNSUB   SETC  UPPER('abcdef')(2,3)",
                "         DC    C'&UP&LOW&HEX&TEXT&DEC&NEG&DHEX'",
                "         DC    C'&BITS&JOIN&LOGICAL&COPIES&ADJOIN&ADJFN&FNSUB'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("ABCdefF1F212+241-7FFFFFFF9"
                        + "00000000000000000000000000000101XyABZZZQrUvBCD",
                new String(result.module().text(),
                        dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void arithmeticBuiltinsSupportSearchConversionAndBitOperations() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&HEX     SETA  X2A('F1')",
                "&CHAR    SETA  C2A('1')",
                "&FIND    SETA  FIND('ABCDE','ZD')",
                "&INDEX   SETA  INDEX('ABCDE','CD')",
                "&LENGTH  SETA  DCLEN('A&&B')",
                "&MASK    SETA  (X'F0' AND X'3C') SLL 2",
                "&ORDER   SETA  1 OR 2 AND 4",
                "&GROUP   SETA  1+(2 OR 4)",
                "&VALID   SETA  ISDEC('2147483647')",
                "         DC    C'&HEX/&CHAR/&FIND/&INDEX/&LENGTH/&MASK/&ORDER/'",
                "         DC    C'&GROUP/&VALID'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("241/241/4/3/3/192/0/7/1", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void arithmeticSearchInfixAndSelfDefiningComparisonsFollowIbmForms() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&FOUND   SETA  ('ABCDE' FIND 'ZD')",
                "&INDEX   SETA  ('A(B)C' INDEX 'B')",
                "&NESTED  SETA  1+('A(B)C' INDEX 'B')",
                "&BIT     SETB  ((X'F0' AND X'3C') EQ 48)",
                "&ORDER   SETB  (X'10' LT X'2')",
                "         AIF   (X'10' GT X'2').GOOD",
                "         DC    C'BAD'",
                "         AGO   .DONE",
                ".GOOD    ANOP",
                "         DC    C'&FOUND/&INDEX/&NESTED/&BIT/&ORDER'",
                ".DONE    ANOP",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("4/3/4/1/0", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void characterRelationsCompareLengthBeforeEbcdicBytes() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&SHORT   SETB  ('BB' LT 'AAA')",
                "&ORDER   SETB  ('A' GT 'a')",
                "&LONGER  SETB  ('BB' GT 'AAA')",
                "         DC    C'&SHORT&ORDER&LONGER'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("110", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void comparisonWordsInsideCharacterValuesAreNotOperators() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&EQUAL   SETB  ('A EQ B' EQ 'A EQ B')",
                "&NOT     SETB  ('A LT B' NE 'A GT B')",
                "&GROUP   SETB  (('A OR B' EQ 'A OR B') AND ('X' EQ 'X'))",
                "         DC    C'&EQUAL&NOT&GROUP'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("111", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void parenthesizedCharacterFunctionCanBeJuxtaposed() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&TEXT    SETC  (UPPER 'a')'B'",
                "&REPEAT  SETC  (3)'Z'",
                "         DC    C'&TEXT/&REPEAT'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("AB/ZZZ", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void systemMacroAncestryAndSeverityTrackNestedCalls() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         INNER",
                "         DC    C'&SYSMAC(0)/&SYSMAC(1)/&SYSMAC(2)'",
                "         MNOTE 4,'warning'",
                "         MEND",
                "         MACRO",
                "         OUTER",
                "         INNER",
                "         MEND",
                "TEST     CSECT",
                "&TYPE    SETC  T'&SYS_HLASM_DATE",
                "&PTFTYPE SETC  T'&SYS_HLASM_PTF",
                "&PARMTYP SETC  T'&SYSPARM",
                "         OUTER",
                "         DC    C'&TYPE&PTFTYPE&PARMTYP/&SYSM_HSEV'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("INNER/OUTER/OPEN CODENUO/4", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void sysseqfKeepsOutermostCallIdentificationThroughNestedMacros() {
        String call = String.format("%-72s", "         OUTER") + "AB12    ";
        Assembler.Result result = assemble(
                "         MACRO",
                "         INNER",
                "&PREFIX  SETC  '&SYSSEQF'(1,4)",
                "&WIDTH   SETA  K'&SYSSEQF",
                "&TYPE    SETC  T'&SYSSEQF",
                "         DC    C'&PREFIX/&WIDTH/&TYPE'",
                "         MEND",
                "         MACRO",
                "         OUTER",
                "         INNER",
                "         MEND",
                "TEST     CSECT",
                call,
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("AB12/8/U", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void lookaheadUnknownAndDuplicateSymbolsHaveUnknownType() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         CHECK",
                "&UNKNOWN SETC  T'NEVER",
                "&REPEAT  SETC  T'ITEM",
                "         DC    C'&UNKNOWN&REPEAT'",
                "         MEND",
                "TEST     CSECT",
                "         CHECK",
                "ITEM     DC    F'1'",
                "ITEM     DC    H'2'",
                "         END");
        assertFalse(result.succeeded()); // 最終組み立てでは重複ラベルを診断する。
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("defined twice")));
    }

    @Test
    void lookaheadComputesScaleAndIntegerAttributes() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         CHECK",
                "&PS      SETA  S'PACK",
                "&PI      SETA  I'PACK",
                "&ZS      SETA  S'ZONE",
                "&ZI      SETA  I'ZONE",
                "&FI      SETA  I'FULL",
                "         DC    C'&PS/&PI/&ZS/&ZI/&FI'",
                "         MEND",
                "TEST     CSECT",
                "         CHECK",
                "PACK     DC    P'123.45'",
                "ZONE     DC    Z'123.45'",
                "FULL     DC    F'7'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("2/3/2/3/31", new String(result.module().text(), 0, 10,
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void forwardAttributesIgnoreOrdinarySymbolQualifiers() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&NAME    SETC  'LATER.QUAL'",
                "&TYPE    SETC  T'LATER.QUAL",
                "&PARAM   SETC  T'&NAME",
                "&LENGTH  SETA  L'LATER.QUAL",
                "&SCALE   SETA  S'LATER.QUAL",
                "&INTEGER SETA  I'LATER.QUAL",
                "&DEFINED SETA  D'LATER.QUAL",
                "         DC    C'&TYPE&PARAM/&LENGTH/&SCALE/&INTEGER/&DEFINED'",
                "LATER    DC    P'12.3'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("PP/2/1/2/0", new String(result.module().text(), 0, 10,
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void forwardAttributesOfMacroOperandExpressionUseLeftmostTerm() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         CHECK &EXPR",
                "&TYPE    SETC  T'&EXPR",
                "&LENGTH  SETA  L'&EXPR",
                "&SCALE   SETA  S'&EXPR",
                "         DC    C'&TYPE/&LENGTH/&SCALE'",
                "         MEND",
                "TEST     CSECT",
                "         CHECK LATER.QUAL+5",
                "         CHECK (LATER+5,OTHER)",
                "LATER    DC    P'12.3'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("P/2/1P/2/1", new String(result.module().text(), 0, 10,
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void literalAttributesBecomeAvailableAfterMachineUse() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         BEFORE &LIT",
                "&PRETYPE SETC  T'&LIT",
                "&PREDEF  SETA  D'&LIT",
                "         MEND",
                "         MACRO",
                "         AFTER &LIT",
                "&POSTYPE SETC  T'&LIT",
                "&POSTLEN SETA  L'&LIT",
                "&POSTDEF SETA  D'&LIT",
                "         MEND",
                "TEST     CSECT",
                "         GBLC  &PRETYPE,&POSTYPE",
                "         GBLA  &PREDEF,&POSTLEN,&POSTDEF",
                "         BEFORE =F'7'",
                "         USING *,12",
                "         L     1,=F'7'",
                "         AFTER =F'7'",
                "RESULT   DC    C'&PRETYPE&PREDEF/&POSTYPE&POSTLEN&POSTDEF'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        int resultOffset = result.module().symbols().get("RESULT").value().value();
        assertEquals("U0/F41", new String(result.module().text(), resultOffset, 6,
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void aifUsesArithmeticBuiltinValue() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&GOOD    SETB  ISHEX('F1')",
                "         AIF   (&GOOD AND D2A('+7') GT 6).YES",
                "         DC    C'N'",
                "         AGO   .DONE",
                ".YES     ANOP",
                "         DC    C'Y'",
                ".DONE    ANOP",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("E8", result.module().hex(0, 1));
    }

    @Test
    void sysmSevReportsMostRecentMacroRatherThanAssemblyMaximum() {
        Assembler.Result result = assemble(
                "         MACRO",
                "         WARN",
                "         MNOTE 4,'warning'",
                "         MEND",
                "         MACRO",
                "         CLEAN",
                "         ANOP",
                "         MEND",
                "TEST     CSECT",
                "         WARN",
                "         DC    C'&SYSM_SEV'",
                "         CLEAN",
                "         DC    C'&SYSM_SEV&SYSM_HSEV'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("404", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void assemblerAndProgramTypeFunctionsReadEquMetadata() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "REG      EQU   0,,C'X',C'Work',GR",
                "FIELD    DC    F'7'",
                "&REGTYPE SETC  SYSATTRA('REG')",
                "&PROGRAM SETC  SYSATTRP('REG')",
                "&DATTYPE SETC  SYSATTRA('FIELD')",
                "&TYPE    SETC  T'REG",
                "         DC    C'&REGTYPE/&PROGRAM/&DATTYPE/&TYPE'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("GR/Work/F/X", new String(result.module().text(), 4, 11,
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void libraryMacroExposesItsMemberName() {
        String member = String.join("\n",
                "         MACRO",
                "         MEMBER",
                "         DC    C'&SYSLIB_MEMBER'",
                "         MEND");
        Assembler.Result result = Assembler.assemble("macro.asm", String.join("\n",
                "TEST     CSECT",
                "         MEMBER",
                "         END"),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT,
                name -> name.equals("MEMBER") ? member : null);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("MEMBER", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void setcSubstitutesSetaMagnitudeUnlessSignedIsRequested() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&NUMBER  SETA  -10",
                "&PLAIN   SETC  '&NUMBER'",
                "&SIGNED  SETC  SIGNED(&NUMBER)",
                "         DC    C'&PLAIN/&SIGNED'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("10/-10", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }

    @Test
    void codePageSystemOptionsUseFiveDigitCcsidValues() {
        Assembler.Result result = assemble(
                "TEST     CSECT",
                "&WIDTH   SETA  K'&SYSOPT_CODEPAGE",
                "&TYPE    SETC  T'&SYSOPT_EBCDIC",
                "         DC    C'&SYSOPT_CODEPAGE/&SYSOPT_ASCII/&WIDTH&TYPE'",
                "         END");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("01047/00819/5N", new String(result.module().text(),
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()));
    }
}
