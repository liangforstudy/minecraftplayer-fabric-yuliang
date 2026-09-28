@echo off
rem Thin wrapper - all logic is in bots.ps1 (Windows PowerShell, no Python needed). macOS/Linux: the .sh files.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" run %*
set "ZBOT_RC=%errorlevel%"
rem Double-clicked from Explorer: keep the window open so the output can be read (owner, 2026-09-28).
rem Run from a console (cmd /c .\x.bat, as Claude does) it exits at once; ZBOT_NO_PAUSE=1 also skips it.
if not defined ZBOT_NO_PAUSE echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "%~f0" >nul && pause
exit /b %ZBOT_RC%
