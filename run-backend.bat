@echo off
setlocal EnableExtensions
rem ============================================================
rem Astral backend launcher (requires JDK 25 + Maven 3.9+)
rem
rem   run-backend.bat                    auto-locate JDK 25 and Maven 3.9+
rem   run-backend.bat <maven-home>       use the given Maven 3.9+ directory
rem
rem JDK 25 is mandatory: the root pom sets maven.compiler.release=25
rem (Spring Boot 4.1 / Spring Framework 7). JDK 21 or 17 cannot compile it.
rem
rem Maven 3.9+ is mandatory as well. On this machine PATH resolves mvn to
rem Maven 3.6.1 (F:\sunlineMaven\apache-maven-3.6.1), which fails with:
rem   maven-install-plugin:3.1.4 requires Maven version 3.6.3
rem So the PATH mvn is only accepted after its own version probe passes, and the
rem script looks for a 3.9+ installation itself. Override the lookup with the
rem first argument or ASTRAL_MAVEN_HOME (also settable in run-backend.local.bat).
rem
rem Maven lookup order:
rem   1. first argument / ASTRAL_MAVEN_HOME
rem   2. M2_HOME, MAVEN_HOME - only if the version probe passes
rem   3. common install dirs (C:\apache-maven-*, Program Files, D:/E:/F:,
rem      F:\sunlineMaven, %USERPROFILE%, scoop, .m2\wrapper\dists)
rem   4. the mvn.cmd found on PATH - only if the version probe passes
rem NOTE: keep this file ASCII-only. GBK codepage breaks UTF-8 comments.
rem ============================================================

