      *-----------------------------------------------------------------
      * CBLEDIT - numeric editing (P-014, P-016)
      * Q: What does the host put for fixed signs, all-suppressed
      *    pictures, BLANK WHEN ZERO, and check protection with CR/DB?
      *    Key cases: ***.** with zero (P-014), **9.99CR positive
      *    (P-016).
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLEDIT.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SPECIAL-NAMES.
           CURRENCY SIGN IS "$".
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  S-VAL               PIC S9(4)V99.
       01  W-VALS.
           05  FILLER PIC S9(4)V99 VALUE 0.
           05  FILLER PIC S9(4)V99 VALUE 12.34.
           05  FILLER PIC S9(4)V99 VALUE -12.34.
           05  FILLER PIC S9(4)V99 VALUE 0.01.
           05  FILLER PIC S9(4)V99 VALUE -0.01.
           05  FILLER PIC S9(4)V99 VALUE 1234.56.
       01  W-VAL-TAB REDEFINES W-VALS.
           05  W-VAL           PIC S9(4)V99 OCCURS 6.
       01  W-K                 PIC 9(2).
       01  W-KX REDEFINES W-K  PIC X(2).
      * Receivers, each wrapped in a group so a group MOVE gives bytes
       01  G01. 05 E01 PIC +9(4).
       01  G02. 05 E02 PIC -9(4).
       01  G03. 05 E03 PIC 9(4)+.
       01  G04. 05 E04 PIC ZZZZ.
       01  G05. 05 E05 PIC ****.
       01  G06. 05 E06 PIC ***.**.
       01  G07. 05 E07 PIC ZZZ.ZZ.
       01  G08. 05 E08 PIC 9(4) BLANK WHEN ZERO.
       01  G09. 05 E09 PIC ZZ9.99 BLANK WHEN ZERO.
       01  G10. 05 E10 PIC **9.99CR.
       01  G11. 05 E11 PIC **9.99DB.
       01  G12. 05 E12 PIC $***.99CR.
       01  G13. 05 E13 PIC ZZ9.99CR.
       01  G14. 05 E14 PIC +ZZ9.99.
       01  G15. 05 E15 PIC $$$$.$$.
       01  G16. 05 E16 PIC ----.--.
       01  G17. 05 E17 PIC ***9.99-.
       01  G18. 05 E18 PIC Z,ZZ9.99-.
       01  G19. 05 E19 PIC ++++.++.
       01  G20. 05 E20 PIC **.**CR.
       PROCEDURE DIVISION.
       MAIN-PARA.
           PERFORM VARYING W-K FROM 1 BY 1 UNTIL W-K > 6
               MOVE W-VAL(W-K) TO S-VAL
               PERFORM EDIT-ALL
           END-PERFORM
           GOBACK.
      *
       EDIT-ALL.
           MOVE S-VAL TO E01 MOVE "01" TO PRB-CASE(8:2)
           MOVE G01 TO PRB-IN MOVE LENGTH OF G01 TO PRB-LEN
           MOVE G01 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E02 MOVE "02" TO PRB-CASE(8:2)
           MOVE G02 TO PRB-IN MOVE LENGTH OF G02 TO PRB-LEN
           MOVE G02 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E03 MOVE "03" TO PRB-CASE(8:2)
           MOVE G03 TO PRB-IN MOVE LENGTH OF G03 TO PRB-LEN
           MOVE G03 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E04 MOVE "04" TO PRB-CASE(8:2)
           MOVE G04 TO PRB-IN MOVE LENGTH OF G04 TO PRB-LEN
           MOVE G04 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E05 MOVE "05" TO PRB-CASE(8:2)
           MOVE G05 TO PRB-IN MOVE LENGTH OF G05 TO PRB-LEN
           MOVE G05 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E06 MOVE "06" TO PRB-CASE(8:2)
           MOVE G06 TO PRB-IN MOVE LENGTH OF G06 TO PRB-LEN
           MOVE G06 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E07 MOVE "07" TO PRB-CASE(8:2)
           MOVE G07 TO PRB-IN MOVE LENGTH OF G07 TO PRB-LEN
           MOVE G07 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E08 MOVE "08" TO PRB-CASE(8:2)
           MOVE G08 TO PRB-IN MOVE LENGTH OF G08 TO PRB-LEN
           MOVE G08 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E09 MOVE "09" TO PRB-CASE(8:2)
           MOVE G09 TO PRB-IN MOVE LENGTH OF G09 TO PRB-LEN
           MOVE G09 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E10 MOVE "10" TO PRB-CASE(8:2)
           MOVE G10 TO PRB-IN MOVE LENGTH OF G10 TO PRB-LEN
           MOVE G10 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E11 MOVE "11" TO PRB-CASE(8:2)
           MOVE G11 TO PRB-IN MOVE LENGTH OF G11 TO PRB-LEN
           MOVE G11 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E12 MOVE "12" TO PRB-CASE(8:2)
           MOVE G12 TO PRB-IN MOVE LENGTH OF G12 TO PRB-LEN
           MOVE G12 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E13 MOVE "13" TO PRB-CASE(8:2)
           MOVE G13 TO PRB-IN MOVE LENGTH OF G13 TO PRB-LEN
           MOVE G13 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E14 MOVE "14" TO PRB-CASE(8:2)
           MOVE G14 TO PRB-IN MOVE LENGTH OF G14 TO PRB-LEN
           MOVE G14 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E15 MOVE "15" TO PRB-CASE(8:2)
           MOVE G15 TO PRB-IN MOVE LENGTH OF G15 TO PRB-LEN
           MOVE G15 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E16 MOVE "16" TO PRB-CASE(8:2)
           MOVE G16 TO PRB-IN MOVE LENGTH OF G16 TO PRB-LEN
           MOVE G16 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E17 MOVE "17" TO PRB-CASE(8:2)
           MOVE G17 TO PRB-IN MOVE LENGTH OF G17 TO PRB-LEN
           MOVE G17 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E18 MOVE "18" TO PRB-CASE(8:2)
           MOVE G18 TO PRB-IN MOVE LENGTH OF G18 TO PRB-LEN
           MOVE G18 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E19 MOVE "19" TO PRB-CASE(8:2)
           MOVE G19 TO PRB-IN MOVE LENGTH OF G19 TO PRB-LEN
           MOVE G19 TO PRB-TEXT PERFORM EMIT
           MOVE S-VAL TO E20 MOVE "20" TO PRB-CASE(8:2)
           MOVE G20 TO PRB-IN MOVE LENGTH OF G20 TO PRB-LEN
           MOVE G20 TO PRB-TEXT PERFORM EMIT.
      *
      * Case id: E.P014.<picture no>.<value no>
       EMIT.
           MOVE "E.P014." TO PRB-CASE(1:7)
           MOVE "." TO PRB-CASE(10:1)
           MOVE W-KX TO PRB-CASE(11:2)
           PERFORM PRB-EMIT.
       COPY PRBPD.
