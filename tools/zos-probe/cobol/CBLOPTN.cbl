      *-----------------------------------------------------------------
      * CBLOPTN - CBLOPT without OPTIONAL (P-057)
      * Q: With DD INF pointing at a missing member, does OPEN INPUT
      *    give status 35, or does the system stop the step (S013)?
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLOPTN.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT INF ASSIGN TO INF FILE STATUS IS W-FS.
       DATA DIVISION.
       FILE SECTION.
       FD  INF
           RECORDING MODE IS F.
       01  INF-REC             PIC X(80).
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-FS                PIC X(2).
       PROCEDURE DIVISION.
       MAIN-PARA.
           MOVE "O.P057.NOPT.BEFORE" TO PRB-CASE
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           OPEN INPUT INF
           MOVE "O.P057.NOPT.OPEN" TO PRB-CASE
           MOVE W-FS TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           IF W-FS = "00"
               CLOSE INF
           END-IF
           GOBACK.
       COPY PRBPD.
