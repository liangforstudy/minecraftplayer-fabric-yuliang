@echo off
rem standby.bat with everything filled in: Bot1 boots to its title screen, then joins your singleplayer
rem world (open to LAN: 127.0.0.1:25565), waiting for it to open. Logic in bots.ps1. macOS/Linux: standby-bot1-singleplayer.sh
rem ZBOT_SHOW_LOG=1: print the bot's HeadlessMC log live while it boots and joins (owner, 2026-09-28)
set "ZBOT_SHOW_LOG=1"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" standby bot1
if not errorlevel 1 powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" connect bot1 127.0.0.1:25565 --wait
rem In the world: this window becomes window 2 (the full log; close it = stop Bot1) and opens window 1
rem (decisions + a command prompt). Closed window 1 by accident? console.bat bot1 (owner, 2026-09-28)
if not errorlevel 1 powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" console-log bot1
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
