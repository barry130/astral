@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================
rem  Astral 打包脚本 (Windows 本机执行, 用于上传到服务器构建)
rem
rem   用法:
rem     pack.bat                      打包到当前目录的上一级
rem     pack.bat D:\release           指定输出目录
rem
rem   产物: <输出目录>\astral-<时间戳>.tar.gz
rem ============================================================

rem ---- 源目录: 脚本位于 <repo>\scripts\, 仓库根 = 脚本目录上一级 ----
set "SRC_DIR=%~dp0.."
for %%i in ("!SRC_DIR!") do set "SRC_DIR=%%~fi"
for %%i in ("!SRC_DIR!") do set "PROJECT_DIR=%%~ni"

rem ---- 输出目录 ----
set "OUT_DIR=%~1"
if "!OUT_DIR!"=="" set "OUT_DIR=!SRC_DIR!\.."
for %%i in ("!OUT_DIR!") do set "OUT_DIR=%%~fi"

rem ---- 时间戳 ----
set "STAMP=unknown"
for /f "tokens=2 delims==" %%i in ('wmic os get localdatetime /value 2^>nul ^| find "="') do set "LDT=%%i"
if defined LDT set "STAMP=!LDT:~0,8!-!LDT:~8,6!"
set "NAME=astral-!STAMP!"
set "ARCHIVE=!OUT_DIR!\!NAME!.tar.gz"

echo ============================================
echo  源码目录 : !SRC_DIR!
echo  输出目录 : !OUT_DIR!
echo  产物名称 : !NAME!.tar.gz
echo ============================================

where tar >nul 2>&1
if errorlevel 1 (
  echo [ERR] 未找到 tar 命令, 需要 Windows 10 1803 及以上版本
  exit /b 1
)

if not exist "!SRC_DIR!\deploy\docker-compose.yml" (
  echo [ERR] 源码目录不完整: 缺少 deploy\docker-compose.yml
  exit /b 1
)

if not exist "!OUT_DIR!" mkdir "!OUT_DIR!"
if exist "!ARCHIVE!" del /f /q "!ARCHIVE!"

pushd "!SRC_DIR!\.."
tar --exclude=!PROJECT_DIR!/astral-front/node_modules --exclude=!PROJECT_DIR!/astral-front/.next --exclude=!PROJECT_DIR!/astral-front/out --exclude=!PROJECT_DIR!/*/target --exclude=!PROJECT_DIR!/data --exclude=!PROJECT_DIR!/logs --exclude=!PROJECT_DIR!/.git --exclude=!PROJECT_DIR!/.idea --exclude=!PROJECT_DIR!/.vscode --exclude=!PROJECT_DIR!/.claude --exclude=!PROJECT_DIR!/.qwen --exclude=!PROJECT_DIR!/*.log --exclude=!PROJECT_DIR!/deploy/.env --exclude=!PROJECT_DIR!/*.local --exclude=!PROJECT_DIR!/run-backend.local.bat --exclude=!PROJECT_DIR!/*/application-local.yml --exclude=!PROJECT_DIR!/*/application-local.yaml --exclude=!PROJECT_DIR!/*/*.tsbuildinfo -czf "!ARCHIVE!" "!PROJECT_DIR!"
set "RC=!ERRORLEVEL!"
popd

if not "!RC!"=="0" (
  echo [ERR] tar 打包失败, exit=!RC!
  exit /b 1
)
if not exist "!ARCHIVE!" (
  echo [ERR] 未生成产物
  exit /b 1
)

for %%f in ("!ARCHIVE!") do set "SIZE=%%~zf"

echo.
echo [OK] 打包完成
echo     产物: !ARCHIVE!
echo     大小: !SIZE! 字节
echo.
echo 下一步 (上传到服务器):
echo     scp "!ARCHIVE!" root@^<服务器IP^>:/opt/
echo.
echo 服务器部署:
echo     cd /opt ^&^& tar -xzf !NAME!.tar.gz
echo     cd /opt/!PROJECT_DIR!/deploy ^&^& docker compose up -d --build
echo.
endlocal