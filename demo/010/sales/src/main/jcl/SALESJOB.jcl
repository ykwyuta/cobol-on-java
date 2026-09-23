//SALESJOB JOB  (ACCT),'SALES BATCH',CLASS=A
//* ==================================================================
//*  COBOL-ON-JAVA DEMO #010: MAVEN STANDARD LAYOUT
//*  GEN    : GEN-SALES writes SALES.DAT (PARM = target month)
//*  REPORT : cataloged procedure SALESRPT from PROCLIB prints it
//* ==================================================================
//GEN      EXEC PGM=GEN_SALES,PARM='202609'
//OUTDD    DD   DSN=SALES.DAT,DISP=(NEW,CATLG)
//SYSPRINT DD   SYSOUT=*
//         IF (GEN.RC = 0) THEN
//REPORT   EXEC SALESRPT
//         ENDIF
