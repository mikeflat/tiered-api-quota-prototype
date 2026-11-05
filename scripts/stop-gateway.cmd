@echo off
rem Stop only the gateway service; the container is kept so start-gateway resumes it quickly.
rem Extra arguments go to compose (e.g. --timeout 30).
setlocal
call "%~dp0lib\compose.cmd" stop %* gateway
exit /b %ERRORLEVEL%
