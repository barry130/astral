@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Source package: scripts\pack.bat [-o output-directory] [-n archive-name]
rem Python 3 and Git are required. Both platforms use the same safe source manifest.
python "%~dp0pack.py" %*
exit /b %ERRORLEVEL%
