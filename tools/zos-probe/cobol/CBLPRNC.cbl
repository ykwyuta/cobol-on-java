      *-----------------------------------------------------------------
      * CBLPRNC - ADVANCING to a mnemonic name (P-076)
      * Q: Which carriage control byte does the host put for WRITE
      *    AFTER ADVANCING C01 / C02 / C12, and does C02 go to the
      *    top of a page? The observation is the dataset PRTC, as in
      *    CBLPRNT.
      * Variants: ADV (default) only
      * Scenario notes (Japanese): docs/zos-probe/scenarios.md
      *-----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBLPRNC.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SPECIAL-NAMES.
           C01 IS TOP-OF-FORM
           C02 IS CHANNEL-2
           C12 IS CHANNEL-12.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT PRTC ASSIGN TO PRTC FILE STATUS IS W-FS.
       DATA DIVISION.
       FILE SECTION.
       FD  PRTC
           RECORDING MODE IS F.
       01  PRTC-REC            PIC X(20).
       WORKING-STORAGE SECTION.
       COPY PRBWS.
       01  W-FS                PIC X(2).
       PROCEDURE DIVISION.
       MAIN-PARA.
           OPEN OUTPUT PRTC
           MOVE "C01 FIRST" TO PRTC-REC
           WRITE PRTC-REC AFTER ADVANCING TOP-OF-FORM
           MOVE "C02" TO PRTC-REC
           WRITE PRTC-REC AFTER ADVANCING CHANNEL-2
           MOVE "C12" TO PRTC-REC
           WRITE PRTC-REC AFTER ADVANCING CHANNEL-12
           MOVE "C01 AGAIN" TO PRTC-REC
           WRITE PRTC-REC AFTER ADVANCING TOP-OF-FORM
           MOVE "BEFORE C02" TO PRTC-REC
           WRITE PRTC-REC BEFORE ADVANCING CHANNEL-2
           MOVE "LAST" TO PRTC-REC
           WRITE PRTC-REC AFTER ADVANCING 1 LINE
           MOVE "R.P076.PRTC.FS" TO PRB-CASE
           MOVE W-FS TO PRB-TEXT MOVE 0 TO PRB-LEN PERFORM PRB-EMIT
           CLOSE PRTC
           GOBACK.
       COPY PRBPD.
