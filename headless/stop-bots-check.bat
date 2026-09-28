@echo off
rem stop-bots.bat with --check filled in: lists the headless bot processes it would stop, stops nothing.
rem Logic in bots.ps1. macOS/Linux: stop-bots-check.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" stop --check
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
