@echo off
rem Astral frontend launcher (Next.js dev server on port 3000)
rem 需要 Node.js 18+ 并已加入 PATH
where npm >nul 2>nul
if errorlevel 1 (
  echo [ERR] npm not found in PATH. Please install Node.js 18+.
  exit /b 1
)
set "PROJECT_DIR=%~dp0astral-front"
cd /d "%PROJECT_DIR%"
if not exist ..\logs mkdir ..\logs
echo [run-frontend] project=%PROJECT_DIR%
npm run dev > "%~dp0logs\frontend.log" 2>&1
