       IDENTIFICATION DIVISION.
       PROGRAM-ID. CICSMENU.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-COMM           PIC X(16).
       LINKAGE SECTION.
       01  DFHCOMMAREA       PIC X(16).
       PROCEDURE DIVISION USING DFHCOMMAREA.
       MAIN-START.
           DISPLAY '=================================================='
           DISPLAY '[CICS:CICSMENU] Trans: MENU - Menu Program Started'

      * 1. 照会プログラム INQPROG を LINK で呼び出し
           DISPLAY '[CICS:CICSMENU] LINKing to INQPROG with COMMAREA...'
           MOVE 'ACTION=INQ;ID=01' TO WS-COMM
           EXEC CICS LINK PROGRAM('INQPROG')
                     COMMAREA(WS-COMM)
                     LENGTH(16)
           END-EXEC
           DISPLAY '[CICS:CICSMENU] Returned from INQPROG. Result: '
                   WS-COMM

      * 2. 更新・完了プログラム FINPROG へ XCTL で制御遷移
           DISPLAY '[CICS:CICSMENU] XCTL to FINPROG...'
           MOVE 'ACTION=FIN;ID=01' TO WS-COMM
           EXEC CICS XCTL PROGRAM('FINPROG')
                     COMMAREA(WS-COMM)
                     LENGTH(16)
           END-EXEC

           DISPLAY '[CICS:CICSMENU] SHOULD NOT REACH HERE AFTER XCTL'
           GOBACK.
