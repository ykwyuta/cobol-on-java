      *-----------------------------------------------------------------
      * CBLBIN31 - binary items of 19 to 31 digits (P-005)
      * Q: Does the host compiler accept PIC S9(19)..S9(31) COMP under
      *    ARITH(EXTEND)? If so, how many bytes does the item take?
      *    If not, which message does it issue? The compile listing
      *    is the main observation.
      * Variants: ARITH(EXTEND) only
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLBIN31.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-B19.
           05  W-B19           PIC S9(19) COMP.
       01  G-B31.
           05  W-B31           PIC S9(31) COMP.
       01  W-LEN               PIC 9(4).
       PROCEDURE DIVISION.
       MAIN-PARA.
           MOVE 1234567890123456789 TO W-B19
           MOVE LENGTH OF G-B19 TO W-LEN
           MOVE "B.P005.B19" TO PRB-CASE
           MOVE G-B19 TO PRB-IN MOVE W-LEN TO PRB-LEN
           MOVE W-LEN TO PRB-TEXT PERFORM PRB-EMIT
           MOVE -1234567890123456789012345678901 TO W-B31
           MOVE LENGTH OF G-B31 TO W-LEN
           MOVE "B.P005.B31" TO PRB-CASE
           MOVE G-B31 TO PRB-IN MOVE W-LEN TO PRB-LEN
           MOVE W-LEN TO PRB-TEXT PERFORM PRB-EMIT
           GOBACK.
       COPY PRBPD.
