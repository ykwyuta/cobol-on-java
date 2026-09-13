@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #005 (Java ^<^=^> COBOL Interop)
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

echo [2/4] Compiling COBOL file (ORDER-PROCESS.cbl)...
if not exist "demo\005\bin" mkdir "demo\005\bin"

call mvn exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/005/bin demo/005/ORDER-PROCESS.cbl" -q
if errorlevel 1 (
    echo [ERROR] COBOL compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Compiling Java Runner (Demo005Main.java)...
javac -cp "cobol-runtime\target\classes;cobol-runtime\target\*" -d demo\005\bin demo\005\Demo005Main.java
if errorlevel 1 (
    echo [ERROR] Java compilation failed.
    goto ERROR_END
)

echo.
echo [4/4] Running Java Application invoking COBOL with callback...
echo ----------------------------------------------------------
java -cp "demo\005\bin;cobol-runtime\target\classes;cobol-runtime\target\*" demo.Demo005Main
if errorlevel 1 (
    echo [ERROR] Execution failed.
    goto ERROR_END
)

echo ----------------------------------------------------------
echo [SUCCESS] Demo #005 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
