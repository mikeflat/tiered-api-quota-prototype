@echo off
rem Stop only the redis service; the container is kept so start-redis resumes it quickly.
rem Extra arguments go to compose (e.g. --timeout 30).
setlocal
call "%~dp0lib\compose.cmd" stop %* redis
exit /b %ERRORLEVEL%
