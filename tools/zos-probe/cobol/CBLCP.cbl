      *-----------------------------------------------------------------
      * CBLCP - code page tables as the host COBOL runtime sees them
      *         (P-012, P-002, P-177)
      * Q: For each EBCDIC byte (and each DBCS pair of the mixed code
      *    pages), which UTF-16 does FUNCTION NATIONAL-OF give? The
      *    result is the reference against which the JDK charsets
      *    used by this implementation (IBM1047, IBM037, x-IBM930,
      *    x-IBM939) are compared, and the table that IBM-1390 and
      *    IBM-1399 support has to reproduce.
      * SYSIN, one card per range (columns):
      *    1-5 CCSID, 7 mode (S = single byte, D = DBCS pair),
      *    9-11 first byte from, 13-15 first byte to (decimal).
      *    A card with END in columns 1-3 stops the program.
      * Output DD CPOUT (FB 80): PRB CP.<ccsid>.<S|D>.<bytes> <utf16>
      * The local implementation has no NATIONAL-OF; the comparison
      * is done by tools/zos-probe/ProbeTool.java cp.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLCP.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CPOUT ASSIGN TO CPOUT FILE STATUS IS W-FS.
       DATA DIVISION.
       FILE SECTION.
       FD  CPOUT
           RECORDING MODE IS F.
       01  CPOUT-REC           PIC X(80).
       WORKING-STORAGE SECTION.
       01  W-FS                PIC X(2).
       01  W-CARD VALUE SPACES.
           05  C-CCSID         PIC 9(5).
           05  FILLER          PIC X.
           05  C-MODE          PIC X.
           05  FILLER          PIC X.
           05  C-FROM          PIC 9(3).
           05  FILLER          PIC X.
           05  C-TO            PIC 9(3).
           05  FILLER          PIC X(65).
       01  W-CCSID             PIC 9(5).
       01  W-CCSIDX REDEFINES W-CCSID PIC X(5).
       01  W-IN                PIC X(4).
       01  W-INLEN             PIC S9(4) COMP-5.
       01  W-NAT               PIC N(4) USAGE NATIONAL.
       01  W-NATX REDEFINES W-NAT PIC X(8).
       01  W-NLEN              PIC S9(4) COMP-5.
       01  W-B1                PIC S9(4) COMP-5.
       01  W-B2                PIC S9(4) COMP-5.
       01  W-HALF              PIC 9(4) COMP-5.
       01  W-HALF-X REDEFINES W-HALF.
           05  W-HI            PIC X.
           05  W-LO            PIC X.
       01  W-DIGITS            PIC X(16) VALUE "0123456789ABCDEF".
       01  W-HEXIN             PIC X(8).
       01  W-HEXLEN            PIC S9(4) COMP-5.
       01  W-HEXOUT            PIC X(16).
       01  W-KEYHEX            PIC X(8).
       01  W-VALHEX            PIC X(16).
       01  W-I                 PIC S9(4) COMP-5.
       01  W-Q                 PIC S9(4) COMP-5.
       01  W-R                 PIC S9(4) COMP-5.
       01  W-P                 PIC S9(4) COMP-5.
       PROCEDURE DIVISION.
       MAIN-PARA.
           OPEN OUTPUT CPOUT
           PERFORM UNTIL W-CARD(1:3) = "END"
               MOVE SPACES TO W-CARD
               ACCEPT W-CARD
               IF W-CARD(1:3) NOT = "END" AND W-CARD NOT = SPACES
                   MOVE C-CCSID TO W-CCSID
                   IF C-MODE = "D"
                       PERFORM DBCS-RANGE
                   ELSE
                       PERFORM SBCS-RANGE
                   END-IF
               END-IF
               IF W-CARD = SPACES
                   MOVE "END" TO W-CARD(1:3)
               END-IF
           END-PERFORM
           CLOSE CPOUT
           GOBACK.
      *
       SBCS-RANGE.
           PERFORM VARYING W-B1 FROM C-FROM BY 1 UNTIL W-B1 > C-TO
               MOVE W-B1 TO W-HALF
               MOVE W-LO TO W-IN(1:1)
               MOVE 1 TO W-INLEN
               PERFORM CONVERT
           END-PERFORM.
      *
      * A DBCS pair is converted as SO b1 b2 SI.
       DBCS-RANGE.
           PERFORM VARYING W-B1 FROM C-FROM BY 1 UNTIL W-B1 > C-TO
               PERFORM VARYING W-B2 FROM 64 BY 1 UNTIL W-B2 > 254
                   MOVE X"0E" TO W-IN(1:1)
                   MOVE W-B1 TO W-HALF
                   MOVE W-LO TO W-IN(2:1)
                   MOVE W-B2 TO W-HALF
                   MOVE W-LO TO W-IN(3:1)
                   MOVE X"0F" TO W-IN(4:1)
                   MOVE 4 TO W-INLEN
                   PERFORM CONVERT
               END-PERFORM
           END-PERFORM.
      *
       CONVERT.
           MOVE FUNCTION NATIONAL-OF(W-IN(1:W-INLEN), W-CCSID)
               TO W-NAT
           COMPUTE W-NLEN = FUNCTION LENGTH(
               FUNCTION NATIONAL-OF(W-IN(1:W-INLEN), W-CCSID))
           IF W-NLEN > 4
               MOVE 4 TO W-NLEN
           END-IF
      *    key: the EBCDIC bytes without SO/SI
           MOVE SPACES TO W-HEXIN
           IF W-INLEN = 1
               MOVE W-IN(1:1) TO W-HEXIN
               MOVE 1 TO W-HEXLEN
           ELSE
               MOVE W-IN(2:2) TO W-HEXIN
               MOVE 2 TO W-HEXLEN
           END-IF
           PERFORM TO-HEX
           MOVE W-HEXOUT TO W-KEYHEX
           MOVE W-NATX TO W-HEXIN
           COMPUTE W-HEXLEN = W-NLEN * 2
           PERFORM TO-HEX
           MOVE W-HEXOUT TO W-VALHEX
           MOVE SPACES TO CPOUT-REC
           STRING "PRB CP." W-CCSIDX "." C-MODE "."
                      DELIMITED BY SIZE
                  W-KEYHEX DELIMITED BY SPACE
                  " " DELIMITED BY SIZE
                  W-VALHEX DELIMITED BY SPACE
                  " ||" DELIMITED BY SIZE
                  INTO CPOUT-REC
           END-STRING
           WRITE CPOUT-REC.
      *
       TO-HEX.
           MOVE SPACES TO W-HEXOUT
           PERFORM VARYING W-I FROM 1 BY 1 UNTIL W-I > W-HEXLEN
               MOVE LOW-VALUE TO W-HI
               MOVE W-HEXIN(W-I:1) TO W-LO
               DIVIDE W-HALF BY 16 GIVING W-Q REMAINDER W-R
               COMPUTE W-P = W-I * 2 - 1
               MOVE W-DIGITS(W-Q + 1:1) TO W-HEXOUT(W-P:1)
               MOVE W-DIGITS(W-R + 1:1) TO W-HEXOUT(W-P + 1:1)
           END-PERFORM.
