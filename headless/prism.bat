@echo off
rem Your game only, straight into a world - no bots. Logic in bots.ps1. macOS/Linux: prism.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bots.ps1" prism %*
