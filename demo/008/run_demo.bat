@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #008 (Spring Boot and Db2 SQL Interop)
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

echo [2/4] Compiling COBOL program (ACC-PROCESS.cbl)...
if not exist "demo\008\bin" mkdir "demo\008\bin"

call mvn exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/008/bin demo/008/ACC-PROCESS.cbl" -q
if errorlevel 1 (
    echo [ERROR] COBOL compilation failed.
    goto ERROR_END
)

echo.
echo [3/4] Compiling Java Runner (SpringDb2DemoMain.java)...
set M2_REPO=%USERPROFILE%\.m2\repository
set SPRING_CP=cobol-spring-boot-4-autoconfigure\target\classes;cobol-db2\target\classes;cobol-runtime\target\classes;%M2_REPO%\org\springframework\spring-jdbc\7.0.9\spring-jdbc-7.0.9.jar;%M2_REPO%\org\springframework\spring-tx\7.0.9\spring-tx-7.0.9.jar;%M2_REPO%\org\springframework\spring-beans\7.0.9\spring-beans-7.0.9.jar;%M2_REPO%\org\springframework\spring-core\7.0.9\spring-core-7.0.9.jar;%M2_REPO%\commons-logging\commons-logging\1.3.6\commons-logging-1.3.6.jar;%M2_REPO%\com\h2database\h2\2.4.240\h2-2.4.240.jar;demo\008\bin

javac -cp "%SPRING_CP%" -d demo\008\bin demo\008\SpringDb2DemoMain.java
if errorlevel 1 (
    echo [ERROR] Java compilation failed.
    goto ERROR_END
)

echo.
echo [4/4] Running Spring Managed Transaction with COBOL and SQL...
echo ----------------------------------------------------------
java -cp "demo\008\bin;%SPRING_CP%" demo.SpringDb2DemoMain
if errorlevel 1 (
    echo [ERROR] Execution failed.
    goto ERROR_END
)

echo ----------------------------------------------------------
echo [SUCCESS] Demo #008 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
