@echo off
setlocal
cd /d "%~dp0..\.."

echo ===================================================
echo  cobol-on-java DEMO #002 (File I/O: Write and Read)
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

echo [2/4] Compiling COBOL files (WRITE-DATA.cbl, READ-DATA.cbl)...
if not exist "demo\002\bin" mkdir "demo\002\bin"

call mvn exec:java -pl cobol-compiler -Dexec.mainClass=dev.cobolonjava.compiler.Main -Dexec.args="-d demo/002/bin demo/002/WRITE-DATA.cbl demo/002/READ-DATA.cbl" -q
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Step 1: Writing records to file (cobol.generated.WRITE_DATA)...
echo ---------------------------------------------------
cd /d "%~dp0"
if exist "CUSTFILE" del "CUSTFILE"
if exist "CUSTFILE.meta" del "CUSTFILE.meta"

java -cp "bin;..\..\cobol-runtime\target\classes;..\..\cobol-runtime\target\*" cobol.generated.WRITE_DATA
if errorlevel 1 (
    echo [ERROR] File write failed.
    goto ERROR_END
)

echo.
echo [4/4] Step 2: Reading records from file (cobol.generated.READ_DATA)...
echo ---------------------------------------------------
java -cp "bin;..\..\cobol-runtime\target\classes;..\..\cobol-runtime\target\*" cobol.generated.READ_DATA
if errorlevel 1 (
    echo [ERROR] File read failed.
    goto ERROR_END
)

echo ---------------------------------------------------
echo [SUCCESS] Demo #002 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
