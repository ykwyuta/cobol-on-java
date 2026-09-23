       IDENTIFICATION DIVISION.
       PROGRAM-ID. PRT-SALES.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT SALES-FILE ASSIGN TO INDD
               FILE STATUS IS WS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  SALES-FILE.
       COPY SALESREC.
       WORKING-STORAGE SECTION.
       01  WS-STATUS       PIC XX.
       01  WS-EOF          PIC X VALUE 'N'.
       01  WS-SUBTOTAL     PIC 9(10) VALUE 0.
       01  WS-GRAND-TOTAL  PIC 9(10) VALUE 0.
       01  WS-TOTAL-QTY    PIC 9(6)  VALUE 0.
       01  WS-REC-COUNT    PIC 9(4)  VALUE 0.
       01  WS-DISP-PRICE   PIC ZZ,ZZ9.
       01  WS-DISP-QTY     PIC Z,ZZ9.
       01  WS-DISP-SUB     PIC ZZZ,ZZZ,ZZ9.
       01  WS-DISP-TOTAL   PIC ZZZ,ZZZ,ZZ9.
       01  WS-DISP-ALL-QTY PIC ZZZ,ZZ9.
       PROCEDURE DIVISION.
       MAIN-START.
           OPEN INPUT SALES-FILE.
           IF WS-STATUS NOT = '00'
               DISPLAY 'PRT-SALES: OPEN ERROR, STATUS=' WS-STATUS
               MOVE 8 TO RETURN-CODE
               STOP RUN
           END-IF.

           DISPLAY '=================================================='.
           DISPLAY '             MONTHLY SALES REPORT                 '.
           DISPLAY '=================================================='.
           DISPLAY 'DATE   ID   NAME         PRICE    QTY      SUBTOTAL'.
           DISPLAY '------ ---- ------------ ------ ----- ------------'.

           PERFORM UNTIL WS-EOF = 'Y'
               READ SALES-FILE
                   AT END
                       MOVE 'Y' TO WS-EOF
                   NOT AT END
                       ADD 1 TO WS-REC-COUNT
                       COMPUTE WS-SUBTOTAL = SR-PRICE * SR-QTY
                       ADD WS-SUBTOTAL TO WS-GRAND-TOTAL
                       ADD SR-QTY TO WS-TOTAL-QTY

                       MOVE SR-PRICE    TO WS-DISP-PRICE
                       MOVE SR-QTY      TO WS-DISP-QTY
                       MOVE WS-SUBTOTAL TO WS-DISP-SUB

                       DISPLAY SR-DATE ' ' SR-ITEM-ID ' '
                               SR-ITEM-NAME ' ' WS-DISP-PRICE ' '
                               WS-DISP-QTY ' ' WS-DISP-SUB
               END-READ
           END-PERFORM.

           CLOSE SALES-FILE.

           MOVE WS-TOTAL-QTY    TO WS-DISP-ALL-QTY.
           MOVE WS-GRAND-TOTAL  TO WS-DISP-TOTAL.
           DISPLAY '--------------------------------------------------'.
           DISPLAY 'TOTAL RECORDS: ' WS-REC-COUNT.
           DISPLAY 'TOTAL QTY    : ' WS-DISP-ALL-QTY.
           DISPLAY 'GRAND TOTAL  : ' WS-DISP-TOTAL ' JPY'.
           DISPLAY '=================================================='.
           MOVE 0 TO RETURN-CODE.
           STOP RUN.
