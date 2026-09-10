       IDENTIFICATION DIVISION.
       PROGRAM-ID. GEN-TRANS.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT TXOUT ASSIGN TO TXOUTDD
               ORGANIZATION IS SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  TXOUT.
       01  TX-REC.
           05 TX-ID        PIC X(4).
           05 TX-REGION    PIC X(2).
           05 TX-AMOUNT    PIC 9(5).
           05 TX-STATUS    PIC X(1).
       WORKING-STORAGE SECTION.
       01  WS-EOF          PIC X(1) VALUE 'N'.
       PROCEDURE DIVISION.
       MAIN-START.
           OPEN OUTPUT TXOUT

      * レコード1: 東部(E1), 金額 01500, 有効(A)
           MOVE 'T001' TO TX-ID
           MOVE 'E1'   TO TX-REGION
           MOVE 01500  TO TX-AMOUNT
           MOVE 'A'    TO TX-STATUS
           WRITE TX-REC

      * レコード2: 西部(W1), 金額 00800, 有効(A)
           MOVE 'T002' TO TX-ID
           MOVE 'W1'   TO TX-REGION
           MOVE 00800  TO TX-AMOUNT
           MOVE 'A'    TO TX-STATUS
           WRITE TX-REC

      * レコード3: 東部(E1), 金額 03200, 取消(C)
           MOVE 'T003' TO TX-ID
           MOVE 'E1'   TO TX-REGION
           MOVE 03200  TO TX-AMOUNT
           MOVE 'C'    TO TX-STATUS
           WRITE TX-REC

      * レコード4: 北部(N1), 金額 00450, 有効(A)
           MOVE 'T004' TO TX-ID
           MOVE 'N1'   TO TX-REGION
           MOVE 00450  TO TX-AMOUNT
           MOVE 'A'    TO TX-STATUS
           WRITE TX-REC

      * レコード5: 東部(E1), 金額 02100, 有効(A)
           MOVE 'T005' TO TX-ID
           MOVE 'E1'   TO TX-REGION
           MOVE 02100  TO TX-AMOUNT
           MOVE 'A'    TO TX-STATUS
           WRITE TX-REC

           CLOSE TXOUT
           DISPLAY 'GEN-TRANS: 5 TRANSACTIONS GENERATED.'
           GOBACK.
