*---------------------------------------------------------------------
* ASMDC1 - what DC puts in storage: character, decimal, binary and
*          address constants, doubled quotes and ampersands,
*          length attributes (P-171, P-175)
* Called by CBLASMD (SYSIN card 'ASMDC1,30'). The constant block is
* copied to the work area from offset 16; the code is ignored.
* The assembler listing (object code column) is a second
* observation of the same bytes.
* Scenario notes (Japanese): docs/zos-probe/scenarios.md
*---------------------------------------------------------------------
ASMDC1   CSECT
ASMDC1   AMODE 31
ASMDC1   RMODE ANY
         STM   14,12,12(13)
         LR    12,15
         USING ASMDC1,12
         L     9,0(0,1)
         LA    9,0(0,9)
         MVC   16(BLKLEN,9),BLK
         LM    14,12,12(13)
         SR    15,15
         BR    14
BLK      DS    0C
         DC    C'X''Y'             doubled quote: 3 bytes
         DC    C'&&'               doubled ampersand: 1 byte
         DC    X'0A0B'
         DC    P'-0'
         DC    P'+12'
         DC    PL3'5'
         DC    Z'-12'
         DC    ZL4'7'
         DC    F'-1'
         DC    H'-2'
         DC    FL3'1'
         DC    Y(258)
         DC    CL5'AB'
         DC    XL3'1'
         DC    B'101'
         DC    AL1(L'FLD7)         length attribute of FLD7
         DC    AL1(L'BLK)
         DC    AL2(FLD7-BLK)       an offset inside the block
FLD7     DC    CL7'SEVEN'
BLKLEN   EQU   *-BLK
         END
