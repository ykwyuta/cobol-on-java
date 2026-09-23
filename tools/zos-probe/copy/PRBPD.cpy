      *-----------------------------------------------------------------
      * PRBPD: show the first PRB-LEN bytes of PRB-IN in hex as
      *        one line: PRB case-id hex |text|
      * The caller fills PRB-CASE/PRB-IN/PRB-LEN/PRB-TEXT and
      * PERFORMs PRB-EMIT. The text is informative; compare the hex.
      *-----------------------------------------------------------------
       PRB-EMIT.
           MOVE SPACES TO PRB-OUT
           PERFORM VARYING PRB-I FROM 1 BY 1 UNTIL PRB-I > PRB-LEN
               MOVE LOW-VALUE TO PRB-HI
               MOVE PRB-IN(PRB-I:1) TO PRB-LO
               DIVIDE PRB-HALF BY 16 GIVING PRB-Q REMAINDER PRB-R
               COMPUTE PRB-P = PRB-I * 2 - 1
               MOVE PRB-DIGITS(PRB-Q + 1:1) TO PRB-OUT(PRB-P:1)
               MOVE PRB-DIGITS(PRB-R + 1:1) TO PRB-OUT(PRB-P + 1:1)
           END-PERFORM
           MOVE SPACES TO PRB-LINE
           MOVE 1 TO PRB-P
           STRING "PRB " DELIMITED BY SIZE
                  PRB-CASE DELIMITED BY SPACE
                  " " DELIMITED BY SIZE
                  PRB-OUT DELIMITED BY SPACE
                  " |" DELIMITED BY SIZE
                  INTO PRB-LINE WITH POINTER PRB-P
           END-STRING
      * Drop trailing spaces only; leading ones are edit results
           PERFORM VARYING PRB-I FROM 60 BY -1
                   UNTIL PRB-I < 1 OR PRB-TEXT(PRB-I:1) NOT = SPACE
               CONTINUE
           END-PERFORM
           IF PRB-I > 0
               STRING PRB-TEXT(1:PRB-I) DELIMITED BY SIZE
                   INTO PRB-LINE WITH POINTER PRB-P
               END-STRING
           END-IF
           STRING "|" DELIMITED BY SIZE
               INTO PRB-LINE WITH POINTER PRB-P
           END-STRING
           SUBTRACT 1 FROM PRB-P
           DISPLAY PRB-LINE(1:PRB-P)
           MOVE SPACES TO PRB-TEXT
           MOVE SPACES TO PRB-IN.
