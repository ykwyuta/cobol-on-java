      *-----------------------------------------------------------------
      * CBLFUNC - digits of approximate functions and intermediate
      *           results, 18-digit receivers (P-065, P-075, P-009)
      * Q: Which digits does the host give for SQRT, SIN, LOG, EXP,
      *    fractional powers, ANNUITY, and for decimal intermediate
      *    results that exceed the receiver? Does ARITH change them?
      * Receivers are PIC S9(3)V9(15) (18 digits, valid in COMPAT).
      * Variants: ARITH(COMPAT) (default) / ARITH(EXTEND)
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLFUNC.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-R.
           05  W-R             PIC S9(3)V9(15).
       01  G-RR.
           05  W-RR            PIC S9(3)V9(15).
       01  G-I.
           05  W-I             PIC S9(18).
       01  W-A                 PIC S9(9)V9(9) VALUE 1.
       01  W-B                 PIC S9(9)V9(9) VALUE 3.
       01  W-BIG               PIC S9(18) VALUE 123456789012345678.
       PROCEDURE DIVISION.
       MAIN-PARA.
           COMPUTE W-R = FUNCTION SQRT(2)
           MOVE "F.P065.SQRT2" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R ROUNDED = FUNCTION SQRT(2)
           MOVE "F.P065.SQRT2R" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION SIN(1)
           MOVE "F.P065.SIN1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION COS(1)
           MOVE "F.P065.COS1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION TAN(1)
           MOVE "F.P065.TAN1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION ATAN(1)
           MOVE "F.P065.ATAN1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION LOG(2)
           MOVE "F.P065.LOG2" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION LOG10(2)
           MOVE "F.P065.LOG10" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION EXP(1)
           MOVE "F.P065.EXP1" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION ANNUITY(0.029, 4)
           MOVE "F.P065.ANNUITY" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION PRESENT-VALUE(0.1, 100, 200)
           MOVE "F.P065.PV" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = FUNCTION MEAN(1 2 4)
           MOVE "F.P065.MEAN" TO PRB-CASE PERFORM SHOW-R
      * Powers (P-075)
           COMPUTE W-R = 2 ** 0.5
           MOVE "F.P075.P2H" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = 1.1 ** 10
           MOVE "F.P075.P11T10" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = 10 ** -3
           MOVE "F.P075.P10M3" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = 3 ** 1.5
           MOVE "F.P075.P3X15" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-I = 7 ** 20
           MOVE "F.P075.P7T20" TO PRB-CASE PERFORM SHOW-I
      * Intermediate results (P-009)
           COMPUTE W-R = W-A / W-B
           MOVE "F.P009.DIV13" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = W-A / W-B * W-B
           MOVE "F.P009.DIV13X3" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-R = (W-A / W-B) * (W-A / W-B)
           MOVE "F.P009.SQ13" TO PRB-CASE PERFORM SHOW-R
           COMPUTE W-I = W-BIG * 1000 / 7
           MOVE "F.P009.BIGMD" TO PRB-CASE PERFORM SHOW-I
           COMPUTE W-I = W-BIG / 7 * 1000
           MOVE "F.P009.BIGDM" TO PRB-CASE PERFORM SHOW-I
           COMPUTE W-RR = 1 / 7 + 1 / 7 + 1 / 7
           MOVE "F.P009.SUM17" TO PRB-CASE
           MOVE G-RR TO PRB-IN MOVE 18 TO PRB-LEN PERFORM PRB-EMIT
           GOBACK.
       SHOW-R.
           MOVE G-R TO PRB-IN MOVE 18 TO PRB-LEN
           PERFORM PRB-EMIT.
       SHOW-I.
           MOVE G-I TO PRB-IN MOVE 18 TO PRB-LEN
           PERFORM PRB-EMIT.
       COPY PRBPD.
