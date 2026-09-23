//JCLCONR  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLCONR - IF with RUN and RC after an abend (FR-136)
//* Kept apart from JCLCOND: this implementation cannot read
//* "IF (step.RUN = TRUE)" and refuses the whole job.
//* Q: after R02 abends, which of R03 (R01.RUN), R04 (R02.RUN),
//*    R05 (R01.RC = 0) and R06 (NOT ABEND) run?
//*--------------------------------------------------------------------
//R01      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
R01,0
/*
//R02      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
R02,ABEND
/*
//IFA      IF (R01.RUN = TRUE) THEN
//R03      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
R03,0
/*
//         ENDIF
//IFB      IF (R02.RUN = TRUE) THEN
//R04      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
R04,0
/*
//         ENDIF
//IFC      IF (R01.RC = 0) THEN
//R05      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
R05,0
/*
//         ENDIF
//IFD      IF (NOT ABEND) THEN
//R06      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
R06,0
/*
//         ENDIF
