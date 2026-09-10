       IDENTIFICATION DIVISION.
       PROGRAM-ID. PRT-REPORT.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT RPTIN ASSIGN TO RPTINDD
               ORGANIZATION IS SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  RPTIN.
       01  RPT-REC.
           05 RPT-ID       PIC X(4).
           05 RPT-REGION   PIC X(2).
           05 RPT-AMOUNT   PIC 9(5).
           05 RPT-STATUS   PIC X(1).
       WORKING-STORAGE SECTION.
       01  WS-EOF          PIC X(1) VALUE 'N'.
       01  WS-COUNT        PIC 9(4) VALUE 0.
       01  WS-TOTAL        PIC 9(8) VALUE 0.
       01  WS-DISP-CNT     PIC Z,ZZ9.
       01  WS-DISP-TOT     PIC ZZZ,ZZZ,ZZ9.
       PROCEDURE DIVISION.
       MAIN-START.
           OPEN INPUT RPTIN
           DISPLAY '======================================'
           DISPLAY '  SORTED & FILTERED REPORT (E1 & A)   '
           DISPLAY '======================================'
           DISPLAY 'ID   REGION  AMOUNT  STATUS'
           DISPLAY '---- ------  ------  ------'

           PERFORM UNTIL WS-EOF = 'Y'
               READ RPTIN
                   AT END
                       MOVE 'Y' TO WS-EOF
                   NOT AT END
                       DISPLAY RPT-ID '   ' RPT-REGION '    '
                               RPT-AMOUNT '   ' RPT-STATUS
                       ADD 1 TO WS-COUNT
                       ADD RPT-AMOUNT TO WS-TOTAL
               END-READ
           END-PERFORM

           CLOSE RPTIN

           MOVE WS-COUNT TO WS-DISP-CNT
           MOVE WS-TOTAL TO WS-DISP-TOT
           DISPLAY '--------------------------------------'
           DISPLAY 'TOTAL RECORDS: ' WS-DISP-CNT
           DISPLAY 'TOTAL AMOUNT : ' WS-DISP-TOT
           DISPLAY '======================================'
           GOBACK.
