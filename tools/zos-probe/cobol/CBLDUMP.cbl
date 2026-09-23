      *-----------------------------------------------------------------
      * CBLDUMP - read DD INF (80-byte records) and show each record
      *           (used by the JCL probes to see what a step wrote)
      * SYSIN card: 'tag'. Shows "PRB U.tag.nnnn |first 40 bytes|"
      * for up to 99 records, then "PRB U.tag.COUNT |n|".
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLDUMP.
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
       01  W-TAG               PIC X(8).
       01  W-N                 PIC 9(4) VALUE 0.
       01  W-EOF               PIC X VALUE "N".
       01  W-CARD              PIC X(80) VALUE SPACES.
       PROCEDURE DIVISION.
       MAIN-PARA.
      * The argument comes on a SYSIN card, not in PARM: this
      * implementation accepts a PARM only of exactly the declared
      * length (P-090; see CBLPARM).
           ACCEPT W-CARD
           MOVE SPACES TO W-TAG
           UNSTRING W-CARD DELIMITED BY SPACE INTO W-TAG
           END-UNSTRING
           OPEN INPUT INF
           PERFORM UNTIL W-EOF = "Y"
               READ INF
                   AT END MOVE "Y" TO W-EOF
                   NOT AT END
                       ADD 1 TO W-N
                       IF W-N < 100
                           MOVE SPACES TO PRB-CASE
                           STRING "U." W-TAG DELIMITED BY SPACE
                                  "." W-N DELIMITED BY SIZE
                                  INTO PRB-CASE
                           MOVE INF-REC(1:40) TO PRB-TEXT
                           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
                       END-IF
               END-READ
           END-PERFORM
           CLOSE INF
           MOVE SPACES TO PRB-CASE
           STRING "U." W-TAG DELIMITED BY SPACE
                  ".COUNT" DELIMITED BY SIZE INTO PRB-CASE
           MOVE W-N TO PRB-TEXT
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           GOBACK.
       COPY PRBPD.
