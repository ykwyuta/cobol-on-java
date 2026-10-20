      *****************************************************************
      * DEMO 011  --  GREETING ON A 3270 TERMINAL (TN3270)            *
      *                                                               *
      * BMS  : GRTSET / GRTMAP  (SEND MAP / RECEIVE MAP)              *
      * CICS : PSEUDO CONVERSATION (RETURN TRANSID COMMAREA)          *
      * KEYS : ENTER GREETS, CLEAR AND AN EMPTY ENTER GIVE MAPFAIL,   *
      *        PF3 ENDS, THE NAME 'ABEND' ENDS WITH ABEND GRTA        *
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. GREET.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY GRTSET.
       COPY DFHAID.
       01  WS-RESP  PIC S9(8) COMP.
       01  WS-COUNT PIC 9(4).
       01  WS-MSG   PIC X(40) VALUE SPACES.
       01  WS-BYE   PIC X(26) VALUE 'GOODBYE FROM COBOL ON JAVA'.
       01  WS-AREA.
           05 WS-AREA-COUNT PIC 9(4).
       LINKAGE SECTION.
       01  DFHCOMMAREA.
           05 LK-COUNT PIC 9(4).
       PROCEDURE DIVISION.
       MAIN-PARA.
           IF EIBCALEN = 0
               MOVE 0 TO WS-COUNT
               MOVE 'TYPE YOUR NAME AND PRESS ENTER' TO WS-MSG
               PERFORM SHOW-MAP
           END-IF
           MOVE LK-COUNT TO WS-COUNT
           IF EIBAID = DFHPF3
               EXEC CICS SEND TEXT FROM(WS-BYE) ERASE FREEKB END-EXEC
               EXEC CICS RETURN END-EXEC
           END-IF
           EXEC CICS RECEIVE MAP('GRTMAP') MAPSET('GRTSET')
                INTO(GRTMAPI) RESP(WS-RESP)
           END-EXEC
           IF WS-RESP = DFHRESP(MAPFAIL)
               MOVE 'PLEASE TYPE A NAME' TO WS-MSG
           ELSE
               IF NAMEI = 'ABEND'
                   EXEC CICS ABEND ABCODE('GRTA') END-EXEC
               END-IF
               STRING 'HELLO, ' DELIMITED BY SIZE
                      NAMEI DELIMITED BY SPACE
                      '!' DELIMITED BY SIZE INTO WS-MSG
           END-IF
           PERFORM SHOW-MAP.
       SHOW-MAP.
           ADD 1 TO WS-COUNT
           MOVE LOW-VALUES TO GRTMAPO
           MOVE EIBTRMID TO TERMO
           MOVE WS-MSG TO MSGO
           MOVE WS-COUNT TO CNTO
           EXEC CICS SEND MAP('GRTMAP') MAPSET('GRTSET')
                FROM(GRTMAPO) ERASE
           END-EXEC
           MOVE WS-COUNT TO WS-AREA-COUNT
           EXEC CICS RETURN TRANSID('GRT1') COMMAREA(WS-AREA)
           END-EXEC.
