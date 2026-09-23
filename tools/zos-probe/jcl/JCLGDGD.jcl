//JCLGDGD  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//*--------------------------------------------------------------------
//* JCLGDGD - a GDG base defined in the same job (FR-114, design 90)
//* Q: can a step refer to (+1) of a base that an earlier step of
//*    the same job defined? This implementation says no (JCL error
//*    IEF212I, design 90 "the base did not exist when the job
//*    started"). That claim has not been checked on a host.
//*--------------------------------------------------------------------
//DELGDG   EXEC PGM=IDCAMS
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  DELETE YOURID.PROBE.GDGD GDG FORCE
/*
//DEFGDG   EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  DEFINE GDG(NAME(YOURID.PROBE.GDGD) LIMIT(2) NOEMPTY SCRATCH)
/*
//NEW1     EXEC PGM=IEFBR14,COND=EVEN
//OUT      DD DSN=YOURID.PROBE.GDGD(+1),DISP=(NEW,CATLG,DELETE),
//            SPACE=(TRK,(1,1))
//LIST     EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  LISTCAT ENTRIES(YOURID.PROBE.GDGD) ALL
/*
