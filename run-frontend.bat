@echo off
rem Astral frontend launcher
rem   astral-front (admin console)  http://localhost:3000  -> logs\frontend.log
rem   astral-blog  (public blog)    http://localhost:3001  -> own window
rem Node.js 24 LTS is the project standard (astral-front/Dockerfile uses node:24-alpine).
rem Next.js 16 requires >= 20.9; Node 20 is EOL (2026-04-30) and Node 18 cannot run it.
rem NOTE: keep this file ASCII-only. GBK codepage breaks UTF-8 comments.

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

if not exist "%~dp0logs" mkdir "%~dp0logs"

rem ==================== 2. public blog: astral-blog (port 3001) ====================
rem Blog dev server pins port 3001 in its package.json ("next dev -p 3001"),
rem so it can run next to the admin console on 3000.
set "BLOG_DIR=%~dp0astral-blog"
where pnpm >nul 2>nul
if errorlevel 1 (
  set "BLOG_PKG=npm"
  set "BLOG_CLI=npm"
) else (
  set "BLOG_PKG=pnpm"
  set "BLOG_CLI=pnpm"
)

if not exist "%BLOG_DIR%\package.json" (
  echo [WARN] astral-blog not found - starting admin console only.
  goto start_admin
)

netstat -ano | findstr /C:":3001 " | findstr /C:"LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [WARN] port 3001 already in use - astral-blog may already be running.
  goto start_admin
)

if not exist "%BLOG_DIR%\node_modules" (
  echo [run-frontend] astral-blog dependencies missing - installing with %BLOG_PKG% in a new window.
  start "Astral Blog Install" /d "%BLOG_DIR%" cmd /k "%BLOG_PKG% install"
  echo [run-frontend] after install finishes, run this script again to start the blog.
  goto start_admin
)

echo [run-frontend] starting astral-blog on http://localhost:3001 ^(window: Astral Blog^)
start "Astral Blog" /d "%BLOG_DIR%" cmd /k "%BLOG_CLI% run dev"

rem ==================== 3. admin console: astral-front (port 3000) ====================
:start_admin
set "PROJECT_DIR=%~dp0astral-front"
cd /d "%PROJECT_DIR%"
echo [run-frontend] project=%PROJECT_DIR%
echo [run-frontend] admin console http://localhost:3000
npm run dev > "%~dp0logs\frontend.log" 2>&1
