      *-----------------------------------------------------------------
      * CBLNUM - numeric MOVE and sign nibbles (P-013, P-015)
      * Q: Does MOVE keep a packed negative zero? Are the non-preferred
      *    signs F/A/B/E normalised by MOVE? Does NUMPROC change that?
      * Variants: NUMPROC(NOPFD) / NUMPROC(PFD) / NUMPROC(MIG)
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLNUM.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
      * Senders: raw bytes via REDEFINES, read as numbers
       01  S-PK3-X             PIC X(2).
       01  S-PK3 REDEFINES S-PK3-X PIC S9(3) COMP-3.
       01  S-ZD3-X             PIC X(3).
       01  S-ZD3 REDEFINES S-ZD3-X PIC S9(3).
      * Receivers, each wrapped in a group so a group MOVE gives bytes
       01  G-PK3.
           05  T-PK3           PIC S9(3) COMP-3.
       01  G-PK5.
           05  T-PK5           PIC S9(5) COMP-3.
       01  G-PK3U.
           05  T-PK3U          PIC 9(3) COMP-3.
       01  G-ZD3.
           05  T-ZD3           PIC S9(3).
       01  G-ZD5.
           05  T-ZD5           PIC S9(5).
       01  G-ZD3U.
           05  T-ZD3U          PIC 9(3).
       01  G-BIN.
           05  T-BIN           PIC S9(4) COMP.
       01  W-TABLE.
           05  FILLER PIC X(3) VALUE X"F1F2F3".
           05  FILLER PIC X(3) VALUE X"F1F2C3".
           05  FILLER PIC X(3) VALUE X"F1F2D3".
           05  FILLER PIC X(3) VALUE X"F1F2A3".
           05  FILLER PIC X(3) VALUE X"F1F2B3".
           05  FILLER PIC X(3) VALUE X"F1F2E3".
           05  FILLER PIC X(3) VALUE X"F0F0D0".
           05  FILLER PIC X(3) VALUE X"F0F0C0".
       01  W-ZD-TAB REDEFINES W-TABLE.
           05  W-ZD            PIC X(3) OCCURS 8.
       01  W-PTABLE.
           05  FILLER PIC X(2) VALUE X"123F".
           05  FILLER PIC X(2) VALUE X"123C".
           05  FILLER PIC X(2) VALUE X"123D".
           05  FILLER PIC X(2) VALUE X"123A".
           05  FILLER PIC X(2) VALUE X"123B".
           05  FILLER PIC X(2) VALUE X"123E".
           05  FILLER PIC X(2) VALUE X"000D".
           05  FILLER PIC X(2) VALUE X"000C".
       01  W-PK-TAB REDEFINES W-PTABLE.
           05  W-PK            PIC X(2) OCCURS 8.
       01  W-K                 PIC 9(2).
       01  W-SUFFIX            PIC X(3).
       PROCEDURE DIVISION.
       MAIN-PARA.
           PERFORM VARYING W-K FROM 1 BY 1 UNTIL W-K > 8
               PERFORM PACKED-CASES
               PERFORM ZONED-CASES
           END-PERFORM
           GOBACK.
      *
      * Packed decimal sender
       PACKED-CASES.
           MOVE W-PK(W-K) TO S-PK3-X
           MOVE W-K TO W-SUFFIX
           MOVE S-PK3 TO T-PK3
           STRING "N.P013.PK3." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-PK3 TO PRB-IN MOVE 2 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-PK3 TO T-PK5
           STRING "N.P013.PK5." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-PK5 TO PRB-IN MOVE 3 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-PK3 TO T-PK3U
           STRING "N.P013.PKU." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-PK3U TO PRB-IN MOVE 2 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-PK3 TO T-ZD3
           STRING "N.P013.ZD3." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-ZD3 TO PRB-IN MOVE 3 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-PK3 TO T-BIN
           STRING "N.P013.BIN." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-BIN TO PRB-IN MOVE 2 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-PK3 TO T-PK3
           ADD 0 TO T-PK3
           STRING "N.P013.ADD." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-PK3 TO PRB-IN MOVE 2 TO PRB-LEN PERFORM PRB-EMIT
           STRING "N.P013.CMP." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           EVALUATE TRUE
               WHEN S-PK3 = 0     MOVE "ZERO" TO PRB-TEXT
               WHEN S-PK3 > 0     MOVE "POSITIVE" TO PRB-TEXT
               WHEN S-PK3 < 0     MOVE "NEGATIVE" TO PRB-TEXT
           END-EVALUATE
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT.
      *
      * Zoned decimal sender
       ZONED-CASES.
           MOVE W-ZD(W-K) TO S-ZD3-X
           MOVE W-K TO W-SUFFIX
           MOVE S-ZD3 TO T-ZD3
           STRING "N.P015.ZD3." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-ZD3 TO PRB-IN MOVE 3 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-ZD3 TO T-ZD5
           STRING "N.P015.ZD5." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-ZD5 TO PRB-IN MOVE 5 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-ZD3 TO T-ZD3U
           STRING "N.P015.ZDU." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-ZD3U TO PRB-IN MOVE 3 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-ZD3 TO T-PK3
           STRING "N.P015.PK3." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-PK3 TO PRB-IN MOVE 2 TO PRB-LEN PERFORM PRB-EMIT
           MOVE S-ZD3 TO T-ZD5
           ADD 0 TO T-ZD5
           STRING "N.P015.ADD." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           MOVE G-ZD5 TO PRB-IN MOVE 5 TO PRB-LEN PERFORM PRB-EMIT
           STRING "N.P015.CMP." W-SUFFIX(1:2) DELIMITED BY SIZE
               INTO PRB-CASE
           EVALUATE TRUE
               WHEN S-ZD3 = 0     MOVE "ZERO" TO PRB-TEXT
               WHEN S-ZD3 > 0     MOVE "POSITIVE" TO PRB-TEXT
               WHEN S-ZD3 < 0     MOVE "NEGATIVE" TO PRB-TEXT
           END-EVALUATE
           MOVE 0 TO PRB-LEN PERFORM PRB-EMIT.
       COPY PRBPD.
