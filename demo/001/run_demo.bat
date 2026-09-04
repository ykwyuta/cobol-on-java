@echo off
setlocal
cd /d "%~dp0..\.."

echo ===================================================
echo  cobol-on-java DEMO #001 (Multi-Program Linkage)
echo ===================================================
echo.

echo [1/3] Checking Environment...
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

echo [2/3] Compiling COBOL files (MAIN-JOB.cbl, CALC-TAX.cbl)...
if not exist "demo\001\bin" mkdir "demo\001\bin"

call mvn exec:java -pl cobol-compiler -Dexec.mainClass=dev.cobolonjava.compiler.Main -Dexec.args="-d demo/001/bin demo/001/MAIN-JOB.cbl demo/001/CALC-TAX.cbl" -q
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    goto ERROR_END
)

echo.
echo [3/3] Running Main Program (cobol.generated.MAIN_JOB)...
echo ---------------------------------------------------
java -cp "demo/001/bin;cobol-runtime/target/classes;cobol-runtime/target/*" cobol.generated.MAIN_JOB
if errorlevel 1 (
    echo [ERROR] Execution failed.
    goto ERROR_END
)

echo ---------------------------------------------------
echo [SUCCESS] Demo completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
