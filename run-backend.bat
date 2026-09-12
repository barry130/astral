@echo off
rem Astral backend launcher (requires JDK 21)
rem 若 JAVA_HOME 未指向 JDK 21，请先设置，例如：
rem   set "JAVA_HOME=C:\path\to\jdk-21"
if not defined JAVA_HOME (
  echo [ERR] JAVA_HOME is not set. Please point it to a JDK 21 installation.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"
if not exist logs mkdir logs
rem 可选：存在 run-backend.local.bat 时先加载（用于填写本地数据库/Redis 环境变量，该文件已被 .gitignore 忽略）
if exist "%~dp0run-backend.local.bat" (
  echo [run-backend] loading run-backend.local.bat
  call "%~dp0run-backend.local.bat"
)
echo [run-backend] project=%PROJECT_DIR% JAVA_HOME=%JAVA_HOME%
mvn -pl astral-server spring-boot:run -Dspring-boot.run.arguments=--server.port=27000 > "%PROJECT_DIR%logs\backend.log" 2>&1
