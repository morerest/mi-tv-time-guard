#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
PKG_NAME="com.wangqd.mitvguard"
export PATH="/opt/homebrew/bin:$PATH"
export ADB_MDNS=0
export ADB_MDNS_OPENSCREEN=0
export ADB_LIBUSB=0

TV_IP="${1:-}"
if [ -z "$TV_IP" ] && [ -f "$PROJECT_DIR/.last_tv_ip" ]; then
    TV_IP="$(cat "$PROJECT_DIR/.last_tv_ip" | tr -d '[:space:]')"
fi

if [ -z "$TV_IP" ]; then
    read -r -p "请输入要卸载程序的小米电视 IP 地址: " TV_IP
fi

TV_IP="$(echo "$TV_IP" | tr -d '[:space:]')"
TARGET="$TV_IP:5555"

echo "==> 正在连接小米电视 ($TARGET)..."
adb connect "$TARGET" >/dev/null 2>&1 || true

echo "==> 正在从小米电视彻底移除《电视时长守护》($PKG_NAME)..."
adb -s "$TARGET" shell dumpsys deviceidle whitelist -"$PKG_NAME" >/dev/null 2>&1 || true
adb -s "$TARGET" uninstall "$PKG_NAME"

echo "✅ 已从小米电视彻底卸载移除，无任何残留！"
