//JCLTSO   JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLTSO - TSO commands in batch (P-058)
//* Which commands do real batch jobs need beyond LISTDS? Record the
//* SYSTSPRT text and the step return code of each command.
//*--------------------------------------------------------------------
//TSO      EXEC PGM=IKJEFT01
//SYSTSPRT DD SYSOUT=*
//SYSTSIN  DD *
  ALLOCATE DATASET('YOURID.PROBE.TSO.A') NEW CATALOG +
    SPACE(1,1) TRACKS RECFM(F B) LRECL(80)
  LISTDS 'YOURID.PROBE.TSO.A' STATUS
  LISTCAT ENTRIES('YOURID.PROBE.TSO.A')
  RENAME 'YOURID.PROBE.TSO.A' 'YOURID.PROBE.TSO.B'
  LISTDS 'YOURID.PROBE.TSO.B'
  FREE DATASET('YOURID.PROBE.TSO.A')
  DELETE 'YOURID.PROBE.TSO.B'
  LISTDS 'YOURID.PROBE.TSO.B'
/*
