@echo off
REM Subscribe over MQTT-over-WebSocket to a room and print what arrives — the same path the app uses.
set CODE=%1
set SECS=%2
cd /d "%~dp0"
node read_wss.js %CODE% %SECS%
