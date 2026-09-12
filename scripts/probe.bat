@echo off
set "SRC_DIR=%~dp0.."
echo [1 raw ] %SRC_DIR%
for %%i in ("%SRC_DIR%") do set "SRC_DIR=%%~fi"
echo [2 abs ] %SRC_DIR%
for %%i in ("%SRC_DIR%") do set "PROJECT_DIR=%%~ni"
echo [3 proj] %PROJECT_DIR%
if exist "%SRC_DIR%\deploy\docker-compose.yml" (echo [4 yml ] FOUND) else (echo [4 yml ] MISSING)
if exist "%SRC_DIR%\..\deploy\docker-compose.yml" (echo [5 up  ] FOUND) else (echo [5 up  ] MISSING)