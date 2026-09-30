@echo off
rem Astral frontend launcher (Next.js dev server on port 3000)
rem Node.js 24 LTS is the project standard (astral-front/Dockerfile uses node:24-alpine).
rem Next.js 16 requires >= 20.9; Node 20 is EOL (2026-04-30) and Node 18 cannot run it.

rem ==================== 1. locate & verify Node.js ====================
where npm >nul 2>nul
if errorlevel 1 (
  echo [ERR] npm not found in PATH. Please install Node.js 24 LTS.
  exit /b 1
)
rem node -v prints "v24.11.1" -> treat 'v' and '.' as delimiters, token 1 is the major.
set "NODE_MAJOR="
for /f "tokens=1 delims=v." %%V in ('node -v') do set "NODE_MAJOR=%%V"
if not defined NODE_MAJOR (
  echo [ERR] unable to detect the Node.js version ^(node -v failed^).
  echo       Please install Node.js 24 LTS and put it on PATH.
  exit /b 1
)
if %NODE_MAJOR% LSS 20 (
  echo [ERR] Node.js %NODE_MAJOR% is too old: Next.js 16 requires Node.js 20.9+.
  echo       Please install Node.js 24 LTS.
  exit /b 1
)
if %NODE_MAJOR% EQU 20 (
  echo [WARN] Node.js 20 reached EOL on 2026-04-30. Next.js 16 still runs on it ^(>= 20.9^),
  echo        but Node.js 22 LTS / 24 LTS is recommended.
)
set /a NODE_IS_ODD=%NODE_MAJOR% %% 2
if %NODE_IS_ODD% EQU 1 (
  echo [WARN] Node.js %NODE_MAJOR% is a non-LTS ^(odd^) release; the frontend image pins node:24-alpine.
)

set "PROJECT_DIR=%~dp0astral-front"
cd /d "%PROJECT_DIR%"
if not exist ..\logs mkdir ..\logs
echo [run-frontend] project=%PROJECT_DIR%
npm run dev > "%~dp0logs\frontend.log" 2>&1
