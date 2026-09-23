//JCLCP    JOB (ACCT),'ZOS PROBE',CLASS=A,MSGCLASS=X,REGION=0M
//JOBLIB   DD DISP=SHR,DSN=YOURID.PROBE.LOAD
//*--------------------------------------------------------------------
//* JCLCP - code page tables (P-012, P-002, P-177)
//* Step NAT: CBLCP writes FUNCTION NATIONAL-OF for every SBCS byte of
//* 1047 / 037 / 930 / 939 / 1390 / 1399 and every DBCS pair of the
//* mixed ones. Step ICONV: the same bytes through z/OS iconv, as a
//* cross-check. Download OUT.CPNAT (text) and the ICONV stdout.
//* If NAT abends on an invalid pair, split the D cards (from/to).
//*--------------------------------------------------------------------
//CLEAN    EXEC PGM=IEFBR14
//CPNAT    DD DSN=YOURID.PROBE.OUT.CPNAT,DISP=(MOD,DELETE),
//            SPACE=(TRK,(1,1))
//NAT      EXEC PGM=CBLCP
//CPOUT    DD DSN=YOURID.PROBE.OUT.CPNAT,DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(20,10))
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSIN    DD *
01047 S 000 255
00037 S 000 255
00930 S 000 255
00939 S 000 255
01390 S 000 255
01399 S 000 255
00930 D 064 254
00939 D 064 254
01390 D 064 254
01399 D 064 254
END
/*
//ICONV    EXEC PGM=BPXBATCH,COND=EVEN
//STDOUT   DD SYSOUT=*
//STDERR   DD SYSOUT=*
//STDPARM  DD *
SH for cp in IBM-1047 IBM-037 IBM-930 IBM-939 IBM-1390 IBM-1399;
do i=0; while [ $i -lt 256 ]; do
h=$(printf %02X $i); o=$(printf %03o $i);
u=$(printf "\\$o" | iconv -f $cp -t UTF-16BE 2>/dev/null
| od -An -tx1 | tr -d ' \n');
echo "PRB ICONV.$cp.S.$h $u ||"; i=$((i+1)); done; done
/*
