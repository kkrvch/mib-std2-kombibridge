@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set "build_exit=%errorlevel%"
echo.
pause
exit /b %build_exit%
