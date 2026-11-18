#!/bin/bash
# 部署中枢到已 root 的小方头。用法：
#   ./deploy.sh [设备序列号]
# 前提：设备已 root（见超级工具箱）、USB 调试已开、平台签名 APK 已构建。
set -e
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$ROOT/../RootTool/tools/adb.exe"
[ -f "$ADB" ] || ADB="$(command -v adb)"
DEV="$1"
if [ -z "$DEV" ]; then
  DEV=$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')
fi
[ -z "$DEV" ] && { echo "没有设备"; exit 1; }
A="$ADB -s $DEV"
APK="$ROOT/hub/out/XRobotHub.apk"
[ -f "$APK" ] || { echo "先跑 tools/build_hub.sh 构建 APK"; exit 1; }

echo "设备: $DEV"

echo "[1/3] 提取并部署 UBTECH SDK dex（不随仓库分发）"
if [ -z "$("$ADB" -s "$DEV" shell 'ls /data/local/tmp/visbot1.dex 2>/dev/null')" ]; then
  echo "  设备上没有 visbot1.dex，先从 ROM 反优化生成（见 docs/部署说明.md）"
else
  echo "  visbot1.dex 已就位"
fi
"$A" shell chmod 666 /data/local/tmp/visbot1.dex 2>/dev/null || true

echo "[2/3] 安装中枢（覆盖旧版，保留包名 com.visbot.robotctl）"
"$A" install -r "$APK"

echo "[3/3] 启动中枢进程（Application 里初始化 SDK + 注册驱动）"
"$A" shell am start -n com.visbot.robotctl/com.xrobot.hub.DebugActivity >/dev/null 2>&1 || true
sleep 3

echo "=== 自检 ==="
"$A" shell content call --uri content://com.xrobot.hub.api --method ping
"$A" shell content call --uri content://com.xrobot.hub.api --method capabilities
