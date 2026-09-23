*---------------------------------------------------------------------
* ASMDC2 - floating-point constants (P-018)
*          This implementation refuses floating-point DC (design 27
*          section 5); the host gives the reference bytes.
* Q: How does HLASM round a decimal constant to HFP (E, D, L),
*    and what are the BFP (EB, DB) and DFP (ED, DD) encodings?
*    Compare with CBLFLT (COBOL COMP-1 / COMP-2 VALUE 0.1).
* Called by CBLASMD (SYSIN card 'ASMDC2,31'). The constant block is
* copied to the work area from offset 16; the code is ignored.
* Scenario notes (Japanese): docs/zos-probe/scenarios.md
*---------------------------------------------------------------------
ASMDC2   CSECT
ASMDC2   AMODE 31
ASMDC2   RMODE ANY
         STM   14,12,12(13)
         LR    12,15
         USING ASMDC2,12
         L     9,0(0,1)
         LA    9,0(0,9)
         MVC   16(BLKLEN,9),BLK
         LM    14,12,12(13)
         SR    15,15
         BR    14
         DS    0D
BLK      DS    0C
         DC    E'0.1'              offset 16
         DC    E'-0.1'             20
         DC    E'1'                24
         DC    E'1E-10'            28
         DC    D'0.1'              32
         DC    D'0.333333333333333333'  40
         DC    L'0.1'              48 (16 bytes)
         DC    EH'0.1'             64
         DC    EB'0.1'             68
         DC    DB'0.1'             72
         DC    ED'0.1'             80
         DC    DD'0.1'             84
BLKLEN   EQU   *-BLK
         END
