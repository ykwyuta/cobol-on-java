      *-----------------------------------------------------------------
      * CBLPRNT - carriage control and LINAGE (P-063, P-072, P-088)
      * Q: (1) Does a file written WITH ADVANCING get a control byte in
      *        front of each record, and is LRECL one byte longer (ADV)?
      *    (2) Which control byte do ADVANCING 0 / PAGE
      *        give? (C01 / C02: see CBLPRNC)
      *    (3) After OPEN, is LINAGE-COUNTER 4 or 5 after the first
      *        WRITE AFTER ADVANCING 4?
      *    (4) LINAGE-COUNTER and END-OF-PAGE at the page end.
      * DD PRTF and LINF have no DCB, so the compiler decides
      * RECFM and LRECL.
      * Variants: ADV (default) / NOADV
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLPRNT.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT PRTF ASSIGN TO PRTF FILE STATUS IS W-FS1.
           SELECT LINF ASSIGN TO LINF FILE STATUS IS W-FS2.
       DATA DIVISION.
       FILE SECTION.
       FD  PRTF
           RECORDING MODE IS F.
       01  PRTF-REC            PIC X(20).
       FD  LINF
           LINAGE IS 10 LINES
               WITH FOOTING AT 8
               LINES AT TOP 2
               LINES AT BOTTOM 2
           RECORDING MODE IS F.
       01  LINF-REC            PIC X(20).
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-FS1               PIC X(2).
       01  W-FS2               PIC X(2).
       01  W-LC                PIC 9(4).
       01  W-I                 PIC 9(2).
       01  W-IX REDEFINES W-I  PIC X(2).
       01  W-EOP               PIC X VALUE "N".
       PROCEDURE DIVISION.
       MAIN-PARA.
           PERFORM PRINT-FILE
           PERFORM LINAGE-FILE
           GOBACK.
      *
      * (1)(2) one line per kind of advancing; the text names it
       PRINT-FILE.
           OPEN OUTPUT PRTF
           MOVE "P01 AFTER 1" TO PRTF-REC
           WRITE PRTF-REC AFTER ADVANCING 1 LINE
           MOVE "P02 AFTER 2" TO PRTF-REC
           WRITE PRTF-REC AFTER ADVANCING 2 LINES
           MOVE "P03 AFTER 3" TO PRTF-REC
           WRITE PRTF-REC AFTER ADVANCING 3 LINES
           MOVE "P04 AFTER 0" TO PRTF-REC
           WRITE PRTF-REC AFTER ADVANCING 0 LINES
           MOVE "P05 AFTER PAGE" TO PRTF-REC
           WRITE PRTF-REC AFTER ADVANCING PAGE
           MOVE "P06 BEFORE 2" TO PRTF-REC
           WRITE PRTF-REC BEFORE ADVANCING 2 LINES
           MOVE "P07 AFTER 1" TO PRTF-REC
           WRITE PRTF-REC AFTER ADVANCING 1 LINE
           MOVE "P10 NO ADVANCING" TO PRTF-REC
           WRITE PRTF-REC
           MOVE "R.P063.PRTF.FS" TO PRB-CASE
           MOVE W-FS1 TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           CLOSE PRTF.
      *
      * (3)(4) show LINAGE-COUNTER after each WRITE
       LINAGE-FILE.
           OPEN OUTPUT LINF
           MOVE LINAGE-COUNTER TO W-LC
           MOVE "R.P088.OPEN" TO PRB-CASE PERFORM SHOW-LC
           MOVE "L01 AFTER 4" TO LINF-REC
           WRITE LINF-REC AFTER ADVANCING 4 LINES
           MOVE LINAGE-COUNTER TO W-LC
           MOVE "R.P088.FIRST" TO PRB-CASE PERFORM SHOW-LC
           PERFORM VARYING W-I FROM 2 BY 1 UNTIL W-I > 9
               MOVE SPACES TO LINF-REC
               STRING "L" W-IX " AFTER 1" DELIMITED BY SIZE
                   INTO LINF-REC
               MOVE "N" TO W-EOP
               WRITE LINF-REC AFTER ADVANCING 1 LINE
                   AT END-OF-PAGE MOVE "Y" TO W-EOP
               END-WRITE
               MOVE LINAGE-COUNTER TO W-LC
               MOVE SPACES TO PRB-CASE
               STRING "R.P072.L" W-IX DELIMITED BY SIZE
                   INTO PRB-CASE
               PERFORM SHOW-LC
           END-PERFORM
           MOVE "L10 AFTER PAGE" TO LINF-REC
           WRITE LINF-REC AFTER ADVANCING PAGE
           MOVE LINAGE-COUNTER TO W-LC
           MOVE "R.P072.PAGE" TO PRB-CASE PERFORM SHOW-LC
           MOVE "R.P088.LINF.FS" TO PRB-CASE
           MOVE W-FS2 TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           CLOSE LINF.
       SHOW-LC.
           MOVE SPACES TO PRB-TEXT
           STRING "LC=" W-LC " EOP=" W-EOP DELIMITED BY SIZE
               INTO PRB-TEXT
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT.
       COPY PRBPD.
