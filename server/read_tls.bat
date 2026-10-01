@echo off
REM Read a room over MQTT with TLS (8883), matching host_tls.bat.
set MQTT_TLS=1
set MQTT_PORT=8883
cd /d "%~dp0"
node read_room.js %1 %2 --mqtt
