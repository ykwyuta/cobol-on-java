//JCLGDG2  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLGDG2 - relative generations in a later job (FR-114, P-060)
//* Q: which generations do (0), (-1), (-2) name after JCLGDG, and
//* what happens to (-3), which rolled off?
//*--------------------------------------------------------------------
//CUR      EXEC PGM=CBLDUMP
//INF      DD DSN=YOURID.PROBE.GDG(0),DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
G0
/*
//PREV1    EXEC PGM=CBLDUMP
//INF      DD DSN=YOURID.PROBE.GDG(-1),DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
GM1
/*
//PREV2    EXEC PGM=CBLDUMP
//INF      DD DSN=YOURID.PROBE.GDG(-2),DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
GM2
/*
//NEW      EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.GDG(+1),DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
2,GEN5
/*
//LIST     EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  LISTCAT ENTRIES(YOURID.PROBE.GDG) ALL
/*
//PREV3    EXEC PGM=CBLDUMP,COND=EVEN
//INF      DD DSN=YOURID.PROBE.GDG(-3),DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
GM3
/*
