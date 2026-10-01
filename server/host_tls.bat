@echo off
REM Run the room host over MQTT with TLS (8883). A file rather than inline env vars because the shell
REM mangles quotes and `set` in a compound command.
set MQTT_TLS=1
set MQTT_PORT=8883
cd /d "%~dp0"
node host_room.js %1 %2 %3 %4 --mqtt
