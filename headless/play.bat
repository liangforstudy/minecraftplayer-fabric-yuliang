@echo off
rem The everyday start, everything filled in: your game straight into "New World", then Bot1 once it loads.
rem Another world or other bots: play-with-arg.bat "<world name>" [bot1 bot3 ...]. Logic in bots.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" play "New World" bot1
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
