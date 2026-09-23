      *-----------------------------------------------------------------
      * CBLRC - a step that reports that it ran and ends with a given
      *         return code or with S0C7 (used by the JCL probes)
      * SYSIN card: 'tag,rc' (rc up to 4 digits) or 'tag,ABEND'.
      *   Card 'S01,4' shows "PRB J.S01 |RAN RC=0004|" and ends RC 4.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLRC.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-TAG               PIC X(8).
       01  W-ARG               PIC X(8).
       01  W-RC                PIC 9(4).
       01  G-BAD.
           05  W-BAD           PIC S9(3) COMP-3.
       01  G-BADX REDEFINES G-BAD PIC X(2).
       01  W-CARD              PIC X(80) VALUE SPACES.
       PROCEDURE DIVISION.
       MAIN-PARA.
      * The argument comes on a SYSIN card, not in PARM: this
      * implementation accepts a PARM only of exactly the declared
      * length (P-090; see CBLPARM).
           ACCEPT W-CARD
           MOVE SPACES TO W-TAG W-ARG
           UNSTRING W-CARD DELIMITED BY "," OR SPACE
               INTO W-TAG W-ARG
           END-UNSTRING
           MOVE SPACES TO PRB-CASE
           STRING "J." W-TAG DELIMITED BY SPACE INTO PRB-CASE
           IF W-ARG = "ABEND"
               MOVE "RAN ABEND" TO PRB-TEXT
               MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
               MOVE X"1A3C" TO G-BADX
               ADD 1 TO W-BAD
               GOBACK
           END-IF
           MOVE 0 TO W-RC
           IF W-ARG NOT = SPACES
               COMPUTE W-RC = FUNCTION NUMVAL(W-ARG)
           END-IF
           STRING "RAN RC=" W-RC DELIMITED BY SIZE INTO PRB-TEXT
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           MOVE W-RC TO RETURN-CODE
           GOBACK.
       COPY PRBPD.
