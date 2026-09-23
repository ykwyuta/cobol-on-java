@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #010 (Maven standard layout)
echo ==========================================================

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

echo [1/4] Installing the processor and cobol-maven-plugin...
call mvn -q install -DskipTests
if errorlevel 1 goto ERROR_END

echo [2/4] Building demo 010 (COBOL + copybook + JCL + PROCLIB, PL/I + %%INCLUDE)...
call mvn -q -f demo\010\pom.xml clean package
if errorlevel 1 goto ERROR_END

echo [3/4] Running SALESJOB.jcl...
cd /d "%~dp0sales"
call mvn -q dependency:build-classpath -Dmdep.outputFile=target\cp.txt
if errorlevel 1 goto ERROR_END
set /p CP=<target\cp.txt
if not exist "target\data" mkdir "target\data"
java -cp "target\classes;%CP%" dev.cobolonjava.job.Main -d target\classes -w target\work -b target\data -p src\main\proclib src\main\jcl\SALESJOB.jcl
if errorlevel 1 goto ERROR_END

echo [4/4] Running the PL/I program HELLO...
cd /d "%~dp0greet"
call mvn -q dependency:build-classpath -Dmdep.outputFile=target\cp.txt
if errorlevel 1 goto ERROR_END
set /p CP=<target\cp.txt
java -cp "target\classes;%CP%" pli.generated.HELLO
if errorlevel 1 goto ERROR_END

echo ----------------------------------------------------------
echo [SUCCESS] Demo #010 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
