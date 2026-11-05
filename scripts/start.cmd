@echo off
rem Build all images and start the whole stack (Redis, gateway, dashboard, generator).
rem Returns once every service is running (healthy where it has a healthcheck), then prints the URLs.
setlocal
call "%~dp0lib\compose.cmd" up -d --build --wait --wait-timeout 180 --remove-orphans
if errorlevel 1 exit /b %ERRORLEVEL%

rem Effective host ports as published by compose (honours GATEWAY_PORT / DASHBOARD_PORT and .env).
for /f "tokens=2 delims=:" %%p in ('call "%~dp0lib\compose.cmd" port dashboard 8080') do set "DASHBOARD=%%p"
for /f "tokens=2 delims=:" %%p in ('call "%~dp0lib\compose.cmd" port gateway 8080') do set "GATEWAY=%%p"
echo.
echo Stack is up:
echo   dashboard  http://localhost:%DASHBOARD%
echo   gateway    http://localhost:%GATEWAY%
exit /b 0
