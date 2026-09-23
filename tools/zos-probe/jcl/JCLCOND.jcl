//JCLCOND  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLCOND - which steps run: COND, EVEN / ONLY, IF / THEN / ELSE,
//* RC, ABEND, ABENDCC, RUN (design 90, FR-136)
//* Each step that runs shows "PRB J.<step> |RAN RC=nnnn|".
//* Record from JESMSGLG / JESYSMSG which steps ran, were flushed
//* or were not run, and the job's highest condition code.
//* Expected on the host (written from the JCL reference, to check):
//*   S01 RC4, S02 runs (4<4 false), S03 RC8, S04 bypassed
//*   (8<=8 true), S05 runs (IF S03.RC=8), S06 bypassed (ELSE),
//*   S07 abends S0C7, S08 flushed, S09 runs (EVEN), S10 runs
//*   (ONLY), S11 runs (IF ABEND), S12 runs (ABENDCC=S0C7),
//*   (IF ... RUN is in JCLCONR.)
//*   S14 bypassed: EVEN, but 0 NE S01.RC (4) is true.
//*--------------------------------------------------------------------
//S01      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S01,4
/*
//S02      EXEC PGM=CBLRC,COND=(4,LT)
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S02,0
/*
//S03      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S03,8
/*
//S04      EXEC PGM=CBLRC,COND=(8,LE,S03)
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S04,0
/*
//IF1      IF (S03.RC = 8) THEN
//S05      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S05,0
/*
//         ELSE
//S06      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S06,0
/*
//         ENDIF
//S07      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S07,ABEND
/*
//S08      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S08,0
/*
//S09      EXEC PGM=CBLRC,COND=EVEN
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S09,0
/*
//S10      EXEC PGM=CBLRC,COND=ONLY
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S10,0
/*
//IF2      IF ABEND THEN
//S11      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S11,0
/*
//         ENDIF
//IF3      IF (S07.ABENDCC = S0C7) THEN
//S12      EXEC PGM=CBLRC
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S12,0
/*
//         ENDIF
//S14      EXEC PGM=CBLRC,COND=((0,NE,S01),EVEN)
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
S14,2
/*
