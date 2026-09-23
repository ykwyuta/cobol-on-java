//PRBALLOC JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//*--------------------------------------------------------------------
//* PRBALLOC - allocate the probe libraries (run once)
//* Before submitting any probe job: change YOURID to your high-level
//* qualifier in every member (ISPF: C YOURID xxx ALL), and fix the
//* JOB card for your site. See docs/zos-probe/runbook.md.
//*--------------------------------------------------------------------
//ALLOC    EXEC PGM=IEFBR14
//COBOL    DD DSN=YOURID.PROBE.SRC.COBOL,DISP=(NEW,CATLG),
//            SPACE=(TRK,(30,15,20)),RECFM=FB,LRECL=80
//COPY     DD DSN=YOURID.PROBE.SRC.COPY,DISP=(NEW,CATLG),
//            SPACE=(TRK,(5,5,10)),RECFM=FB,LRECL=80
//PLI      DD DSN=YOURID.PROBE.SRC.PLI,DISP=(NEW,CATLG),
//            SPACE=(TRK,(10,5,10)),RECFM=FB,LRECL=80
//ASM      DD DSN=YOURID.PROBE.SRC.ASM,DISP=(NEW,CATLG),
//            SPACE=(TRK,(10,5,10)),RECFM=FB,LRECL=80
//JCL      DD DSN=YOURID.PROBE.JCL,DISP=(NEW,CATLG),
//            SPACE=(TRK,(15,15,20)),RECFM=FB,LRECL=80
//OBJ      DD DSN=YOURID.PROBE.OBJ,DISP=(NEW,CATLG),
//            SPACE=(CYL,(5,5,40)),RECFM=FB,LRECL=80
//LOAD     DD DSN=YOURID.PROBE.LOAD,DISP=(NEW,CATLG),
//            SPACE=(CYL,(5,5,40)),DSNTYPE=LIBRARY,RECFM=U,
//            BLKSIZE=32760
