//SALESJOB JOB  (ACCT),'SALES BATCH',CLASS=A
//* ==================================================================
//*  COBOL-ON-JAVA DEMO #003: JCL BATCH JOB
//*  Step 1 (INIT): Utility IEFBR14 initialization step
//*  Step 2 (GEN) : Run GEN-SALES to write records into SALES.DAT
//*  Step 3 (RPT) : If GEN is successful, run PRT-SALES to print report
//*  Step 4 (SKIP): Skipped step to demonstrate COND condition bypass
//* ==================================================================
//*
//INIT     EXEC PGM=IEFBR14
//*
//GEN      EXEC PGM=GEN_SALES,PARM='202609'
//OUTDD    DD   DSN=SALES.DAT,DISP=(NEW,CATLG)
//SYSPRINT DD   SYSOUT=*
//*
//         IF (GEN.RC = 0) THEN
//RPT      EXEC PGM=PRT_SALES
//INDD     DD   DSN=SALES.DAT,DISP=SHR
//SYSPRINT DD   SYSOUT=*
//         ENDIF
//*
//SKIP     EXEC PGM=IEFBR14,COND=(0,LE,GEN)
