      *-----------------------------------------------------------------
      * CBLFUNX - the same questions as CBLFUNC with 31-digit
      *           receivers; ARITH(EXTEND) only (P-065, P-075, P-009)
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLFUNX.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-R.
           05  W-R             PIC S9(3)V9(28).
       01  G-I.
           05  W-I             PIC S9(31).
       01  W-A                 PIC S9(15)V9(15) VALUE 1.
       01  W-B                 PIC S9(15)V9(15) VALUE 3.
       01  W-BIG               PIC S9(31)
               VALUE 1234567890123456789012345678901.
       PROCEDURE DIVISION.
       MAIN-PARA.
           COMPUTE W-R = FUNCTION SQRT(2)
           MOVE "X.P065.SQRT2" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION SIN(1)
           MOVE "X.P065.SIN1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION LOG(2)
           MOVE "X.P065.LOG2" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION EXP(1)
           MOVE "X.P065.EXP1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = 2 ** 0.5
           MOVE "X.P075.P2H" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = 1.1 ** 10
           MOVE "X.P075.P11T10" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-I = 7 ** 30
           MOVE "X.P075.P7T30" TO PRB-CASE PERFORM SHOW-I
           COMPUTE W-R = W-A / W-B
           MOVE "X.P009.DIV13" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = W-A / W-B * W-B
           MOVE "X.P009.DIV13X3" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-I = W-BIG * 1000 / 7
           MOVE "X.P009.BIGMD" TO PRB-CASE PERFORM SHOW-I
           GOBACK.
       SHOW-R.
           MOVE G-R TO PRB-IN MOVE 31 TO PRB-LEN
           PERFORM PRB-EMIT.
       SHOW-I.
           MOVE G-I TO PRB-IN MOVE 31 TO PRB-LEN
           PERFORM PRB-EMIT.
       COPY PRBPD.
