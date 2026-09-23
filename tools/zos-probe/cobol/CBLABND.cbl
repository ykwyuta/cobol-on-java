      *-----------------------------------------------------------------
      * CBLABND - cases that may abend; one case per job step
      *           (P-003, P-004, P-048, P-027)
      * SYSIN card: the case number in columns 1-2 ('01').
      *   01 zoned X'F1FAF3' (digit nibble A)  ADD 1
      *   02 packed X'1A3C' (digit nibble A)   ADD 1
      *   03 packed X'1239' (sign nibble 9)    MOVE, then ADD 1
      *   04 zoned X'F140F3' (a space inside)  ADD 1
      *   05 subscript 5 on OCCURS 3 (see variant SSRANGE)
      *   06 CALL of a missing program, no ON EXCEPTION
      *   07 CALL of a missing program, with ON EXCEPTION
      *   08 packed DIVIDE by zero without ON SIZE ERROR
      * Q: does the host abend (which code, which LE message), or
      *    which bytes does it compute?
      * A BEFORE line comes first so an abend still shows the case.
      * Variants: default (NUMPROC(NOPFD), NOSSRANGE) /
      *    NUMPROC(PFD) / SSRANGE
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLABND.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-Z.
           05  W-Z             PIC S9(3).
       01  G-ZX REDEFINES G-Z  PIC X(3).
       01  G-P.
           05  W-P             PIC S9(3) COMP-3.
       01  G-PX REDEFINES G-P  PIC X(2).
       01  G-P2.
           05  W-P2            PIC S9(3) COMP-3.
       01  W-TABLE.
           05  W-ELT           PIC X(4) OCCURS 3.
       01  W-AFTER             PIC X(4) VALUE "ZZZZ".
       01  W-SUB               PIC S9(4) COMP VALUE 5.
       01  W-PGM               PIC X(8) VALUE "NOSUCHPG".
       01  W-ZERO              PIC S9(3) COMP-3 VALUE 0.
       01  W-CASE              PIC X(2).
       01  W-CARD              PIC X(80) VALUE SPACES.
       PROCEDURE DIVISION.
       MAIN-PARA.
      * The argument comes on a SYSIN card, not in PARM: this
      * implementation accepts a PARM only of exactly the declared
      * length (P-090; see CBLPARM).
           ACCEPT W-CARD
           MOVE W-CARD(1:2) TO W-CASE
           MOVE SPACES TO PRB-CASE
           STRING "B." W-CASE ".BEFORE" DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           MOVE SPACES TO PRB-CASE
           STRING "B." W-CASE ".AFTER" DELIMITED BY SIZE
               INTO PRB-CASE
           EVALUATE W-CASE
               WHEN "01"
                   MOVE X"F1FAF3" TO G-ZX
                   ADD 1 TO W-Z
                   MOVE G-Z TO PRB-IN MOVE 3 TO PRB-LEN
               WHEN "02"
                   MOVE X"1A3C" TO G-PX
                   ADD 1 TO W-P
                   MOVE G-P TO PRB-IN MOVE 2 TO PRB-LEN
               WHEN "03"
                   MOVE X"1239" TO G-PX
                   MOVE W-P TO W-P2
                   MOVE G-P2 TO PRB-IN(1:2)
                   ADD 1 TO W-P2
                   MOVE G-P2 TO PRB-IN(3:2) MOVE 4 TO PRB-LEN
               WHEN "04"
                   MOVE X"F140F3" TO G-ZX
                   ADD 1 TO W-Z
                   MOVE G-Z TO PRB-IN MOVE 3 TO PRB-LEN
               WHEN "05"
                   MOVE "AAAABBBBCCCC" TO W-TABLE
                   MOVE "XXXX" TO W-ELT(W-SUB)
                   MOVE W-AFTER TO PRB-IN MOVE 4 TO PRB-LEN
               WHEN "06"
                   CALL W-PGM
                   MOVE 0 TO PRB-LEN
               WHEN "07"
                   CALL W-PGM
                       ON EXCEPTION MOVE "EXCEPTION" TO PRB-TEXT
                       NOT ON EXCEPTION MOVE "CALLED" TO PRB-TEXT
                   END-CALL
                   MOVE 0 TO PRB-LEN
               WHEN "08"
                   MOVE 5 TO W-P
                   DIVIDE W-ZERO INTO W-P
                   MOVE G-P TO PRB-IN MOVE 2 TO PRB-LEN
               WHEN OTHER
                   MOVE "UNKNOWN CASE" TO PRB-TEXT
                   MOVE 0 TO PRB-LEN
           END-EVALUATE
           PERFORM PRB-EMIT
           GOBACK.
       COPY PRBPD.