rem ==================== 1. locate JDK 25 ====================
if not defined JAVA_HOME (
  echo [ERR] JAVA_HOME is not set. Please point it to a JDK 25 installation.
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERR] no java.exe under JAVA_HOME: %JAVA_HOME%
  exit /b 1
)
rem Verify the JDK really is 25.x. javac prints to stdout (java -version goes to stderr),
rem so javac is the cleaner probe; skip the check if this is a JRE-only layout.
rem NOTE: JDK_VERFILE must be set BEFORE the block - cmd expands %VAR% when it parses
rem the block, so a set inside it would still be empty on the lines that use it.
set "JDK_VERFILE=%TEMP%\astral_backend_jdk.txt"
if exist "%JAVA_HOME%\bin\javac.exe" (
  "%JAVA_HOME%\bin\javac.exe" -version > "%JDK_VERFILE%" 2>&1
  findstr /R "25\." "%JDK_VERFILE%" >nul
  if errorlevel 1 (
    echo [ERR] JAVA_HOME is not JDK 25: %JAVA_HOME%
    type "%JDK_VERFILE%"
    echo       Spring Boot 4.1 requires JDK 25 ^(maven.compiler.release=25^).
    del "%JDK_VERFILE%" >nul 2>&1
    exit /b 1
  )
  del "%JDK_VERFILE%" >nul 2>&1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ==================== 2. locate Maven 3.9+ ====================
rem Never trust PATH blindly here: Maven 3.6.1 is too old for
rem maven-install-plugin 3.1.4 and the current plugin set, so every candidate
rem gets a version probe and only 3.9+ wins.
if not "%~1"=="" set "ASTRAL_MAVEN_HOME=%~1"
set "MVN_HOME="
set "MVN_VERFILE=%TEMP%\astral_maven_version.txt"

call :mvn_probe "%ASTRAL_MAVEN_HOME%"
call :mvn_probe "%M2_HOME%"
if /i not "%MAVEN_HOME%"=="%M2_HOME%" call :mvn_probe "%MAVEN_HOME%"

for /d %%D in ("C:\apache-maven-3.9*") do call :mvn_probe "%%~fD"
for /d %%D in ("C:\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("%ProgramFiles%\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("D:\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("E:\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("F:\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("F:\sunlineMaven\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("%USERPROFILE%\apache-maven-*") do call :mvn_probe "%%~fD"
for /d %%D in ("%USERPROFILE%\scoop\apps\maven\current") do call :mvn_probe "%%~fD"
rem maven-wrapper download cache: dists\<dist-id>\<hash>\apache-maven-3.9.x
for /d %%D in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-*") do for /d %%E in ("%%~fD\*") do for /d %%F in ("%%~fE\apache-maven-*") do call :mvn_probe "%%~fF"

rem last resort: the mvn.cmd on PATH, still subject to the version probe
if not defined MVN_HOME (
  for /f "delims=" %%P in ('where mvn.cmd 2^>nul') do for %%Q in ("%%~dpP..") do call :mvn_probe "%%~fQ"
)

if not defined MVN_HOME (
  echo [ERR] Maven 3.9+ not found. The mvn on PATH is too old for this build.
  echo       install Maven 3.9+ and pass its directory:
  echo         run-backend.bat ^<maven-home^>
  echo       or set ASTRAL_MAVEN_HOME, e.g. in run-backend.local.bat:
  echo         set "ASTRAL_MAVEN_HOME=C:\apache-maven-3.9.6"
  del "%MVN_VERFILE%" >nul 2>&1
  exit /b 1
)
set "MVN_CMD=%MVN_HOME%\bin\mvn.cmd"
rem prepend so any nested mvn invocation during the build resolves to the same one
set "PATH=%MVN_HOME%\bin;%PATH%"
del "%MVN_VERFILE%" >nul 2>&1

rem ==================== 3. run ====================
set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"
if not exist logs mkdir logs
rem Optional: load local overrides (gitignored), e.g. DB/Redis/storage keys
if exist "%~dp0run-backend.local.bat" (
  echo [run-backend] loading run-backend.local.bat
  call "%~dp0run-backend.local.bat"
)
echo [run-backend] project=%PROJECT_DIR% JAVA_HOME=%JAVA_HOME%
echo [run-backend] maven=%MVN_HOME%
rem Build dependency modules into local repo first (-am alone breaks spring-boot:run on the root pom)
call "%MVN_CMD%" -q -pl astral-server -am -DskipTests install > "%PROJECT_DIR%logs\backend.log" 2>&1
if errorlevel 1 (
  echo [run-backend] mvn install failed, see logs\backend.log
  exit /b 1
)
call "%MVN_CMD%" -q -pl astral-server spring-boot:run -DskipTests -Dspring-boot.run.arguments=--server.port=27000 >> "%PROJECT_DIR%logs\backend.log" 2>&1
exit /b %ERRORLEVEL%

rem ============================================================
rem  helper: probe one Maven home, set MVN_HOME when it is >= 3.9
rem ============================================================
:mvn_probe
if defined MVN_HOME exit /b 0
if "%~1"=="" exit /b 0
if not exist "%~1\bin\mvn.cmd" exit /b 0
call "%~1\bin\mvn.cmd" -version > "%MVN_VERFILE%" 2>&1
set "MVNV="
for /f "tokens=3" %%v in ('findstr /B /C:"Apache Maven" "%MVN_VERFILE%"') do set "MVNV=%%v"
if not defined MVNV exit /b 0
set "MVN_MAJ=0"
set "MVN_MIN=0"
for /f "tokens=1,2 delims=." %%a in ("%MVNV%") do (
  set "MVN_MAJ=%%a"
  if "%%b"=="" (set "MVN_MIN=0") else (set "MVN_MIN=%%b")
)
if %MVN_MAJ% GEQ 4 (
  set "MVN_HOME=%~1"
  echo [run-backend] found Maven %MVNV% at %~1
  exit /b 0
)
if %MVN_MAJ% EQU 3 if %MVN_MIN% GEQ 9 (
  set "MVN_HOME=%~1"
  echo [run-backend] found Maven %MVNV% at %~1
  exit /b 0
)
echo [run-backend] skip Maven %MVNV% ^(need 3.9+^): %~1
exit /b 0
