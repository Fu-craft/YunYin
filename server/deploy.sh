#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 一起听中继 · 一键部署脚本（Linux / 宝塔面板环境）
#
# 用法（把 server/ 整个目录拷到服务器后）：
#     cd server
#     chmod +x deploy.sh
#     ./deploy.sh
#
# 它会：
#   1. 检查 node（没有就告诉你装什么）
#   2. 生成一个随机访问令牌，写进 together.env（权限 600）
#   3. 用 pm2 守护进程启动（有 pm2 就用，没有就退回 nohup）
#   4. 打印：健康检查命令 + 要填进 App 的两行配置
#
# 重复执行是安全的：已在运行会先停掉再起。
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-8090}"
APP_NAME="together-relay"

echo "=============================================="
echo " 一起听中继部署"
echo " 目录: $HERE"
echo " 端口: $PORT"
echo "=============================================="

# ---------------------------------------------------------------- 1. node
if ! command -v node >/dev/null 2>&1; then
  echo
  echo "✗ 没有找到 node。请先安装 Node.js 18 或更高版本："
  echo "    宝塔面板：软件商店 → 搜索 Node.js 版本管理器 → 安装"
  echo "    或命令行：curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && apt install -y nodejs"
  exit 1
fi
echo "✓ node $(node -v)"

# We must be next to together-relay.js.
if [ ! -f "$HERE/together-relay.js" ]; then
  echo "✗ 当前目录没有 together-relay.js，请把整个 server/ 目录拷过来再执行。"
  exit 1
fi

# ---------------------------------------------------------------- 2. token
ENV_FILE="$HERE/together.env"
if [ ! -f "$ENV_FILE" ]; then
  # 32 hex chars, generated locally. Keep it: the App must be given the same value.
  TOKEN="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  {
    echo "# 一起听中继配置。这个文件含访问令牌，不要提交到任何仓库，也不要贴给别人。"
    echo "PORT=$PORT"
    echo "RELAY_TOKEN=$TOKEN"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "✓ 已生成访问令牌 -> $ENV_FILE"
else
  TOKEN="$(grep -E '^RELAY_TOKEN=' "$ENV_FILE" | cut -d= -f2-)"
  echo "✓ 沿用已有的令牌 -> $ENV_FILE"
fi

# ---------------------------------------------------------------- 3. start
STARTED_WITH=""
if command -v pm2 >/dev/null 2>&1; then
  # pm2 keeps it alive across crashes and reboots (if `pm2 startup` was run once).
  pm2 delete "$APP_NAME" >/dev/null 2>&1
  PORT="$PORT" RELAY_TOKEN="$TOKEN" pm2 start "$HERE/together-relay.js" \
      --name "$APP_NAME" --update-env >/dev/null 2>&1
  pm2 save >/dev/null 2>&1
  STARTED_WITH="pm2"
  echo "✓ 已用 pm2 启动（名称 $APP_NAME）"
else
  # No pm2: plain background process. It will NOT survive a reboot — start it from /etc/rc.local
  # or install pm2 if you want that.
  pkill -f "together-relay.js" >/dev/null 2>&1
  sleep 1
  PORT="$PORT" RELAY_TOKEN="$TOKEN" nohup node "$HERE/together-relay.js" \
      > "$HERE/relay.log" 2>&1 &
  STARTED_WITH="nohup"
  echo "✓ 已用 nohup 启动（日志：$HERE/relay.log）"
  echo "  注意：这种方式重启服务器后不会自动拉起。装上 pm2 可解决：npm i -g pm2"
fi

# ---------------------------------------------------------------- 4. verify
sleep 1
echo
echo "---------------------------------------------- 自检"
if command -v curl >/dev/null 2>&1; then
  HEALTH="$(curl -s -m 5 "http://127.0.0.1:$PORT/health" || true)"
  if [ -n "$HEALTH" ]; then
    echo "✓ 本机健康检查: $HEALTH"
  else
    echo "✗ 本机连不上，看日志：$HERE/relay.log（pm2 则是 pm2 logs $APP_NAME）"
  fi
fi

# The public address: whatever the user's API server IP is. Taken from the machine itself.
IP="$(curl -s -m 5 https://api.ipify.org 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
echo
echo "=============================================="
echo " 部署完成（$STARTED_WITH）"
echo "=============================================="
echo
echo "接下来两件事："
echo
echo "1) 放行端口 $PORT（宝塔：安全 → 添加端口规则；云服务器还要在安全组放行）"
echo
echo "2) 在你自己电脑的 local.properties 里加这两行，然后重新构建 App："
echo
echo "     together.base.url=http://${IP:-你的服务器IP}:$PORT"
echo "     together.token=$TOKEN"
echo
echo "   改完后重新构建：  gradlew :app:assembleDebug"
echo
echo "提醒：令牌等于进入中继的钥匙，只填进 local.properties（它已被 .gitignore 忽略）。"
