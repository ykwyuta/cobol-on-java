@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #004 (JCL Utility: SORT and IEBGENER)
echo ==========================================================
echo.

echo [1/4] Checking Environment...
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java command not found. Please install JDK 21+.
    goto ERROR_END
)
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] mvn command not found. Please install Apache Maven.
    goto ERROR_END
)

echo [2/4] Compiling COBOL files (GEN-TRANS.cbl, PRT-REPORT.cbl)...
if not exist "demo\004\bin" mkdir "demo\004\bin"

call mvn exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/004/bin demo/004/GEN-TRANS.cbl demo/004/PRT-REPORT.cbl" -q
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Ensuring cobol-job is packaged...
if not exist "cobol-job\target\cobol-job-0.1.0-SNAPSHOT.jar" (
    call mvn package -DskipTests -q
    if errorlevel 1 (
        echo [ERROR] Build failed.
        goto ERROR_END
    )
)

echo.
echo [4/4] Executing JCL Utility Job (UTILJOB.jcl)...
echo ----------------------------------------------------------
cd /d "%~dp0"
if exist "RAWTRANS.DAT" del "RAWTRANS.DAT"
if exist "RAWTRANS.DAT.meta" del "RAWTRANS.DAT.meta"
if exist "BAKTRANS.DAT" del "BAKTRANS.DAT"
if exist "BAKTRANS.DAT.meta" del "BAKTRANS.DAT.meta"
if exist "FILTERED.DAT" del "FILTERED.DAT"
if exist "FILTERED.DAT.meta" del "FILTERED.DAT.meta"

java -cp "bin;..\..\cobol-job\target\classes;..\..\cobol-job\target\*;..\..\cobol-runtime\target\classes;..\..\cobol-runtime\target\*" dev.cobolonjava.job.Main -d bin -w work -b . UTILJOB.jcl
if errorlevel 1 (
    echo [ERROR] JCL execution failed.
    goto ERROR_END
)

echo ----------------------------------------------------------
echo [SUCCESS] Demo #004 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
