*---------------------------------------------------------------------
* ASMEXE - execution probe called by CBLASMD (P-173, P-174, P-175)
* Entry: R1 -> one address -> a 256-byte work area.
*   Byte 0 of the area is the function code; results go from
*   offset 16 on. A condition code is stored as one byte 0..3.
* Codes (each one is run in its own job step):
*   01 ED     three values with a 9-byte pattern, and CR with '*'
*   02 EDMK   offset of the first significant digit (R1)
*   03 TRT    R1 offset, R2 function byte, condition code
*   04 MVO    two cases
*   05 SRP    shift left, and round-shift right (+ and -)
*   06 SLDA / SRDA without overflow
*   07 SLDA with overflow           S0C8 if the mask allows it
*   08 A with overflow              S0C8 if the mask allows it
*   09 AP with overflow             S0CA if the mask allows it
*   10 LA wrap-around at 2 GB (AMODE 31)
*   11 PACK / UNPK / CVB / CVD baseline (already checked with
*      Hercules through cobol-runtime; a check of the harness)
* Only instructions that this implementation can assemble are used,
* so that the same source runs on both sides. IPM / SPM are in
* ASMPM, which is host-only.
* Scenario notes (Japanese): docs/zos-probe/scenarios.md
*---------------------------------------------------------------------
ASMEXE   CSECT
ASMEXE   AMODE 31
ASMEXE   RMODE ANY
         STM   14,12,12(13)
         LR    12,15
         USING ASMEXE,12
         L     9,0(0,1)            work area address
         LA    9,0(0,9)            drop the end-of-list bit
         SR    2,2
         IC    2,0(0,9)            function code
         SLL   2,2
         B     JTAB(2)
JTAB     B     RETURN              00
         B     F01
         B     F02
         B     F03
         B     F04
         B     F05
         B     F06
         B     F07
         B     F08
         B     F09
         B     F10
         B     F11
*
F01      MVC   16(9,9),PAT1
         ED    16(9,9),PKA
         BAL   10,GETCC
         STC   3,25(0,9)
         MVC   32(9,9),PAT1
         ED    32(9,9),PKZ
         BAL   10,GETCC
         STC   3,41(0,9)
         MVC   48(9,9),PAT1
         ED    48(9,9),PKN
         BAL   10,GETCC
         STC   3,57(0,9)
         MVC   64(11,9),PAT2
         ED    64(11,9),PKA
         BAL   10,GETCC
         STC   3,75(0,9)
         MVC   80(11,9),PAT2
         ED    80(11,9),PKN
         BAL   10,GETCC
         STC   3,91(0,9)
         B     RETURN
*
F02      MVC   16(9,9),PAT1
         LA    1,21(0,9)           after the significance starter
         EDMK  16(9,9),PKA
         LA    4,16(0,9)
         SR    1,4
         STC   1,25(0,9)
         MVC   32(9,9),PAT1
         LA    1,37(0,9)
         EDMK  32(9,9),PKS
         LA    4,32(0,9)
         SR    1,4
         STC   1,41(0,9)
         B     RETURN
*
F03      SR    1,1
         SR    2,2
         TRT   STR(10),TRTAB
         BAL   10,GETCC
         STC   3,16(0,9)
         LA    4,STR
         SR    1,4
         ST    1,20(0,9)
         ST    2,24(0,9)
         B     RETURN
*
F04      MVC   16(4,9),MV1
         MVO   16(4,9),MV2(2)
         MVC   24(3,9),MV1
         MVO   24(3,9),MV1(3)
         B     RETURN
*
F05      MVC   16(4,9),SP1
         SRP   16(4,9),2,0         shift left 2
         BAL   10,GETCC
         STC   3,20(0,9)
         MVC   24(4,9),SP2
         SRP   24(4,9),63,5        shift right 1, round
         BAL   10,GETCC
         STC   3,28(0,9)
         MVC   32(4,9),SP3
         SRP   32(4,9),63,5        negative, shift right 1, round
         BAL   10,GETCC
         STC   3,36(0,9)
         B     RETURN
*
F06      LM    4,5,DW1
         SLDA  4,4
         BAL   10,GETCC
         STM   4,5,16(9)
         STC   3,24(0,9)
         LM    4,5,DW2
         SRDA  4,2
         BAL   10,GETCC
         STM   4,5,32(9)
         STC   3,40(0,9)
         B     RETURN
*
F07      LM    4,5,DW3
         SLDA  4,1
         BAL   10,GETCC
         STM   4,5,16(9)
         STC   3,24(0,9)
         B     RETURN
*
F08      L     2,MAXF
         A     2,ONE
         BAL   10,GETCC
         ST    2,16(0,9)
         STC   3,20(0,9)
         B     RETURN
*
F09      MVC   16(2,9),P999
         AP    16(2,9),P1
         BAL   10,GETCC
         STC   3,18(0,9)
         B     RETURN
*
F10      L     3,MAXF
         LA    2,1(0,3)
         ST    2,16(0,9)
         B     RETURN
*
F11      PACK  16(3,9),ZD1
         UNPK  24(5,9),16(3,9)
         CVB   2,DWP
         ST    2,32(0,9)
         L     2,NEG
         CVD   2,40(0,9)
         B     RETURN
*
RETURN   LM    14,12,12(13)
         SR    15,15
         BR    14
*
* R3 := condition code (0..3). Returns through R10. LA and BR do
* not change the condition code.
GETCC    BC    8,GCC0
         BC    4,GCC1
         BC    2,GCC2
         LA    3,3
         BR    10
GCC0     LA    3,0
         BR    10
GCC1     LA    3,1
         BR    10
GCC2     LA    3,2
         BR    10
*
PAT1     DC    X'4020202021204B2020'      7 digits, ZZZ9.99 style
PAT2     DC    X'5C20202021204B2020C3D9'  check protect, CR
PKA      DC    PL4'1234567'
PKZ      DC    PL4'0'
PKN      DC    PL4'-1234'
PKS      DC    PL4'5'
STR      DC    C'ABCDEFGHIJ'
TRTAB    DC    195X'00',X'07',60X'00'     non-zero only at X'C3'
MV1      DC    X'12345678'
MV2      DC    X'ABCD'
SP1      DC    PL4'12345'
SP2      DC    PL4'1234567'
SP3      DC    PL4'-1234565'
         DS    0D
DW1      DC    F'0',F'12345'
DW2      DC    F'-1',F'-100'
DW3      DC    X'40000000',X'00000000'
MAXF     DC    X'7FFFFFFF'
ONE      DC    F'1'
P999     DC    PL2'999'
P1       DC    PL1'1'
ZD1      DC    C'12345'
DWP      DC    PL8'-98765'
NEG      DC    F'-42'
         END
