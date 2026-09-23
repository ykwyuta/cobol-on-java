      *-----------------------------------------------------------------
      * CBLFLT - decimal to HFP and back (P-018, P-127)
      * Q: Which HFP bits does the host give for VALUE 0.1 and for a
      *    MOVE of 0.1 into COMP-1 / COMP-2 (rounded or truncated)?
      *    When HFP moves into a fixed-point item, is the last digit
      *    rounded or truncated?
      * Variants: FLOAT(HEX) (default) only
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLFLT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-S1.
           05  W-S1            COMP-1 VALUE 0.1.
       01  G-D1.
           05  W-D1            COMP-2 VALUE 0.1.
       01  G-S2.
           05  W-S2            COMP-1.
       01  G-D2.
           05  W-D2            COMP-2.
       01  G-F.
           05  W-F             PIC S9(1)V9(17).
       01  G-F5.
           05  W-F5            PIC S9(1)V9(5).
       01  W-DEC               PIC S9V9(9) VALUE 0.1.
       01  W-THIRD             PIC S9V9(17) VALUE 0.33333333333333333.
       PROCEDURE DIVISION.
       MAIN-PARA.
           MOVE "L.P018.VALS" TO PRB-CASE
           MOVE G-S1 TO PRB-IN MOVE 4 TO PRB-LEN PERFORM PRB-EMIT
           MOVE "L.P018.VALD" TO PRB-CASE
           MOVE G-D1 TO PRB-IN MOVE 8 TO PRB-LEN PERFORM PRB-EMIT
           MOVE W-DEC TO W-S2
           MOVE "L.P018.MOVS" TO PRB-CASE
           MOVE G-S2 TO PRB-IN MOVE 4 TO PRB-LEN PERFORM PRB-EMIT
           MOVE W-DEC TO W-D2
           MOVE "L.P018.MOVD" TO PRB-CASE
           MOVE G-D2 TO PRB-IN MOVE 8 TO PRB-LEN PERFORM PRB-EMIT
           MOVE W-THIRD TO W-D2
           MOVE "L.P018.THIRDD" TO PRB-CASE
           MOVE G-D2 TO PRB-IN MOVE 8 TO PRB-LEN PERFORM PRB-EMIT
           MOVE -2.5 TO W-S2
           MOVE "L.P018.NEG25" TO PRB-CASE
           MOVE G-S2 TO PRB-IN MOVE 4 TO PRB-LEN PERFORM PRB-EMIT
      * HFP to fixed point (P-127)
           MOVE W-D1 TO W-F
           MOVE "L.P127.D1TOF" TO PRB-CASE
           MOVE G-F TO PRB-IN MOVE 18 TO PRB-LEN PERFORM PRB-EMIT
           MOVE W-S1 TO W-F
           MOVE "L.P127.S1TOF" TO PRB-CASE
           MOVE G-F TO PRB-IN MOVE 18 TO PRB-LEN PERFORM PRB-EMIT
      * 2/3 is moved from a decimal literal: this implementation
      * refuses COMPUTE division on floating point (P-127).
           MOVE 0.666666666666666667 TO W-D2
           MOVE W-D2 TO W-F5
           MOVE "L.P127.TWO3" TO PRB-CASE
           MOVE G-F5 TO PRB-IN MOVE 6 TO PRB-LEN PERFORM PRB-EMIT
           COMPUTE W-F5 ROUNDED = W-D2
           MOVE "L.P127.TWO3R" TO PRB-CASE
           MOVE G-F5 TO PRB-IN MOVE 6 TO PRB-LEN PERFORM PRB-EMIT
           GOBACK.
       COPY PRBPD.
