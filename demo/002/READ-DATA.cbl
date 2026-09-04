       IDENTIFICATION DIVISION.
       PROGRAM-ID. READ-DATA.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CUST-FILE ASSIGN TO CUSTFILE
               FILE STATUS IS WS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  CUST-FILE.
       01  CUST-REC.
           05  CUST-ID    PIC 9(4).
           05  CUST-NAME  PIC X(10).
           05  CUST-SALES PIC 9(6).
       WORKING-STORAGE SECTION.
       01  WS-STATUS      PIC XX.
       01  WS-EOF         PIC X VALUE 'N'.
       01  WS-REC-COUNT   PIC 9(3) VALUE 0.
       01  WS-TOTAL-SALES PIC 9(8) VALUE 0.
       01  WS-DISP-SALES  PIC ZZ,ZZZ,ZZ9.
       PROCEDURE DIVISION.
       MAIN-START.
           OPEN INPUT CUST-FILE.
           DISPLAY 'OPEN INPUT STATUS: ' WS-STATUS.
           DISPLAY '--- CUSTOMER RECORDS ---'.
           PERFORM UNTIL WS-EOF = 'Y'
               READ CUST-FILE
                   AT END
                       MOVE 'Y' TO WS-EOF
                   NOT AT END
                       ADD 1 TO WS-REC-COUNT
                       ADD CUST-SALES TO WS-TOTAL-SALES
                       MOVE CUST-SALES TO WS-DISP-SALES
                       DISPLAY 'ID=' CUST-ID ' NAME=' CUST-NAME
                               ' SALES=' WS-DISP-SALES
               END-READ
           END-PERFORM.
           CLOSE CUST-FILE.
           MOVE WS-TOTAL-SALES TO WS-DISP-SALES.
           DISPLAY '------------------------'.
           DISPLAY 'TOTAL RECORDS: ' WS-REC-COUNT.
           DISPLAY 'TOTAL SALES  : ' WS-DISP-SALES.
           STOP RUN.
