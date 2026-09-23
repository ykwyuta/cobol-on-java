//JCLGDG0  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//*--------------------------------------------------------------------
//* JCLGDG0 - (re)define the generation data group used by JCLGDG
//* and JCLGDG2. Kept in its own job: this implementation refuses
//* (+1) for a base defined earlier in the same job (see JCLGDGD).
//* The DELETE fails the first time (the base does not exist yet);
//* that is expected.
//*--------------------------------------------------------------------
//DELGDG   EXEC PGM=IDCAMS
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  DELETE YOURID.PROBE.GDG GDG FORCE
/*
//DEFGDG   EXEC PGM=IDCAMS,COND=EVEN
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  DEFINE GDG(NAME(YOURID.PROBE.GDG) LIMIT(3) NOEMPTY SCRATCH)
/*
