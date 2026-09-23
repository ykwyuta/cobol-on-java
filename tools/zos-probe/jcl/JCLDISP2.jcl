//JCLDISP2 JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLDISP2 - a DD without DISP naming a dataset that already exists
//* and is cataloged (P-054). The default is DISP=(NEW,DELETE).
//* Q: JCL error at allocation (duplicate name), or does the step run?
//* Run JCLDISP first (it leaves YOURID.PROBE.DISP.A).
//*--------------------------------------------------------------------
//NODISP   EXEC PGM=CBLGEN
//OUTF     DD DSN=YOURID.PROBE.DISP.A,SPACE=(TRK,(1,1))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
1,NODISP
/*
//READ     EXEC PGM=CBLDUMP,COND=EVEN
//INF      DD DSN=YOURID.PROBE.DISP.A,DISP=SHR
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
A2
/*
