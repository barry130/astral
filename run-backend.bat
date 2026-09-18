@echo off
rem Astral backend launcher (requires JDK 21)
rem Set JAVA_HOME to a JDK 21 installation, e.g.
rem   set "JAVA_HOME=C:\path\to\jdk-21"
if not defined JAVA_HOME (
  echo [ERR] JAVA_HOME is not set. Please point it to a JDK 21 installation.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"
if not exist logs mkdir logs
rem Optional: load local overrides (gitignored), e.g. DB/Redis/storage keys
if exist "%~dp0run-backend.local.bat" (
  echo [run-backend] loading run-backend.local.bat
  call "%~dp0run-backend.local.bat"
)
echo [run-backend] project=%PROJECT_DIR% JAVA_HOME=%JAVA_HOME%
rem Build dependency modules into local repo first (-am alone breaks spring-boot:run on the root pom)
call mvn -q -pl astral-server -am -DskipTests install > "%PROJECT_DIR%logs\backend.log" 2>&1
if errorlevel 1 (
  echo [run-backend] mvn install failed, see logs\backend.log
  exit /b 1
)
call mvn -q -pl astral-server spring-boot:run -DskipTests -Dspring-boot.run.arguments=--server.port=27000 >> "%PROJECT_DIR%logs\backend.log" 2>&1
