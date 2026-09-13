@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #007 (CICS Transactions and Control)
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

echo [2/4] Compiling COBOL CICS programs (CICSMENU, INQPROG, FINPROG)...
if not exist "demo\007\bin" mkdir "demo\007\bin"

call mvn exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/007/bin demo/007/CICSMENU.cbl demo/007/INQPROG.cbl demo/007/FINPROG.cbl" -q
if errorlevel 1 (
    echo [ERROR] COBOL compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Compiling Java CICS Runner (CicsDemoRunner.java)...
javac -cp "cobol-cics\target\classes;cobol-runtime\target\classes;demo\007\bin" -d demo\007\bin demo\007\CicsDemoRunner.java
if errorlevel 1 (
    echo [ERROR] Java compilation failed.
    goto ERROR_END
)

echo.
echo [4/4] Executing CICS Transaction Task...
echo ----------------------------------------------------------
java -cp "demo\007\bin;cobol-cics\target\classes;cobol-runtime\target\classes;cobol-compiler\target\classes" demo.CicsDemoRunner
if errorlevel 1 (
    echo [ERROR] CICS task execution failed.
    goto ERROR_END
)

echo ----------------------------------------------------------
echo [SUCCESS] Demo #007 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
