*---------------------------------------------------------------------
* ASMDC3 - quotes next to a letter that is also an attribute (P-171)
*          This implementation reads only L' as an attribute and
*          fails on C'L''A'; the host gives the reference.
* Called by CBLASMD (SYSIN card 'ASMDC3,32'). The constant block is
* copied to the work area from offset 16; the code is ignored.
* Scenario notes (Japanese): docs/zos-probe/scenarios.md
*---------------------------------------------------------------------
ASMDC3   CSECT
ASMDC3   AMODE 31
ASMDC3   RMODE ANY
         STM   14,12,12(13)
         LR    12,15
         USING ASMDC3,12
         L     9,0(0,1)
         LA    9,0(0,9)
         MVC   16(BLKLEN,9),BLK
         LM    14,12,12(13)
         SR    15,15
         BR    14
BLK      DS    0C
         DC    C'L''A'
         DC    C'A''L'
         DC    C'T''K''D'
         DC    AL1(L'FLD)
         DC    C'L'
FLD      DC    CL3'ABC'
BLKLEN   EQU   *-BLK
         END
