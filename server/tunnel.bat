@echo off
REM Expose the local relay to the internet through a free SSH tunnel, so listen-together works
REM ACROSS networks (not just the same WiFi).
REM
REM Why a tunnel: the rendezvous has to be reachable by both devices. A LAN address is not, and the
REM third-party pub/sub options were either blocked on this network (MQTT) or quota-limited (the HTTP
REM service). A tunnel needs no account and no server: it forwards a public HTTPS address to the local
REM relay.
REM
REM The relay runs WITH a token here: the address is public, so anyone who learns it could otherwise
REM create rooms on it.
setlocal
cd /d "%~dp0"
if "%RELAY_TOKEN%"=="" set RELAY_TOKEN=together%RANDOM%%RANDOM%

echo === relay (with token) ===
set RELAY_TOKEN=%RELAY_TOKEN%
start "together-relay" /b node together-relay.js > _relay.log 2>&1
timeout /t 2 /nobreak >nul

echo === tunnel ===
echo (a public https address will be printed below; keep this window open)
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o ServerAliveInterval=30 -R 80:localhost:8090 nokey@localhost.run
