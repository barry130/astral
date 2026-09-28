@echo off
rem ============================================================
rem Astral one-click launcher: backend + frontend in separate windows
rem
rem   run-all.bat               auto-locate JDK 25, start both
rem   run-all.bat D:\jdk-25     use the given JDK 25 directory
rem
rem JDK 25 lookup order:
rem   1. first argument
rem   2. system JAVA_HOME, only if java -version reports 25.x
rem   3. auto-detect: %USERPROFILE%\.jdks\temurin-25* / ms-25* / *-25*,
rem      then Program Files (Eclipse Adoptium / Java)
rem
rem Backend : run-backend.bat   logs\backend.log   port 27000
rem Frontend: run-frontend.bat  logs\frontend.log  port 3000
rem Closing a window stops that service. Close both to stop all.
rem NOTE: keep this file ASCII-only. GBK codepage breaks UTF-8 comments.
rem ============================================================
setlocal

set "VERFILE=%TEMP%\astral_jdk_version.txt"

rem ==================== 1. locate JDK 25 ====================
if "%~1"=="" goto jdk_auto
set "JAVA_HOME=%~1"
echo [run-all] using JDK 25 from argument: %JAVA_HOME%
goto jdk_ok

:jdk_auto
if not defined JAVA_HOME goto jdk_default
"%JAVA_HOME%\bin\java.exe" -version > "%VERFILE%" 2>&1
findstr /R "25\." "%VERFILE%" >nul
if errorlevel 1 goto jdk_default
echo [run-all] using system JAVA_HOME - verified as JDK 25: %JAVA_HOME%
goto jdk_ok

:jdk_default
rem auto-detect a local JDK 25 instead of hardcoding a machine-specific path
set "JAVA_HOME="
for /d %%D in ("%USERPROFILE%\.jdks\temurin-25*") do set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME for /d %%D in ("%USERPROFILE%\.jdks\ms-25*") do set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME for /d %%D in ("%USERPROFILE%\.jdks\*-25*") do set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*") do set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME for /d %%D in ("%ProgramFiles%\Java\jdk-25*") do set "JAVA_HOME=%%~fD"
if not defined JAVA_HOME (
    echo [ERR] JDK 25 not found - pass it as the first argument: run-all.bat ^<jdk25-dir^>
    exit /b 1
)
echo [run-all] JAVA_HOME missing or not JDK 25, auto-detected: %JAVA_HOME%

:jdk_ok
del "%VERFILE%" >nul 2>&1
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERR] no JDK found at: %JAVA_HOME%
    echo       usage: run-all.bat ^<jdk25-dir^>
    exit /b 1
)
rem final guard: verify the resolved JDK really is 25.x
"%JAVA_HOME%\bin\java.exe" -version > "%VERFILE%" 2>&1
findstr /R "25\." "%VERFILE%" >nul
if errorlevel 1 (
    echo [ERR] resolved JDK is not 25.x: %JAVA_HOME%
    type "%VERFILE%"
    echo       Spring Boot 4.1 needs JDK 25 ^(maven.compiler.release=25^).
    del "%VERFILE%" >nul 2>&1
    exit /b 1
)
del "%VERFILE%" >nul 2>&1

rem ==================== 2. pre-checks ====================
where npm >nul 2>nul
if errorlevel 1 (
    echo [WARN] npm not found - frontend needs Node.js 18+ - starting backend only
    set "SKIP_FRONT=1"
)

netstat -ano | findstr /C:":27000 " | findstr /C:"LISTENING" >nul 2>&1
if not errorlevel 1 echo [WARN] port 27000 already in use - backend may be running

if not defined SKIP_FRONT (
    netstat -ano | findstr /C:":3000 " | findstr /C:"LISTENING" >nul 2>&1
    if not errorlevel 1 echo [WARN] port 3000 already in use - frontend may be running
)

rem ==================== 3. start ====================
echo [run-all] starting backend - window title: Astral Backend - build and boot takes 1-2 minutes
start "Astral Backend" cmd /k call "%~dp0run-backend.bat"

if not defined SKIP_FRONT (
    echo [run-all] starting frontend - window title: Astral Frontend - ready in seconds
    start "Astral Frontend" cmd /k call "%~dp0run-frontend.bat"
)

echo.
echo [run-all] started:
echo    backend  http://localhost:27000  - ready when log shows Started AstralApplication
echo    frontend http://localhost:3000
echo    logs     logs\backend.log and logs\frontend.log
endlocal
