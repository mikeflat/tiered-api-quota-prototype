@echo off
rem Start only the gateway service (other services are left as they are); returns once it is up.
rem Does not rebuild an existing image; extra arguments go to compose (e.g. --build).
setlocal
call "%~dp0lib\compose.cmd" up -d --no-deps --wait --wait-timeout 180 %* gateway
exit /b %ERRORLEVEL%
