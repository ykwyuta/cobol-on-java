      *-----------------------------------------------------------------
      * CBLSYNC - SYNCHRONIZED slack bytes and lengths (P-111)
      * Q: How many slack bytes precede SYNC binary and float items?
      *    Does one OCCURS entry grow? Is the 01 level the base for the
      *    boundaries? Is the FD record (and so LRECL) the same?
      * How to read: each group is filled with X'EE' first; bytes that
      *    are still EE afterwards are slack bytes.
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLSYNC.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT SYNCF ASSIGN TO SYNCF
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS W-FS.
       DATA DIVISION.
       FILE SECTION.
       FD  SYNCF
           RECORDING MODE IS F.
       01  F1.
           05  F1-A            PIC X.
           05  F1-B            PIC S9(4) COMP SYNC.
           05  F1-C            PIC X(3).
           05  F1-D            PIC S9(9) COMP SYNC.
           05  F1-E            PIC X.
           05  F1-F            PIC S9(18) COMP SYNC.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-FS                PIC X(2).
       01  R1.
           05  R1-A            PIC X.
           05  R1-B            PIC S9(4) COMP SYNC.
           05  R1-C            PIC X(3).
           05  R1-D            PIC S9(9) COMP SYNC.
           05  R1-E            PIC X.
           05  R1-F            PIC S9(18) COMP SYNC.
           05  R1-G            PIC X.
           05  R1-H            COMP-1 SYNC.
           05  R1-I            PIC X.
           05  R1-J            COMP-2 SYNC.
           05  R1-K            PIC S9(4) COMP-5 SYNC.
           05  R1-L            PIC S9(5) COMP-3 SYNC.
           05  R1-M            PIC X(2) SYNC.
       01  R2.
           05  R2-HEAD         PIC X.
           05  R2-E OCCURS 3.
               10  R2-A        PIC X.
               10  R2-B        PIC S9(9) COMP SYNC.
               10  R2-C        PIC X.
      * Does the position depend on the 01 level being on a boundary?
       01  R3.
           05  R3-A            PIC X(3).
           05  R3-B            PIC S9(4) COMP SYNC.
           05  R3-C            PIC X(5).
           05  R3-D            PIC S9(9) COMP SYNC.
       01  W-LEN               PIC 9(4).
       PROCEDURE DIVISION.
       MAIN-PARA.
           MOVE ALL X"EE" TO R1
           MOVE "A" TO R1-A  MOVE 1 TO R1-B  MOVE "CCC" TO R1-C
           MOVE 2 TO R1-D  MOVE "E" TO R1-E  MOVE 3 TO R1-F
           MOVE "G" TO R1-G  MOVE 1 TO R1-H  MOVE "I" TO R1-I
           MOVE 1 TO R1-J  MOVE 4 TO R1-K  MOVE 5 TO R1-L
           MOVE "MM" TO R1-M
           MOVE LENGTH OF R1 TO W-LEN
           MOVE "S.P111.R1" TO PRB-CASE
           MOVE R1 TO PRB-IN MOVE LENGTH OF R1 TO PRB-LEN
           MOVE W-LEN TO PRB-TEXT
           PERFORM PRB-EMIT
      *
           MOVE ALL X"EE" TO R2
           MOVE "H" TO R2-HEAD
           MOVE "A" TO R2-A(1) MOVE 1 TO R2-B(1) MOVE "C" TO R2-C(1)
           MOVE "A" TO R2-A(2) MOVE 2 TO R2-B(2) MOVE "C" TO R2-C(2)
           MOVE "A" TO R2-A(3) MOVE 3 TO R2-B(3) MOVE "C" TO R2-C(3)
           MOVE LENGTH OF R2 TO W-LEN
           MOVE "S.P111.R2" TO PRB-CASE
           MOVE R2 TO PRB-IN MOVE LENGTH OF R2 TO PRB-LEN
           MOVE W-LEN TO PRB-TEXT
           PERFORM PRB-EMIT
      *
           MOVE ALL X"EE" TO R3
           MOVE "AAA" TO R3-A MOVE 1 TO R3-B MOVE "CCCCC" TO R3-C
           MOVE 2 TO R3-D
           MOVE LENGTH OF R3 TO W-LEN
           MOVE "S.P111.R3" TO PRB-CASE
           MOVE R3 TO PRB-IN MOVE LENGTH OF R3 TO PRB-LEN
           MOVE W-LEN TO PRB-TEXT
           PERFORM PRB-EMIT
      *
      * FD record; its length is also the LRECL given to the DCB
           OPEN OUTPUT SYNCF
           MOVE ALL X"EE" TO F1
           MOVE "A" TO F1-A  MOVE 1 TO F1-B  MOVE "CCC" TO F1-C
           MOVE 2 TO F1-D  MOVE "E" TO F1-E  MOVE 3 TO F1-F
           MOVE LENGTH OF F1 TO W-LEN
           MOVE "S.P111.F1" TO PRB-CASE
           MOVE F1 TO PRB-IN MOVE LENGTH OF F1 TO PRB-LEN
           MOVE W-LEN TO PRB-TEXT
           PERFORM PRB-EMIT
           WRITE F1
           MOVE "S.P111.F1.FS" TO PRB-CASE
           MOVE W-FS TO PRB-TEXT MOVE 0 TO PRB-LEN
           PERFORM PRB-EMIT
           CLOSE SYNCF
           GOBACK.
       COPY PRBPD.
