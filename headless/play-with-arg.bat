@echo off
rem play.bat with your own arguments: play-with-arg.bat "<world name>" [bot1 bot3 ...]
rem Your game straight into that world, then the bots once it loads. Logic in bots.ps1. macOS/Linux: play.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" play %*
rem Then this window becomes window 2 for Bot1 (the log; close it = stop Bot1) and opens window 1
rem (decisions + a command prompt), like standby-bot1-singleplayer.bat (owner, 2026-09-28)
if not errorlevel 1 powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" console-log bot1
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
