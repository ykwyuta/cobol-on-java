//PRBBLDA  JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//*--------------------------------------------------------------------
//* PRBBLDA - assemble and bind the HLASM probes. Keep the job
//* output: the object code column of the listing is the second
//* observation for ASMDC1 / ASMDC2 / ASMDC3 (P-018, P-171).
//*--------------------------------------------------------------------
//ASM      PROC MEM=
//ASSEMBLE EXEC PGM=ASMA90,PARM='OBJECT,NODECK,LIST(133)'
//SYSLIB   DD DISP=SHR,DSN=SYS1.MACLIB
//SYSIN    DD DISP=SHR,DSN=YOURID.PROBE.SRC.ASM(&MEM)
//SYSLIN   DD DISP=SHR,DSN=YOURID.PROBE.OBJ(&MEM)
//SYSPRINT DD SYSOUT=*
//SYSUT1   DD UNIT=SYSALLDA,SPACE=(CYL,(1,1))
//BIND     EXEC PGM=IEWBLINK,COND=(4,LT,ASSEMBLE),
//            PARM='LIST,MAP,RENT'
//SYSLIN   DD DISP=SHR,DSN=YOURID.PROBE.OBJ(&MEM)
//SYSLMOD  DD DISP=SHR,DSN=YOURID.PROBE.LOAD(&MEM)
//SYSPRINT DD SYSOUT=*
//         PEND
//*
//ASMEXE   EXEC ASM,MEM=ASMEXE
//ASMPM    EXEC ASM,MEM=ASMPM
//ASMDC1   EXEC ASM,MEM=ASMDC1
//ASMDC2   EXEC ASM,MEM=ASMDC2
//ASMDC3   EXEC ASM,MEM=ASMDC3
