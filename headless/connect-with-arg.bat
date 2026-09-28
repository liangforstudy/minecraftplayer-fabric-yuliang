@echo off
rem connect.bat with your own arguments: connect-with-arg.bat <bot> <host:port> [--wait]
rem Joins a bot at its title screen (standby) to a world. Logic in bots.ps1. macOS/Linux: connect.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" connect %*
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
