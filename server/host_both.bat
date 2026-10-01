@echo off
REM Host the same room on BOTH default brokers at once.
REM
REM Why: the app now walks a candidate list, so the phone may end up on either broker depending on what
REM its network allows. Publishing to only one of them would leave the phone following a room that
REM looks empty. Two publishers, one room code, so the test cannot fail for that reason.
set MQTT_TLS=1
set MQTT_PORT=8883
set CODE=%1
cd /d "%~dp0"

echo === hosting %CODE% on both brokers ===
set MQTT_HOST=broker.hivemq.com
start "together-hivemq" /b node host_room.js %CODE% %2 %3 %4 --mqtt
set MQTT_HOST=broker.emqx.io
start "together-emqx" /b node host_room.js %CODE% %2 %3 %4 --mqtt
echo both publishers started (Ctrl+C or `taskkill /f /im node.exe` to stop)
