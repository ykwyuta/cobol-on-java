*---------------------------------------------------------------------
* ASMPM - program mask and addressing mode at entry (P-174)
*         Host only: this implementation cannot assemble IPM / SPM.
* Entry: R1 -> one address -> a 256-byte work area (see CBLASMD).
* Codes:
*   20 at offset 16: R2 after SR 2,2 / IPM 2 (bits 2-3 of the first
*      byte are the condition code, bits 4-7 the program mask);
*      at offset 20: R14 as passed (bit 0 set = caller in AMODE 31);
*      at offset 24: R15 as passed; at offset 28: R13 as passed.
*   21 SPM with all four mask bits on, then A with overflow.
*      Expect S0C8. Shows whether the mask can be changed at all.
* Scenario notes (Japanese): docs/zos-probe/scenarios.md
*---------------------------------------------------------------------
ASMPM    CSECT
ASMPM    AMODE 31
ASMPM    RMODE ANY
         STM   14,12,12(13)
         LR    12,15
         USING ASMPM,12
         L     9,0(0,1)
         LA    9,0(0,9)
         SR    2,2
         IPM   2
         ST    2,16(0,9)
         ST    14,20(0,9)
         ST    15,24(0,9)
         ST    13,28(0,9)
         CLI   0(9),21
         BNE   RETURN
         L     2,MASKON
         SPM   2
         L     2,MAXF
         A     2,ONE
         ST    2,32(0,9)
RETURN   LM    14,12,12(13)
         SR    15,15
         BR    14
MASKON   DC    X'0F000000'    SPM takes the mask from bits 36-39
MAXF     DC    X'7FFFFFFF'
ONE      DC    F'1'
         END
