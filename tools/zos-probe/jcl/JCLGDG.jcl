//JCLGDG   JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLGDG - generation data group (FR-114, P-060)
//* LIMIT(3) SCRATCH NOEMPTY. Four new generations in one job, (+1)
//* to (+4), then read (+1) and (+2) again in the same job, LISTCAT.
//* Q: GnnnnV00 names; when G0001 rolls off (step end or job end);
//* do relative numbers stay fixed for the whole job?
//* Run JCLGDG0 first (it defines the base), and JCLGDG2 afterwards
//* for (0), (-1), (-2) in a new job.
//*--------------------------------------------------------------------
//NEW1     EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.GDG(+1),DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
2,GEN1
/*
//NEW2     EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.GDG(+2),DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
2,GEN2
/*
//NEW3     EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.GDG(+3),DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
2,GEN3
/*
//NEW4     EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.GDG(+4),DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
2,GEN4
/*
//READ1    EXEC PGM=CBLDUMP
//INF      DD DSN=YOURID.PROBE.GDG(+1),DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
P1
/*
//READ2    EXEC PGM=CBLDUMP
//INF      DD DSN=YOURID.PROBE.GDG(+2),DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
P2
/*
//LIST     EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  LISTCAT ENTRIES(YOURID.PROBE.GDG) ALL
/*
