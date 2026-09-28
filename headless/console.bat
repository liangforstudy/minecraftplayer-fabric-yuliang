@echo off
rem Window 1 for a running bot: its decisions, chat and whispers, and a prompt that sends commands to it.
rem Double-click it: the running bot (Bot1 if several). Or: console.bat bot3. Logic in bots.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" console %*
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
