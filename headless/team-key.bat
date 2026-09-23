@echo off
rem Thin wrapper - all logic is in bots.ps1 (Windows PowerShell, no Python needed). macOS/Linux: the .sh files.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" team-key %*
