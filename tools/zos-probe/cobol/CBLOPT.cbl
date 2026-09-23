      *-----------------------------------------------------------------
      * CBLOPT - SELECT OPTIONAL on a PDS member that does not exist
      *          (P-057)
      * Q: With DD INF pointing at a missing member, does OPEN INPUT
      *    of an OPTIONAL file succeed (status 05) and READ give end
      *    of file (10), or does the system stop the step (S013)?
      * Compare with CBLOPTN, which is the same without OPTIONAL.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLOPT.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT OPTIONAL INF ASSIGN TO INF FILE STATUS IS W-FS.
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
           MOVE "O.P057.OPT.BEFORE" TO PRB-CASE
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           OPEN INPUT INF
           MOVE "O.P057.OPT.OPEN" TO PRB-CASE
           MOVE W-FS TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           READ INF
               AT END CONTINUE
           END-READ
           MOVE "O.P057.OPT.READ" TO PRB-CASE
           MOVE W-FS TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           CLOSE INF
           GOBACK.
       COPY PRBPD.
