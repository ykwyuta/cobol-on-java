       IDENTIFICATION DIVISION.
       PROGRAM-ID. LOAN-APP.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-SCORE-RESULT    PIC 9(3) VALUE 0.
       01  WS-SCORE-STATUS    PIC X(2) VALUE 'OK'.
       01  WS-BASE-LIMIT      PIC 9(8) VALUE 05000000.
       01  WS-DISP-AMT        PIC Z,ZZZ,ZZ9.
       LINKAGE SECTION.
       01  LNK-CUSTOMER-ID    PIC X(6).
       01  LNK-APPROVED-AMT   PIC 9(8).
       01  LNK-DECISION       PIC X(1).
       PROCEDURE DIVISION USING LNK-CUSTOMER-ID
                                LNK-APPROVED-AMT
                                LNK-DECISION.
       MAIN-START.
           DISPLAY '=== LOAN-APP STARTED FOR CUST: '
                   LNK-CUSTOMER-ID

      * 外部サブルーチン SCOREAPI (信用スコア照会) を CALL
           CALL 'SCOREAPI' USING LNK-CUSTOMER-ID
                                 WS-SCORE-RESULT
                                 WS-SCORE-STATUS

      * 融資限度額の判定セクション
           PERFORM EVALUATE-LIMIT

           DISPLAY 'DECISION: ' LNK-DECISION
           DISPLAY '=== LOAN-APP ENDED ==='
           GOBACK.

       EVALUATE-LIMIT SECTION.
       EVAL-P.
           IF WS-SCORE-STATUS NOT = 'OK' OR WS-SCORE-RESULT < 500
               MOVE 'R' TO LNK-DECISION
               MOVE 0   TO LNK-APPROVED-AMT
           ELSE
               MOVE 'A' TO LNK-DECISION
               IF WS-SCORE-RESULT >= 800
                   COMPUTE LNK-APPROVED-AMT = WS-BASE-LIMIT * 2
               ELSE
                   MOVE WS-BASE-LIMIT TO LNK-APPROVED-AMT
               END-IF
           END-IF.
