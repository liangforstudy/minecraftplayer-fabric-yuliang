@echo off
rem disconnect-bot1.bat with your own argument: disconnect-with-arg.bat <bot>
rem The bot leaves the world and waits at its title screen (still running). Logic in bots.ps1. macOS/Linux: disconnect.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" disconnect %*
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
