@echo off
rem Stop and remove the whole stack (containers and network); built images are kept.
setlocal
call "%~dp0lib\compose.cmd" down --remove-orphans
exit /b %ERRORLEVEL%
