@echo off
rem connect, everything filled in: Bot1 joins your singleplayer world (open to LAN: 127.0.0.1:25565), waiting for it
rem to open. For a bot left at its title screen. Other bot or address: connect-with-arg.bat. Logic in bots.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" connect bot1 127.0.0.1:25565 --wait
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
