@echo off
REM ---------------------------------------------------------------------------
REM 一起听中继 · Windows 服务器部署脚本
REM
REM 用法：把 server\ 整个目录拷到 Windows 服务器上，双击本文件（或在 cmd 里执行）。
REM
REM 说明：你的服务器如果是 Linux（宝塔面板那种），请用 deploy.sh，不要用这个。
REM 这个 .bat 只给 Windows 服务器用——.bat 在 Linux 上不能运行。
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "PORT=8090"
set "APP_NAME=together-relay"

echo ==============================================
echo  一起听中继部署 (Windows)
echo  目录: %CD%
echo ==============================================
echo.

REM ---------------------------------------------------------------- node
where node >nul 2>&1
if errorlevel 1 (
  echo [X] 没有找到 node。请先安装 Node.js 18+：https://nodejs.org/
  pause
  exit /b 1
)
for /f "delims=" %%v in ('node -v') do set "NODEVER=%%v"
echo [OK] node !NODEVER!

if not exist "together-relay.js" (
  echo [X] 当前目录没有 together-relay.js，请把整个 server 目录拷过来再执行。
  pause
  exit /b 1
)

REM ---------------------------------------------------------------- token
if not exist "together.env" (
  REM Generate a 32-hex-char token using node itself (avoids relying on PowerShell).
  for /f "delims=" %%t in ('node -e "process.stdout.write(require('crypto').randomBytes(16).toString('hex'))"') do set "TOKEN=%%t"
  > "together.env" echo # 一起听中继配置。含访问令牌，不要提交或外传。
  >> "together.env" echo PORT=%PORT%
  >> "together.env" echo RELAY_TOKEN=!TOKEN!
  echo [OK] 已生成访问令牌 -^> together.env
) else (
  for /f "tokens=2 delims==" %%t in ('findstr /b "RELAY_TOKEN=" together.env') do set "TOKEN=%%t"
  echo [OK] 沿用已有的令牌 -^> together.env
)

REM ---------------------------------------------------------------- start
REM Stop any previous instance so re-running is safe.
taskkill /f /im node.exe >nul 2>&1
timeout /t 1 /nobreak >nul

set "PORT=%PORT%"
set "RELAY_TOKEN=!TOKEN!"
start "together-relay" /b node together-relay.js > relay.log 2>&1
timeout /t 2 /nobreak >nul
echo [OK] 已启动（日志：relay.log）

REM ---------------------------------------------------------------- verify
echo.
echo ---------------------------------------------- 自检
curl -s -m 5 http://127.0.0.1:%PORT%/health > "%TEMP%\relay_health.txt" 2>nul
set /p HEALTH=<"%TEMP%\relay_health.txt"
if "!HEALTH!"=="" (
  echo [X] 本机连不上，看 relay.log
) else (
  echo [OK] 本机健康检查: !HEALTH!
)

echo.
echo ==============================================
echo  部署完成
echo ==============================================
echo.
echo 接下来两件事：
echo.
echo 1^) 在防火墙 / 安全组放行 TCP %PORT% 端口
echo.
echo 2^) 在你开发电脑的 local.properties 里加这两行，然后重新构建 App：
echo.
echo      together.base.url=http://你的服务器IP:%PORT%
echo      together.token=!TOKEN!
echo.
echo 提醒：令牌等于进入中继的钥匙，只填进 local.properties（已被 .gitignore 忽略）。
echo.
pause
