       IDENTIFICATION DIVISION.
       PROGRAM-ID. GEN-SALES.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT SALES-FILE ASSIGN TO OUTDD
               FILE STATUS IS WS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  SALES-FILE.
       COPY SALESREC.
       WORKING-STORAGE SECTION.
       01  WS-STATUS       PIC XX.
       01  WS-DATE         PIC X(6) VALUE '202600'.
       LINKAGE SECTION.
       01  PARM-AREA.
           05  PA-LEN      PIC S9(4) COMP.
           05  PA-TEXT     PIC X(6).
       PROCEDURE DIVISION USING PARM-AREA.
       MAIN-START.
           IF PA-LEN >= 6
               MOVE PA-TEXT(1:6) TO WS-DATE
           END-IF
           DISPLAY 'GEN-SALES: TARGET MONTH = ' WS-DATE.

           OPEN OUTPUT SALES-FILE.
           IF WS-STATUS NOT = '00'
               DISPLAY 'GEN-SALES: OPEN ERROR, STATUS=' WS-STATUS
               MOVE 12 TO RETURN-CODE
               STOP RUN
           END-IF.

      *>   Record 1: APPLE
           MOVE WS-DATE   TO SR-DATE.
           MOVE 1001      TO SR-ITEM-ID.
           MOVE 'APPLE'   TO SR-ITEM-NAME.
           MOVE 000150    TO SR-PRICE.
           MOVE 0020      TO SR-QTY.
           WRITE SALES-REC.

      *>   Record 2: BANANA
           MOVE WS-DATE   TO SR-DATE.
           MOVE 1002      TO SR-ITEM-ID.
           MOVE 'BANANA'  TO SR-ITEM-NAME.
           MOVE 000100    TO SR-PRICE.
           MOVE 0050      TO SR-QTY.
           WRITE SALES-REC.

      *>   Record 3: ORANGE
           MOVE WS-DATE   TO SR-DATE.
           MOVE 1003      TO SR-ITEM-ID.
           MOVE 'ORANGE'  TO SR-ITEM-NAME.
           MOVE 000120    TO SR-PRICE.
           MOVE 0035      TO SR-QTY.
           WRITE SALES-REC.

           CLOSE SALES-FILE.
           DISPLAY 'GEN-SALES: OUTPUT COMPLETED (3 RECORDS).'.
           MOVE 0 TO RETURN-CODE.
           STOP RUN.
