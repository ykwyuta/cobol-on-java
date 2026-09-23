      *-----------------------------------------------------------------
      * CBLASMD - driver that calls the assembler probe ASMEXE with a
      *           256-byte work area and shows the area afterwards
      *           (P-173, P-174, P-175)
      * SYSIN card: 'program,code' ('ASMEXE,01'). The code is
      *   put in byte 1 of the area; ASMEXE writes its results from
      *   byte 17 on. See hlasm/ASMEXE.asm and ASMPM.asm for the codes.
      * The area is shown as 8 lines of 32 bytes: H.<code>.<line>.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLASMD.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-AREA              PIC X(256) VALUE LOW-VALUES.
       01  W-CODE              PIC 9(2).
       01  W-CODE-X REDEFINES W-CODE PIC X(2).
       01  W-CODE-B            PIC 9(4) COMP-5.
       01  W-CODE-BX REDEFINES W-CODE-B.
           05  FILLER          PIC X.
           05  W-CODE-BYTE     PIC X.
       01  W-K                 PIC 9.
       01  W-POS               PIC 9(3).
       01  W-RC                PIC 9(4).
       01  W-PGM               PIC X(8).
       01  W-CARD              PIC X(80) VALUE SPACES.
       PROCEDURE DIVISION.
       MAIN-PARA.
      * The argument comes on a SYSIN card, not in PARM: this
      * implementation accepts a PARM only of exactly the declared
      * length (P-090; see CBLPARM).
           ACCEPT W-CARD
           MOVE SPACES TO W-PGM
           UNSTRING W-CARD DELIMITED BY "," OR SPACE
               INTO W-PGM W-CODE-X
           END-UNSTRING
           MOVE W-CODE TO W-CODE-B
           MOVE W-CODE-BYTE TO W-AREA(1:1)
           MOVE SPACES TO PRB-CASE
           STRING "H." W-CODE-X ".BEFORE" DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           CALL W-PGM USING W-AREA
           MOVE RETURN-CODE TO W-RC
           MOVE SPACES TO PRB-CASE
           STRING "H." W-CODE-X ".RC" DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE W-RC TO PRB-TEXT
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           PERFORM VARYING W-K FROM 1 BY 1 UNTIL W-K > 8
               COMPUTE W-POS = (W-K - 1) * 32 + 1
               MOVE SPACES TO PRB-CASE
               STRING "H." W-CODE-X "." W-K DELIMITED BY SIZE
                   INTO PRB-CASE
               MOVE W-AREA(W-POS:32) TO PRB-IN
               MOVE 32 TO PRB-LEN
               PERFORM PRB-EMIT
           END-PERFORM
           MOVE 0 TO RETURN-CODE
           GOBACK.
       COPY PRBPD.
