@echo off
rem A command prompt already in this headless folder, to type the rig commands (send.bat bot1 /zbot status, ...).
rem Uses its own location, so it still works if the project folder is moved or renamed.
cd /d "%~dp0"
cmd /k "echo In %~dp0 - try: stop-bots-check.bat   send.bat bot1 /zbot status   console.bat"
