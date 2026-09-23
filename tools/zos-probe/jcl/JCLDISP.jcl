//JCLDISP  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLDISP - DISP before and after a step (FR-133, P-054)
//* Steps: create A with IEFBR14, write 3 records (OLD), append 2
//* (MOD), read (expect 5), write with DISP=(NEW,CATLG,DELETE) and
//* abend (is the dataset deleted?), LISTCAT both.
//* JCLDISP2 covers a DD without DISP for an existing dataset.
//*--------------------------------------------------------------------
//CLEAN    EXEC PGM=IEFBR14
//DISPA    DD DSN=YOURID.PROBE.DISP.A,DISP=(MOD,DELETE),
//            SPACE=(TRK,(1,1))
//DISPB    DD DSN=YOURID.PROBE.DISP.B,DISP=(MOD,DELETE),
//            SPACE=(TRK,(1,1))
//ALLOC    EXEC PGM=IEFBR14
//A        DD DSN=YOURID.PROBE.DISP.A,DISP=(NEW,CATLG),
//            SPACE=(TRK,(1,1))
//OLD      EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.DISP.A,DISP=OLD
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
3,OLD
/*
//MOD      EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.DISP.A,DISP=MOD
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
2,MOD
/*
//READ     EXEC PGM=CBLDUMP
//INF      DD DSN=YOURID.PROBE.DISP.A,DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
A
/*
//ABND     EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.DISP.B,DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
3,ABND,ABEND
/*
//LISTA    EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  LISTCAT ENTRIES(YOURID.PROBE.DISP.A) ALL
/*
//LISTB    EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  LISTCAT ENTRIES(YOURID.PROBE.DISP.B) ALL
/*
