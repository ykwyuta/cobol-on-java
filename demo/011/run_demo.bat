@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #011 (3270 terminal over TN3270)
echo ==========================================================
echo.

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java command not found. Please install JDK 21+.
    exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] mvn command not found. Please install Apache Maven.
    exit /b 1
)

echo [1/3] Building the processor (cobol-runtime, cobol-cics, cobol-compiler)...
call mvn -q install -pl cobol-compiler -am -DskipTests
if errorlevel 1 exit /b 1

echo [2/3] Compiling the COBOL program (GREET.cbl)...
if exist "demo\011\bin" rmdir /s /q "demo\011\bin"
call mvn -q exec:java -pl cobol-compiler "-Dexec.mainClass=dev.cobolonjava.compiler.Main" "-Dexec.args=-d demo/011/bin -I demo/011 demo/011/GREET.cbl"
if errorlevel 1 exit /b 1

echo [3/3] Starting the region (APPLID COBOLJVM) on localhost:3270...
echo       Connect a 3270 emulator (e.g. wc3270 localhost:3270),
echo       type GRT1 and press Enter. Ctrl-C stops.
echo ----------------------------------------------------------
java -cp "demo\011\bin;cobol-cics\target\classes;cobol-runtime\target\classes" dev.cobolonjava.cics.tn3270.Tn3270Main --bms demo\011\GRTSET.bms --transaction GRT1=GREET --port 3270
