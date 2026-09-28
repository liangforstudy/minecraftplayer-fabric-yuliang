@echo off
rem disconnect, everything filled in: Bot1 leaves the world and waits at its title screen (still running;
rem connect-bot1.bat brings it back). Another bot: disconnect-with-arg.bat <bot>. Logic in bots.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" disconnect bot1
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
