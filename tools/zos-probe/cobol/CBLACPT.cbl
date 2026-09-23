      *-----------------------------------------------------------------
      * CBLACPT - ACCEPT after the end of SYSIN (P-083)
      * Q: After SYSIN is exhausted, does ACCEPT leave the receiver
      *    alone, store zero/spaces, or abend? Is an empty line treated
      *    the same as end of input?
      * SYSIN: line 1 '12345', line 2 all spaces, then nothing.
      * A BEFORE line precedes each ACCEPT so an abend shows progress.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLACPT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-N.
           05  W-N             PIC 9(5).
       01  G-A.
           05  W-A             PIC X(5).
       PROCEDURE DIVISION.
       MAIN-PARA.
      * Line 1: '12345' into a numeric receiver
           MOVE 77777 TO W-N
           MOVE "A.P083.1.BEFORE" TO PRB-CASE PERFORM MARK
           ACCEPT W-N
           MOVE "A.P083.1.NUM" TO PRB-CASE PERFORM SHOW-N
      * Line 2: an all-space line into a numeric receiver
           MOVE 77777 TO W-N
           MOVE "A.P083.2.BEFORE" TO PRB-CASE PERFORM MARK
           ACCEPT W-N
           MOVE "A.P083.2.NUM" TO PRB-CASE PERFORM SHOW-N
      * Third: input exhausted, numeric receiver
           MOVE 77777 TO W-N
           MOVE "A.P083.3.BEFORE" TO PRB-CASE PERFORM MARK
           ACCEPT W-N
           MOVE "A.P083.3.NUM" TO PRB-CASE PERFORM SHOW-N
      * Fourth: input exhausted, alphanumeric receiver
           MOVE "ZZZZZ" TO W-A
           MOVE "A.P083.4.BEFORE" TO PRB-CASE PERFORM MARK
           ACCEPT W-A
           MOVE "A.P083.4.ALNUM" TO PRB-CASE
           MOVE G-A TO PRB-IN MOVE 5 TO PRB-LEN
           MOVE W-A TO PRB-TEXT PERFORM PRB-EMIT
           MOVE "A.P083.END" TO PRB-CASE PERFORM MARK
           GOBACK.
       MARK.
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT.
       SHOW-N.
           MOVE G-N TO PRB-IN MOVE 5 TO PRB-LEN
           MOVE G-N TO PRB-TEXT PERFORM PRB-EMIT.
       COPY PRBPD.
