@echo off
rem Thin wrapper - all logic is in bots.py (same on macOS, Linux and Windows).
set "PY=python"
where py >nul 2>nul && set "PY=py -3"
%PY% -u "%~dp0bots.py" standby %*
