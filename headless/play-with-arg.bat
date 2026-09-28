@echo off
rem play.bat with your own arguments: play-with-arg.bat "<world name>" [bot1 bot3 ...]
rem Your game straight into that world, then the bots once it loads. Logic in bots.ps1. macOS/Linux: play.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" play %*
rem Then the console - Windows Terminal, a tab per bot: log on top (close = stop), commands below;
rem without Windows Terminal (or ZBOT_CONSOLE=windows) the two separate windows (owner, 2026-09-28)
if not errorlevel 1 powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" consoles %*
set "ZBOT_RC=%errorlevel%"
rem 10 = the console opened in Windows Terminal (a tab per bot): nothing left to read here
if "%ZBOT_RC%"=="10" exit /b 0
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
