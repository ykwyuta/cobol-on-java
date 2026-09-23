      *-----------------------------------------------------------------
      * CBLWSINI - WORKING-STORAGE without VALUE (P-025)
      * Q: What bytes does an item without VALUE start with? Does it
      *    differ for LOCAL-STORAGE, RENT/NORENT, or LE STORAGE option?
      * Variants: RENT (default) / NORENT; run with and without
      *    CEEOPTS STORAGE(FE,DE,BE)
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLWSINI.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      * Observed items come first, before the PRBWS items with VALUE
       01  U-GROUP.
           05  U-X             PIC X(8).
           05  U-9             PIC 9(4).
           05  U-P             PIC S9(7) COMP-3.
           05  U-B             PIC S9(8) COMP.
           05  U-F1            COMP-1.
           05  U-F2            COMP-2.
       01  U-ALONE-X           PIC X(4).
       01  U-ALONE-B           PIC S9(4) COMP.
       01  U-ALONE-BX REDEFINES U-ALONE-B PIC X(2).
       01  U-TABLE.
           05  U-ELT           PIC X(2) OCCURS 4.
       COPY PRBWS.
       LOCAL-STORAGE SECTION.
       01  L-GROUP.
           05  L-X             PIC X(8).
           05  L-P             PIC S9(7) COMP-3.
           05  L-B             PIC S9(8) COMP.
       PROCEDURE DIVISION.
       MAIN-PARA.
           MOVE "W.P025.WS.GROUP" TO PRB-CASE
           MOVE U-GROUP TO PRB-IN MOVE LENGTH OF U-GROUP TO PRB-LEN
           PERFORM PRB-EMIT
           MOVE "W.P025.WS.X" TO PRB-CASE
           MOVE U-ALONE-X TO PRB-IN MOVE 4 TO PRB-LEN
           PERFORM PRB-EMIT
           MOVE "W.P025.WS.B" TO PRB-CASE
           MOVE U-ALONE-BX TO PRB-IN MOVE 2 TO PRB-LEN
           PERFORM PRB-EMIT
           MOVE "W.P025.WS.TABLE" TO PRB-CASE
           MOVE U-TABLE TO PRB-IN MOVE LENGTH OF U-TABLE TO PRB-LEN
           PERFORM PRB-EMIT
           MOVE "W.P025.LS.GROUP" TO PRB-CASE
           MOVE L-GROUP TO PRB-IN MOVE LENGTH OF L-GROUP TO PRB-LEN
           PERFORM PRB-EMIT
           GOBACK.
       COPY PRBPD.
