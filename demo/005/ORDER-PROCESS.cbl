       IDENTIFICATION DIVISION.
       PROGRAM-ID. ORDER-PROCESS.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-EXCHANGE-RATE   PIC 9(3)V99 VALUE 0.
       01  WS-CURRENCY        PIC X(3).
       01  WS-DISP-JPY        PIC ZZZ,ZZ9.
       01  WS-DISP-USD        PIC ZZZ,ZZ9.99.
       01  WS-DISP-RATE       PIC ZZ9.99.
       LINKAGE SECTION.
       01  LNK-ORDER-ID       PIC X(6).
       01  LNK-AMOUNT-JPY     PIC 9(6).
       01  LNK-AMOUNT-USD     PIC 9(6)V99.
       01  LNK-STATUS         PIC X(2).
       PROCEDURE DIVISION USING LNK-ORDER-ID
                                LNK-AMOUNT-JPY
                                LNK-AMOUNT-USD
                                LNK-STATUS.
       MAIN-START.
           DISPLAY '--------------------------------------------------'
           DISPLAY '[COBOL] ORDER-PROCESS STARTED for Order: '
                   LNK-ORDER-ID
           MOVE LNK-AMOUNT-JPY TO WS-DISP-JPY
           DISPLAY '[COBOL] Input Amount (JPY): ' WS-DISP-JPY

      * Java 側の為替レートサービス (FXSERVICE) を CALL
           MOVE 'USD' TO WS-CURRENCY
           CALL 'FXSERVICE' USING WS-CURRENCY WS-EXCHANGE-RATE

           MOVE WS-EXCHANGE-RATE TO WS-DISP-RATE
           DISPLAY '[COBOL] Acquired Exchange Rate from Java: '
                   WS-DISP-RATE

      * 米ドル金額を計算
           COMPUTE LNK-AMOUNT-USD = LNK-AMOUNT-JPY / WS-EXCHANGE-RATE
           MOVE LNK-AMOUNT-USD TO WS-DISP-USD
           DISPLAY '[COBOL] Converted Amount (USD)  : ' WS-DISP-USD

           MOVE 'OK' TO LNK-STATUS
           DISPLAY '[COBOL] ORDER-PROCESS COMPLETED (Status: OK)'
           DISPLAY '--------------------------------------------------'
           GOBACK.
