@echo off
rem Shared helper for the /scripts/*.cmd scripts: call "%~dp0lib\compose.cmd" <args>
rem
rem - ROOT is the repository root, resolved from this file's location (scripts\lib\),
rem   so scripts work from any working directory.
rem - Checks that Docker, the compose v2 plugin and the engine are available, then runs
rem   docker compose against the repository's docker-compose.yml and returns its exit code.
rem   Same steps and messages as compose.sh.
setlocal
for %%i in ("%~dp0..\..") do set "ROOT=%%~fi"

where docker >nul 2>&1 || goto :no_docker
docker compose version >nul 2>&1 || goto :no_compose
docker info >nul 2>&1 || goto :no_engine

docker compose --project-directory "%ROOT%" -f "%ROOT%\docker-compose.yml" %*
set "STATUS=%ERRORLEVEL%"
if not "%STATUS%"=="0" >&2 echo error: docker compose %1 failed ^(exit %STATUS%^)
exit /b %STATUS%

:no_docker
>&2 echo error: Docker is not available: 'docker' command not found
exit /b 1

:no_compose
>&2 echo error: Docker is not available: 'docker compose' ^(compose v2 plugin^) not found
exit /b 1

:no_engine
>&2 echo error: Docker is not available: the Docker engine is not running
exit /b 1
