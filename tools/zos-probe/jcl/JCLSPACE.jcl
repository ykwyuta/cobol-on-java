//JCLSPACE JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLSPACE - out-of-space abends (P-052)
//* Each step writes more 80-byte records than its SPACE allows.
//* Q: which completion code (SD37 / SB37 / SE37, reason code) and at
//* which record does each stop? The last PRB line of each step does
//* not appear when it abends; count the records with LISTCAT/IDCAMS.
//* Record the volume type (SMS or not): extents depend on it.
//*--------------------------------------------------------------------
//CLEAN    EXEC PGM=IEFBR14
//SPD37    DD DSN=YOURID.PROBE.SP.D37,DISP=(MOD,DELETE),
//            SPACE=(TRK,(1,1))
//SPB37    DD DSN=YOURID.PROBE.SP.B37,DISP=(MOD,DELETE),
//            SPACE=(TRK,(1,1))
//SPE37    DD DSN=YOURID.PROBE.SP.E37,DISP=(MOD,DELETE),
//            SPACE=(TRK,(1,1))
//D37      EXEC PGM=CBLGEN,COND=EVEN
//OUTF     DD DSN=YOURID.PROBE.SP.D37,DISP=(NEW,CATLG,CATLG),
//            SPACE=(TRK,(1,0))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
50000,D37
/*
//B37      EXEC PGM=CBLGEN,COND=EVEN
//OUTF     DD DSN=YOURID.PROBE.SP.B37,DISP=(NEW,CATLG,CATLG),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
200000,B37
/*
//*  A member of a small PDS: SE37 is the usual report.
//E37      EXEC PGM=CBLGEN,COND=EVEN
//OUTF     DD DSN=YOURID.PROBE.SP.E37(MEM1),DISP=(NEW,CATLG,CATLG),
//            SPACE=(TRK,(1,1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
200000,E37
/*
//LIST     EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  LISTCAT ENTRIES(YOURID.PROBE.SP.D37 -
    YOURID.PROBE.SP.B37 YOURID.PROBE.SP.E37) ALL
/*
