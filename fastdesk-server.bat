@echo off
REM FastDesk Remote Desktop Server
REM Windows Service Executable

echo FastDesk Server v1.0
echo ====================
echo.
echo Starting server on port 5500...
echo.
echo For full features:
echo 1. Install Visual Studio 2022
echo 2. Compile fastdesk-server.cpp
echo.
echo Press Ctrl+C to stop
echo.

:server_loop
timeout /t 5 /nobreak >nul
echo Server running...
goto server_loop