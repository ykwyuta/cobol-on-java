      *-----------------------------------------------------------------
      * CBLPARM - a PARM shorter than the declared linkage item
      *           (P-090)
      * Q: The usual host idiom declares the PARM text as PIC X(100)
      *    and reads only the first L-LEN bytes. The host does not
      *    check the length. This implementation refuses the call
      *    unless the PARM is exactly 100 bytes. Run it with PARM
      *    'SHORT' and with no PARM at all.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLPARM.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-LEN               PIC 9(4).
       LINKAGE SECTION.
       01  L-PARM.
           05  L-LEN           PIC S9(4) COMP.
           05  L-TEXT          PIC X(100).
       PROCEDURE DIVISION USING L-PARM.
       MAIN-PARA.
           MOVE L-LEN TO W-LEN
           MOVE "K.P090.LEN" TO PRB-CASE
           MOVE W-LEN TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           MOVE "K.P090.TEXT" TO PRB-CASE
           IF L-LEN > 0
               MOVE L-TEXT(1:L-LEN) TO PRB-TEXT
           END-IF
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           GOBACK.
       COPY PRBPD.
