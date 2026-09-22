@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #009 (BMS + COBOL + H2 TODO list)
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

echo [2/4] Compiling the COBOL program (TODOAPP.cbl)...
echo        -I demo/009 makes COPY TODOSET read TODOSET.bms and build
echo        the symbolic map; DFHAID and SQLCA come from the compiler.
if not exist "demo\009\bin" mkdir "demo\009\bin"

call mvn exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/009/bin -I demo/009 demo/009/TODOAPP.cbl" -q
if errorlevel 1 (
    echo [ERROR] COBOL compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Compiling the terminal (TodoTerminal.java)...
set M2_REPO=%USERPROFILE%\.m2\repository
set DEMO_CP=cobol-cics\target\classes;cobol-runtime\target\classes;cobol-db2\target\classes;cobol-spring-boot-4-autoconfigure\target\classes;%M2_REPO%\org\springframework\spring-jdbc\7.0.9\spring-jdbc-7.0.9.jar;%M2_REPO%\org\springframework\spring-tx\7.0.9\spring-tx-7.0.9.jar;%M2_REPO%\org\springframework\spring-beans\7.0.9\spring-beans-7.0.9.jar;%M2_REPO%\org\springframework\spring-core\7.0.9\spring-core-7.0.9.jar;%M2_REPO%\commons-logging\commons-logging\1.3.6\commons-logging-1.3.6.jar;%M2_REPO%\com\h2database\h2\2.4.240\h2-2.4.240.jar

javac -cp "%DEMO_CP%" -d demo\009\bin demo\009\TodoTerminal.java
if errorlevel 1 (
    echo [ERROR] Java compilation failed.
    goto ERROR_END
)

echo.
echo [4/4] Starting the 3270 style terminal...
echo ----------------------------------------------------------
java -cp "demo\009\bin;%DEMO_CP%" demo.TodoTerminal %*
if errorlevel 1 (
    echo [ERROR] Execution failed.
    goto ERROR_END
)

echo ----------------------------------------------------------
echo [SUCCESS] Demo #009 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
