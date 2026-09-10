       IDENTIFICATION DIVISION.
       PROGRAM-ID. ACC-PROCESS.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-DISP-ID        PIC 9(4).
       01  WS-DISP-BAL       PIC ZZZ,ZZ9.99.
       LINKAGE SECTION.
       01  LNK-ACC-ID        PIC 9(4).
       01  LNK-TX-TYPE       PIC X(1).
       01  LNK-TX-AMOUNT     PIC 9(5)V99.
       01  LNK-NEW-BAL       PIC 9(5)V99.
       01  LNK-STATUS        PIC X(2).
       PROCEDURE DIVISION USING LNK-ACC-ID
                                LNK-TX-TYPE
                                LNK-TX-AMOUNT
                                LNK-NEW-BAL
                                LNK-STATUS.
       MAIN-START.
           DISPLAY '--------------------------------------------------'
           MOVE LNK-ACC-ID TO WS-DISP-ID
           DISPLAY '[COBOL:ACC-PROCESS] Processing Account ID: '
                   WS-DISP-ID

      * 取引種別に応じた残高更新計算 (D: 預金, W: 引出)
           IF LNK-TX-TYPE = 'D'
               COMPUTE LNK-NEW-BAL = LNK-NEW-BAL + LNK-TX-AMOUNT
               MOVE 'OK' TO LNK-STATUS
           ELSE
               IF LNK-TX-AMOUNT > LNK-NEW-BAL
                   MOVE 'NS' TO LNK-STATUS
                   DISPLAY '[COBOL:ACC-PROCESS] INSUFFICIENT FUNDS!'
               ELSE
                   COMPUTE LNK-NEW-BAL = LNK-NEW-BAL - LNK-TX-AMOUNT
                   MOVE 'OK' TO LNK-STATUS
               END-IF
           END-IF

           MOVE LNK-NEW-BAL TO WS-DISP-BAL
           DISPLAY '[COBOL:ACC-PROCESS] New Balance Calculated: '
                   WS-DISP-BAL
           DISPLAY '--------------------------------------------------'
           GOBACK.
