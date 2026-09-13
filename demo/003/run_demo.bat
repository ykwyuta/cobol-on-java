@echo off
setlocal
cd /d "%~dp0..\.."

echo ===================================================
echo  cobol-on-java DEMO #003 (JCL Batch Job Execution)
echo ===================================================
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

echo [2/4] Compiling COBOL files (GEN-SALES.cbl, PRT-SALES.cbl)...
if not exist "demo\003\bin" mkdir "demo\003\bin"

call mvn exec:java -pl cobol-compiler -Dexec.mainClass=dev.cobolonjava.compiler.Main -Dexec.args="-d demo/003/bin demo/003/GEN-SALES.cbl demo/003/PRT-SALES.cbl" -q
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Packaging cobol-job and dependencies...
if not exist "cobol-job\target\cobol-job-0.1.0-SNAPSHOT.jar" (
    call mvn package -DskipTests -q
    if errorlevel 1 (
        echo [ERROR] Build failed.
        goto ERROR_END
    )
)

echo.
echo [4/4] Executing JCL Batch Job (SALESJOB.jcl)...
echo ---------------------------------------------------
cd /d "%~dp0"
if exist "SALES.DAT" del "SALES.DAT"
if exist "SALES.DAT.meta" del "SALES.DAT.meta"

java -cp "bin;..\..\cobol-job\target\classes;..\..\cobol-job\target\*;..\..\cobol-runtime\target\classes;..\..\cobol-runtime\target\*" dev.cobolonjava.job.Main -d bin -w work -b . SALESJOB.jcl
if errorlevel 1 (
    echo [ERROR] JCL execution failed.
    goto ERROR_END
)

echo ---------------------------------------------------
echo [SUCCESS] Demo #003 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
