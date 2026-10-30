#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/dist/mi-tv-time-guard.apk"
PKG_NAME="com.wangqd.mitvguard"
ACC_SERVICE="$PKG_NAME/$PKG_NAME.GuardAccessibilityService"
export PATH="/opt/homebrew/bin:$PATH"
export ADB_MDNS=0
export ADB_MDNS_OPENSCREEN=0
export ADB_LIBUSB=0

if [ ! -f "$APK_PATH" ]; then
    echo "==> 未找到编译好的 APK，正在自动编译..."
    "$PROJECT_DIR/build_apk.sh"
fi

TV_IP="${1:-}"
if [ -z "$TV_IP" ]; then
    echo "========================================================"
    echo "  📺 小米电视时长守护 - Mac 一键无线安装与自启激活工具"
    echo "========================================================"
    echo "请确保已在小米电视上开启 ADB 调试："
    echo "  1. 电视【设置】->【关于】-> 连续按 5 次【产品型号】开启开发者模式"
    echo "  2. 电视【设置】->【账号与安全】-> 开启【ADB调试】和【安装未知来源应用】"
    echo "  3. 电视【设置】->【关于】->【网络信息】查看电视的 IP 地址（如 192.168.31.xxx）"
    echo "--------------------------------------------------------"
    read -r -p "请输入小米电视的局域网 IP 地址: " TV_IP
fi

TV_IP="$(echo "$TV_IP" | tr -d '[:space:]')"
if [ -z "$TV_IP" ]; then
    echo "❌ 错误：未输入电视 IP 地址。"
    exit 1
fi

# 保存最近使用的电视 IP，方便下次免输入直接用
echo "$TV_IP" > "$PROJECT_DIR/.last_tv_ip"
TARGET="$TV_IP:5555"

echo ""
echo "==> [1/4] 正在通过 Wi-Fi 连接小米电视 ($TARGET)..."
adb connect "$TARGET"
sleep 1

echo "==> 请看一眼小米电视屏幕：如果弹出【是否允许 USB 调试】，请用遥控器勾选【一律允许】并点【确定】..."
adb -s "$TARGET" wait-for-device

echo "==> [2/4] 正在推送并安装 mi-tv-time-guard.apk..."
adb -s "$TARGET" install -r "$APK_PATH"

echo "==> [3/4] 正在配置小米电视【开机自启】、【全局悬浮窗】与【防杀后台白名单】权限..."
# 1. 授予全局悬浮窗权限
adb -s "$TARGET" shell appops set "$PKG_NAME" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
# 2. 授予小米 MIUI 自启动权限
adb -s "$TARGET" shell appops set "$PKG_NAME" AUTO_START allow 2>/dev/null || true
# 3. 加入系统电池/内存免清理白名单
adb -s "$TARGET" shell dumpsys deviceidle whitelist +"$PKG_NAME" >/dev/null 2>&1 || true

# 4. 激活系统级无障碍守护服务（确保拔电源冷启动 100% 自动拉起）
EXISTING_ACC="$(adb -s "$TARGET" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')"
if [ "$EXISTING_ACC" = "null" ] || [ -z "$EXISTING_ACC" ]; then
    NEW_ACC="$ACC_SERVICE"
elif [[ "$EXISTING_ACC" == *"$ACC_SERVICE"* ]]; then
    NEW_ACC="$EXISTING_ACC"
else
    NEW_ACC="${EXISTING_ACC}:${ACC_SERVICE}"
fi
adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$NEW_ACC"
adb -s "$TARGET" shell settings put secure accessibility_enabled 1

echo "==> [4/4] 正在启动电视端守护服务..."
adb -s "$TARGET" shell am start -n "$PKG_NAME/.MainActivity" >/dev/null 2>&1 || true

WEB_URL="http://$TV_IP:8686"
echo ""
echo "========================================================"
echo "✅ 安装与开机自启配置全部完成！"
echo "💻 电脑端管理后台地址: $WEB_URL"
echo "🗑️ 日后如需移除，随时运行: ./uninstall_from_tv.sh"
echo "========================================================"

# 自动在当前 Mac 默认浏览器打开控制页面
open "$WEB_URL" 2>/dev/null || true
