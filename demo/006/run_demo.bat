@echo off
setlocal
cd /d "%~dp0..\.."

echo ==========================================================
echo  cobol-on-java DEMO #006 (JUnit 5 Unit Test and Mocking)
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

echo [2/3] Compiling Test Classes...
if not exist "demo\006\bin" mkdir "demo\006\bin"

set M2_REPO=%USERPROFILE%\.m2\repository
set JUNIT_CP=cobol-junit\target\classes;cobol-compiler\target\classes;cobol-runtime\target\classes;cobol-cics\target\classes;%M2_REPO%\org\junit\platform\junit-platform-launcher\1.11.4\junit-platform-launcher-1.11.4.jar;%M2_REPO%\org\junit\jupiter\junit-jupiter-api\5.11.4\junit-jupiter-api-5.11.4.jar;%M2_REPO%\org\junit\platform\junit-platform-commons\1.11.4\junit-platform-commons-1.11.4.jar;%M2_REPO%\org\junit\platform\junit-platform-engine\1.11.4\junit-platform-engine-1.11.4.jar;%M2_REPO%\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;%M2_REPO%\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;%M2_REPO%\org\antlr\antlr4-runtime\4.13.2\antlr4-runtime-4.13.2.jar;%M2_REPO%\org\ow2\asm\asm\9.7.1\asm-9.7.1.jar

javac -cp "%JUNIT_CP%" -d demo\006\bin demo\006\LoanAppTest.java demo\006\TestRunner.java
if errorlevel 1 (
    echo [ERROR] Test compilation failed.
    goto ERROR_END
)

echo.
echo [3/3] Executing JUnit 5 Tests (LoanAppTest)...
echo ----------------------------------------------------------
cd /d "%~dp0"

set RUN_CP=bin;..\..\cobol-junit\target\classes;..\..\cobol-compiler\target\classes;..\..\cobol-runtime\target\classes;..\..\cobol-cics\target\classes;%M2_REPO%\org\junit\platform\junit-platform-launcher\1.11.4\junit-platform-launcher-1.11.4.jar;%M2_REPO%\org\junit\jupiter\junit-jupiter-engine\5.11.4\junit-jupiter-engine-5.11.4.jar;%M2_REPO%\org\junit\jupiter\junit-jupiter-api\5.11.4\junit-jupiter-api-5.11.4.jar;%M2_REPO%\org\junit\platform\junit-platform-commons\1.11.4\junit-platform-commons-1.11.4.jar;%M2_REPO%\org\junit\platform\junit-platform-engine\1.11.4\junit-platform-engine-1.11.4.jar;%M2_REPO%\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;%M2_REPO%\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;%M2_REPO%\org\antlr\antlr4-runtime\4.13.2\antlr4-runtime-4.13.2.jar;%M2_REPO%\org\ow2\asm\asm\9.7.1\asm-9.7.1.jar

java -cp "%RUN_CP%" demo.TestRunner
if errorlevel 1 (
    echo [ERROR] JUnit tests failed.
    goto ERROR_END
)

echo ----------------------------------------------------------
echo [SUCCESS] Demo #006 completed successfully.
goto END

:ERROR_END
echo [ERROR] Demo failed.
pause
exit /b 1

:END
pause
exit /b 0
