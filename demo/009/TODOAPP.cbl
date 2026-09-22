       IDENTIFICATION DIVISION.
       PROGRAM-ID. TODOAPP.
      *****************************************************************
      * DEMO 009  --  TODO LIST                                       *
      *                                                               *
      * BMS  : TODOSET / TODOMAP  (SEND MAP / RECEIVE MAP)            *
      * CICS : PSEUDO CONVERSATION (RETURN TRANSID COMMAREA)          *
      * SQL  : THE TODO TABLE LIVES IN H2 AND IS REACHED WITH         *
      *        EXEC SQL, INCLUDING A CURSOR OVER THE WHOLE LIST.      *
      *****************************************************************
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *    THE SYMBOLIC MAP IS BUILT FROM TODOSET.BMS AT COMPILE TIME.
       COPY TODOSET.
      *    AID KEYS (DFHENTER / DFHCLEAR / DFHPF3 ...).
       COPY DFHAID.
      *    THE SQL COMMUNICATION AREA.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL DECLARE TODO TABLE
               ( TODO_ID   INTEGER      NOT NULL,
                 DONE_FLAG CHAR(1)      NOT NULL,
                 TODO_TEXT VARCHAR(60)  NOT NULL )
           END-EXEC.
      *    HOST VARIABLES.
       01  HV-ID                   PIC S9(4) COMP.
       01  HV-FLAG                 PIC X(1).
       01  HV-TEXT                 PIC X(60).
       01  HV-COUNT                PIC S9(4) COMP.
           EXEC SQL DECLARE TODOCUR CURSOR FOR
               SELECT TODO_ID, DONE_FLAG, TODO_TEXT
                 FROM TODO
                ORDER BY TODO_ID
           END-EXEC.
      *    WORK AREAS.
       01  WS-RESP                 PIC S9(8) COMP.
       01  WS-I                    PIC S9(4) COMP.
       01  WS-K                    PIC S9(4) COMP.
       01  WS-LEN                  PIC S9(4) COMP.
       01  WS-P                    PIC S9(4) COMP.
       01  WS-ROWS                 PIC S9(4) COMP.
       01  WS-EOF                  PIC X VALUE 'N'.
       01  WS-CMD                  PIC X(60).
       01  WS-VERB                 PIC X(8).
       01  WS-ARG                  PIC X(60).
       01  WS-NUM-OK               PIC X VALUE 'N'.
       01  WS-DIGITS               PIC X(4).
       01  WS-DIGITS-R REDEFINES WS-DIGITS.
           05  WS-NUMBER           PIC 9(4).
       01  WS-MESSAGE              PIC X(78).
       01  WS-BYE                  PIC X(46)
           VALUE 'TODO ended.  Start the TODO transaction again.'.
      *    A MESSAGE THAT CARRIES ONE ENTRY NUMBER.
       01  WS-HEAD                 PIC X(30).
       01  WS-NUM-EDIT             PIC ZZ9.
      *    ONE WHOLE SCREEN LINE.  THE COLUMN HEADING IN THE MAP USES
      *    THE SAME OFFSETS, SO THE TWO STAY ALIGNED.
       01  WS-LINE.
           05  FILLER              PIC X(2) VALUE SPACES.
           05  WL-NO               PIC ZZ9.
           05  FILLER              PIC X    VALUE SPACE.
           05  WL-ST               PIC X(3).
           05  FILLER              PIC X(2) VALUE SPACES.
           05  WL-TEXT             PIC X(60).
           05  FILLER              PIC X(8) VALUE SPACES.
      *    THE COUNTER LINE.
       01  WS-COUNTS.
           05  FILLER              PIC X(9) VALUE 'ENTRIES: '.
           05  WC-ALL              PIC ZZ9.
           05  FILLER              PIC X(9) VALUE '   OPEN: '.
           05  WC-OPEN             PIC ZZ9.
           05  FILLER              PIC X(9) VALUE '   DONE: '.
           05  WC-DONE             PIC ZZ9.
      *    THE TASK COUNTER COMES OUT OF THE COMMAREA, SO IT SHOWS THAT
      *    EACH SCREEN IS A SEPARATE CICS TASK.
           05  FILLER              PIC X(9) VALUE '   TASK: '.
           05  WC-TURN             PIC ZZ9.
           05  WC-NOTE             PIC X(30) VALUE SPACES.
      *    THE STATE CARRIED BETWEEN THE TASKS OF ONE CONVERSATION.
       01  WS-COMMAREA.
           05  CA-EYE              PIC X(4) VALUE 'TODO'.
           05  CA-TURNS            PIC S9(4) COMP VALUE 0.
       LINKAGE SECTION.
       01  DFHCOMMAREA.
           05  LK-EYE              PIC X(4).
           05  LK-TURNS            PIC S9(4) COMP.

       PROCEDURE DIVISION.
       A000-MAIN SECTION.
       A010-START.
           MOVE SPACES TO WS-MESSAGE
           IF EIBCALEN = 0
      *        THE FIRST TASK OF THE CONVERSATION: NO COMMAREA YET.
               MOVE 'TODO' TO CA-EYE
               MOVE 0 TO CA-TURNS
               MOVE 'Welcome.  Type a command and press ENTER.'
                   TO WS-MESSAGE
           ELSE
               MOVE LK-EYE TO CA-EYE
               MOVE LK-TURNS TO CA-TURNS
               ADD 1 TO CA-TURNS
               PERFORM B000-RECEIVE
           END-IF
           PERFORM C000-LOAD-LIST
           PERFORM D000-SEND-MAP
           EXEC CICS RETURN TRANSID('TODO') COMMAREA(WS-COMMAREA)
           END-EXEC
           GOBACK.

      *****************************************************************
      * READ THE TERMINAL AND RUN WHAT THE OPERATOR ASKED FOR.        *
      *****************************************************************
       B000-RECEIVE.
      *    PF3 LEAVES.  THE AID IS IN THE EIB EVEN WHEN NO FIELD WAS
      *    CHANGED, SO IT IS TESTED BEFORE THE RECEIVE.
           IF EIBAID = DFHPF3
               PERFORM Z000-QUIT
           END-IF
           MOVE SPACES TO WS-CMD
           EXEC CICS RECEIVE MAP('TODOMAP') MAPSET('TODOSET')
                INTO(TODOMAPI) RESP(WS-RESP)
           END-EXEC
           EVALUATE TRUE
               WHEN WS-RESP = DFHRESP(MAPFAIL)
                   MOVE 'Nothing was entered.  The list is redisplayed.'
                       TO WS-MESSAGE
               WHEN WS-RESP NOT = DFHRESP(NORMAL)
                   MOVE 'The terminal input could not be mapped.'
                       TO WS-MESSAGE
               WHEN EIBAID = DFHCLEAR
                   MOVE 'Screen redisplayed.' TO WS-MESSAGE
               WHEN OTHER
                   MOVE CMDI TO WS-CMD
                   PERFORM E000-RUN-COMMAND
           END-EVALUATE.

      *****************************************************************
      * SPLIT THE COMMAND LINE INTO A VERB AND THE REST OF THE LINE.  *
      *****************************************************************
       E000-RUN-COMMAND.
           MOVE SPACES TO WS-VERB
           MOVE SPACES TO WS-ARG
           MOVE 0 TO WS-LEN
           INSPECT WS-CMD TALLYING WS-LEN
               FOR CHARACTERS BEFORE INITIAL SPACE
           IF WS-LEN = 0
               MOVE 'Enter a command.  The list is redisplayed.'
                   TO WS-MESSAGE
           ELSE
               IF WS-LEN > 8
                   MOVE 8 TO WS-K
               ELSE
                   MOVE WS-LEN TO WS-K
               END-IF
               MOVE WS-CMD(1:WS-K) TO WS-VERB
               MOVE FUNCTION UPPER-CASE(WS-VERB) TO WS-VERB
               IF WS-LEN < 60
                   COMPUTE WS-K = WS-LEN + 1
                   COMPUTE WS-I = 60 - WS-LEN
                   MOVE WS-CMD(WS-K:WS-I) TO WS-ARG
               END-IF
               PERFORM E100-STRIP-ARGUMENT
               PERFORM E200-DISPATCH
           END-IF.

      *    DROP THE BLANKS BETWEEN THE VERB AND ITS ARGUMENT.
       E100-STRIP-ARGUMENT.
           MOVE 0 TO WS-LEN
           INSPECT WS-ARG TALLYING WS-LEN FOR LEADING SPACE
           IF WS-LEN > 0 AND WS-LEN < 60
               COMPUTE WS-K = WS-LEN + 1
               COMPUTE WS-I = 60 - WS-LEN
               MOVE WS-ARG(WS-K:WS-I) TO HV-TEXT
           ELSE
               IF WS-LEN = 0
                   MOVE WS-ARG TO HV-TEXT
               ELSE
                   MOVE SPACES TO HV-TEXT
               END-IF
           END-IF
           MOVE HV-TEXT TO WS-ARG.

       E200-DISPATCH.
           EVALUATE WS-VERB
               WHEN 'ADD'
                   PERFORM F000-ADD
               WHEN 'DONE'
                   MOVE 'Y' TO HV-FLAG
                   PERFORM G000-SET-FLAG
               WHEN 'OPEN'
                   MOVE 'N' TO HV-FLAG
                   PERFORM G000-SET-FLAG
               WHEN 'DEL'
                   PERFORM H000-DELETE
               WHEN 'LIST'
                   MOVE 'List refreshed.' TO WS-MESSAGE
               WHEN OTHER
                   MOVE 'Unknown command.  Try ADD, DONE, OPEN, DEL.'
                       TO WS-MESSAGE
           END-EVALUATE.

      *****************************************************************
      * ADD  <TEXT>                                                   *
      *****************************************************************
       F000-ADD.
           IF WS-ARG = SPACES
               MOVE 'ADD needs the text of the task.' TO WS-MESSAGE
           ELSE
      *        THE NUMBERS ARE HANDED OUT BY THE DATABASE, NOT BY THE
      *        PROGRAM, SO TWO TERMINALS CANNOT PICK THE SAME ONE.
               EXEC SQL
                   SELECT COALESCE(MAX(TODO_ID), 0) + 1
                     INTO :HV-ID
                     FROM TODO
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'The TODO table could not be read.'
                       TO WS-MESSAGE
               ELSE
                   MOVE WS-ARG TO HV-TEXT
                   MOVE 'N' TO HV-FLAG
                   EXEC SQL
                       INSERT INTO TODO
                           ( TODO_ID, DONE_FLAG, TODO_TEXT )
                       VALUES ( :HV-ID, :HV-FLAG, :HV-TEXT )
                   END-EXEC
                   IF SQLCODE = 0
                       MOVE 'Added entry' TO WS-HEAD
                       PERFORM K000-NUMBER-MESSAGE
                   ELSE
                       MOVE 'The entry could not be stored.'
                           TO WS-MESSAGE
                   END-IF
               END-IF
           END-IF.

      *****************************************************************
      * DONE <N> / OPEN <N>                                           *
      *****************************************************************
       G000-SET-FLAG.
           PERFORM J000-READ-NUMBER
           IF WS-NUM-OK = 'N'
               MOVE 'That command needs an entry number.' TO WS-MESSAGE
           ELSE
               EXEC SQL
                   UPDATE TODO SET DONE_FLAG = :HV-FLAG
                    WHERE TODO_ID = :HV-ID
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'The entry could not be changed.' TO WS-MESSAGE
               ELSE
                   IF SQLERRD(3) = 0
                       MOVE 'No entry has number' TO WS-HEAD
                   ELSE
                       IF HV-FLAG = 'Y'
                           MOVE 'Closed entry' TO WS-HEAD
                       ELSE
                           MOVE 'Reopened entry' TO WS-HEAD
                       END-IF
                   END-IF
                   PERFORM K000-NUMBER-MESSAGE
               END-IF
           END-IF.

      *****************************************************************
      * DEL <N>                                                       *
      *****************************************************************
       H000-DELETE.
           PERFORM J000-READ-NUMBER
           IF WS-NUM-OK = 'N'
               MOVE 'DEL needs an entry number.' TO WS-MESSAGE
           ELSE
               EXEC SQL
                   DELETE FROM TODO WHERE TODO_ID = :HV-ID
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'The entry could not be deleted.' TO WS-MESSAGE
               ELSE
                   IF SQLERRD(3) = 0
                       MOVE 'No entry has number' TO WS-HEAD
                   ELSE
                       MOVE 'Deleted entry' TO WS-HEAD
                   END-IF
                   PERFORM K000-NUMBER-MESSAGE
               END-IF
           END-IF.

      *    BUILD "<HEAD> <N>." WITHOUT THE PADDING OF THE TWO FIELDS.
       K000-NUMBER-MESSAGE.
           MOVE 0 TO WS-LEN
           PERFORM VARYING WS-K FROM 1 BY 1 UNTIL WS-K > 30
               IF WS-HEAD(WS-K:1) NOT = SPACE
                   MOVE WS-K TO WS-LEN
               END-IF
           END-PERFORM
           MOVE HV-ID TO WS-NUM-EDIT
           MOVE 0 TO WS-I
           INSPECT WS-NUM-EDIT TALLYING WS-I FOR LEADING SPACE
           COMPUTE WS-P = WS-I + 1
           COMPUTE WS-I = 3 - WS-I
           MOVE SPACES TO WS-MESSAGE
           STRING WS-HEAD(1:WS-LEN)              DELIMITED BY SIZE
                  ' '                            DELIMITED BY SIZE
                  WS-NUM-EDIT(WS-P:WS-I)         DELIMITED BY SIZE
                  '.'                            DELIMITED BY SIZE
               INTO WS-MESSAGE
           END-STRING.

      *    READ AN ENTRY NUMBER OUT OF THE ARGUMENT.
       J000-READ-NUMBER.
           MOVE 'N' TO WS-NUM-OK
           MOVE 0 TO HV-ID
           MOVE 0 TO WS-LEN
           INSPECT WS-ARG TALLYING WS-LEN
               FOR CHARACTERS BEFORE INITIAL SPACE
           IF WS-LEN > 0 AND WS-LEN < 5
               IF WS-ARG(1:WS-LEN) IS NUMERIC
      *            RIGHT ALIGN THE DIGITS INSIDE A FOUR DIGIT FIELD.
                   MOVE '0000' TO WS-DIGITS
                   COMPUTE WS-K = 5 - WS-LEN
                   MOVE WS-ARG(1:WS-LEN) TO WS-DIGITS(WS-K:WS-LEN)
                   MOVE WS-NUMBER TO HV-ID
                   MOVE 'Y' TO WS-NUM-OK
               END-IF
           END-IF.

      *****************************************************************
      * READ THE WHOLE LIST WITH A CURSOR AND FORMAT THE MAP LINES.   *
      *****************************************************************
       C000-LOAD-LIST.
      *    LOW-VALUES IN THE OUTPUT MAP MEANS "LEAVE THIS FIELD ALONE".
      *    IT ALSO CLEARS THE ATTRIBUTE BYTES THAT THE RECEIVE LEFT IN
      *    THE SAME STORAGE, BECAUSE TODOMAPO REDEFINES TODOMAPI.
           MOVE LOW-VALUES TO TODOMAPO
           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 8
               MOVE SPACES TO LINEO(WS-I)
           END-PERFORM
           MOVE 0 TO WS-ROWS
           MOVE SPACES TO WC-NOTE
           EXEC SQL OPEN TODOCUR END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'The TODO table could not be opened.' TO WS-MESSAGE
           ELSE
               MOVE 'N' TO WS-EOF
               PERFORM UNTIL WS-EOF = 'Y'
                   EXEC SQL
                       FETCH TODOCUR INTO :HV-ID, :HV-FLAG, :HV-TEXT
                   END-EXEC
                   EVALUATE TRUE
                       WHEN SQLCODE = 100
                           MOVE 'Y' TO WS-EOF
                       WHEN SQLCODE NOT = 0
                           MOVE 'Y' TO WS-EOF
                           MOVE 'The TODO table could not be read.'
                               TO WS-MESSAGE
                       WHEN OTHER
                           ADD 1 TO WS-ROWS
                           IF WS-ROWS < 9
                               PERFORM C100-FORMAT-LINE
                           END-IF
                   END-EVALUATE
               END-PERFORM
               EXEC SQL CLOSE TODOCUR END-EXEC
           END-IF
           IF WS-ROWS > 8
               MOVE '   (first 8 entries shown)' TO WC-NOTE
           END-IF
           PERFORM C200-COUNT.

       C100-FORMAT-LINE.
           MOVE HV-ID TO WL-NO
           IF HV-FLAG = 'Y'
               MOVE '[X]' TO WL-ST
           ELSE
               MOVE '[ ]' TO WL-ST
           END-IF
           MOVE HV-TEXT TO WL-TEXT
           MOVE WS-LINE TO LINEO(WS-ROWS).

       C200-COUNT.
           MOVE CA-TURNS TO WC-TURN
           MOVE WS-ROWS TO WC-ALL
           MOVE 0 TO WC-OPEN
           MOVE 0 TO WC-DONE
           EXEC SQL
               SELECT COUNT(*) INTO :HV-COUNT
                 FROM TODO WHERE DONE_FLAG = 'N'
           END-EXEC
           IF SQLCODE = 0
               MOVE HV-COUNT TO WC-OPEN
               COMPUTE HV-COUNT = WS-ROWS - HV-COUNT
               MOVE HV-COUNT TO WC-DONE
           END-IF.

      *****************************************************************
      * PAINT THE MAP.                                                *
      *****************************************************************
       D000-SEND-MAP.
           MOVE WS-MESSAGE TO MSGO
           MOVE WS-COUNTS TO CNTO
           MOVE SPACES TO CMDO
      *    -1 IN THE LENGTH FIELD PUTS THE CURSOR ON THE COMMAND LINE.
           MOVE -1 TO CMDL
           EXEC CICS SEND MAP('TODOMAP') MAPSET('TODOSET')
                FROM(TODOMAPO) ERASE CURSOR FREEKB
                RESP(WS-RESP)
           END-EXEC.

       Z000-QUIT.
           EXEC CICS SEND TEXT FROM(WS-BYE) ERASE FREEKB
           END-EXEC
           EXEC CICS RETURN END-EXEC.
