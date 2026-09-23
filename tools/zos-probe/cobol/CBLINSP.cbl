      *-----------------------------------------------------------------
      * CBLINSP - INSPECT on signed numeric items (P-085)
      * Q: After INSPECT REPLACING/CONVERTING on PIC S9(5), is the sign
      *    kept? Is the separate sign byte inspected?
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLINSP.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  G-T.
           05  W-T             PIC S9(5).
       01  G-L.
           05  W-L             PIC S9(5) SIGN LEADING SEPARATE.
       01  G-R.
           05  W-R             PIC S9(5) SIGN TRAILING SEPARATE.
       01  G-U.
           05  W-U             PIC 9(5).
       01  W-C1                PIC 9(3).
       01  W-C2                PIC 9(3).
       01  W-CT.
           05  W-CT1           PIC 9(3).
           05  FILLER          PIC X VALUE ",".
           05  W-CT2           PIC 9(3).
       PROCEDURE DIVISION.
       MAIN-PARA.
      * 1: negative value; the last byte is D5
           MOVE -12345 TO W-T
           INSPECT W-T REPLACING ALL "5" BY "7"
           MOVE "I.P085.01" TO PRB-CASE PERFORM SHOW-T
      * 2: positive value
           MOVE 12345 TO W-T
           INSPECT W-T REPLACING ALL "5" BY "7"
           MOVE "I.P085.02" TO PRB-CASE PERFORM SHOW-T
      * 3: CONVERTING
           MOVE -12345 TO W-T
           INSPECT W-T CONVERTING "12345" TO "67890"
           MOVE "I.P085.03" TO PRB-CASE PERFORM SHOW-T
      * 4: replace a digit that does not carry the sign
           MOVE -12345 TO W-T
           INSPECT W-T REPLACING ALL "1" BY "9"
           MOVE "I.P085.04" TO PRB-CASE PERFORM SHOW-T
      * 5: TALLYING as in NC216A; is the sign counted as "-"?
           MOVE -12345 TO W-T
           MOVE 0 TO W-C1 W-C2
           INSPECT W-T TALLYING W-C1 FOR ALL "-"
                                W-C2 FOR ALL "5"
           MOVE "I.P085.05" TO PRB-CASE PERFORM SHOW-COUNT
      * 6: can the LEADING SEPARATE sign byte be replaced?
           MOVE -12345 TO W-L
           INSPECT W-L REPLACING ALL "-" BY "+"
           MOVE "I.P085.06" TO PRB-CASE PERFORM SHOW-L
      * 7: replace a digit of a LEADING SEPARATE item
           MOVE -12345 TO W-L
           INSPECT W-L REPLACING ALL "1" BY "9"
           MOVE "I.P085.07" TO PRB-CASE PERFORM SHOW-L
      * 8: is the LEADING SEPARATE sign counted?
           MOVE -12345 TO W-L
           MOVE 0 TO W-C1 W-C2
           INSPECT W-L TALLYING W-C1 FOR ALL "-"
                                W-C2 FOR ALL "1"
           MOVE "I.P085.08" TO PRB-CASE PERFORM SHOW-COUNT
      * 9: can the TRAILING SEPARATE sign byte be replaced?
           MOVE -12345 TO W-R
           INSPECT W-R REPLACING ALL "-" BY "+"
           MOVE "I.P085.09" TO PRB-CASE PERFORM SHOW-R
      * 10: unsigned item, for reference
           MOVE 12345 TO W-U
           INSPECT W-U REPLACING ALL "5" BY "7"
           MOVE "I.P085.10" TO PRB-CASE
           MOVE G-U TO PRB-IN MOVE 5 TO PRB-LEN
           MOVE G-U TO PRB-TEXT PERFORM PRB-EMIT
      * 11: read the replaced value as a number
           MOVE -12345 TO W-T
           INSPECT W-T REPLACING ALL "5" BY "7"
           ADD 0 TO W-T
           MOVE "I.P085.11" TO PRB-CASE PERFORM SHOW-T
           GOBACK.
       SHOW-T.
           MOVE G-T TO PRB-IN MOVE 5 TO PRB-LEN
           PERFORM PRB-EMIT.
       SHOW-L.
           MOVE G-L TO PRB-IN MOVE 6 TO PRB-LEN
           MOVE G-L TO PRB-TEXT PERFORM PRB-EMIT.
       SHOW-R.
           MOVE G-R TO PRB-IN MOVE 6 TO PRB-LEN
           MOVE G-R TO PRB-TEXT PERFORM PRB-EMIT.
       SHOW-COUNT.
           MOVE W-C1 TO W-CT1 MOVE W-C2 TO W-CT2
           MOVE W-CT TO PRB-TEXT MOVE 0 TO PRB-LEN
           PERFORM PRB-EMIT.
       COPY PRBPD.
