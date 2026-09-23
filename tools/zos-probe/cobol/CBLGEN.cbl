      *-----------------------------------------------------------------
      * CBLGEN - write N records of 80 bytes to DD OUTF (used by the
      *          JCL probes for DISP, GDG and space abends)
      * SYSIN card: 'count,tag[,ABEND]'. Each record is 'tag nnnnnnnnn'
      * and spaces.
      * At the end it shows "PRB G.tag |WROTE n FS=xx|". A step that
      * abends for lack of space does not show this line.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLGEN.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT OUTF ASSIGN TO OUTF FILE STATUS IS W-FS.
       DATA DIVISION.
       FILE SECTION.
       FD  OUTF
           RECORDING MODE IS F.
       01  OUTF-REC            PIC X(80).
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-FS                PIC X(2).
       01  W-COUNTX            PIC X(9).
       01  W-TAG               PIC X(8).
       01  W-MODE              PIC X(8).
       01  G-BAD.
           05  W-BAD           PIC S9(3) COMP-3.
       01  G-BADX REDEFINES G-BAD PIC X(2).
       01  W-COUNT             PIC 9(9).
       01  W-I                 PIC 9(9).
       01  W-CARD              PIC X(80) VALUE SPACES.
       PROCEDURE DIVISION.
       MAIN-PARA.
      * The argument comes on a SYSIN card, not in PARM: this
      * implementation accepts a PARM only of exactly the declared
      * length (P-090; see CBLPARM).
           ACCEPT W-CARD
           MOVE SPACES TO W-COUNTX W-TAG W-MODE
           UNSTRING W-CARD DELIMITED BY "," OR SPACE
               INTO W-COUNTX W-TAG W-MODE
           END-UNSTRING
           COMPUTE W-COUNT = FUNCTION NUMVAL(W-COUNTX)
           OPEN OUTPUT OUTF
           PERFORM VARYING W-I FROM 1 BY 1 UNTIL W-I > W-COUNT
               MOVE SPACES TO OUTF-REC
               STRING W-TAG DELIMITED BY SPACE
                      " " W-I DELIMITED BY SIZE
                      INTO OUTF-REC
               END-STRING
               WRITE OUTF-REC
           END-PERFORM
           CLOSE OUTF
           MOVE SPACES TO PRB-CASE
           STRING "G." W-TAG DELIMITED BY SPACE INTO PRB-CASE
           SUBTRACT 1 FROM W-I
           STRING "WROTE " W-I " FS=" W-FS DELIMITED BY SIZE
               INTO PRB-TEXT
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
      * A third field ABEND ends the step with S0C7 after CLOSE, to
      * see the abnormal disposition of OUTF.
           IF W-MODE = "ABEND"
               MOVE X"1A3C" TO G-BADX
               ADD 1 TO W-BAD
           END-IF
           GOBACK.
       COPY PRBPD.
