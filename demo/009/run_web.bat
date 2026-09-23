@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #009 WEB (Spring Boot + Thymeleaf)
echo ==========================================================
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

echo [2/3] Compiling the COBOL program (TODOAPP.cbl)...
echo        The same TODOAPP.cbl and TODOSET.bms as the terminal demo.
if not exist "demo\009\bin" mkdir "demo\009\bin"

call mvn exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/009/bin -I demo/009 demo/009/TODOAPP.cbl" -q
if errorlevel 1 (
    echo [ERROR] COBOL compilation failed.
    goto ERROR_END
)

echo.
echo [3/3] Starting Spring Boot...
echo ----------------------------------------------------------
echo  Open   http://localhost:8080/
echo  Login  demo / demo
echo  Stop   Ctrl+C
echo ----------------------------------------------------------
call mvn -q -f demo/009/web/pom.xml spring-boot:run
if errorlevel 1 (
    echo [ERROR] Execution failed.
    goto ERROR_END
)
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
exit /b 0
