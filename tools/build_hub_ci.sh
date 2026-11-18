#!/usr/bin/env bash
# 在 GitHub Actions（Ubuntu + Android SDK）上构建 XRobot 中枢 APK。
# 与 tools/build_hub.sh 逻辑一致，但使用 Linux 下无扩展名的 build-tools 可执行文件。
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then echo "缺少 ANDROID_HOME"; exit 1; fi
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-33/android.jar"
[ -x "$BT/aapt2" ] || BT="$SDK/build-tools/36.1.0"
[ -f "$AJ" ] || AJ="$SDK/platforms/android-34/android.jar"
[ -f "$AJ" ] || AJ="$SDK/platforms/android-35/android.jar"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HUB="$ROOT/hub"
KEYS="$ROOT/keys"
cd "$HUB"
rm -rf out classes
mkdir -p out classes

echo "[1/5] link manifest (aapt2)"
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 24 --target-sdk-version 26

echo "[2/5] compile java (javac --release 8)"
find src -name '*.java' > out/sources.txt
javac --release 8 -nowarn -encoding UTF-8 -classpath "$AJ" -d classes @out/sources.txt

echo "[3/5] dex (d8)"
find classes -name '*.class' -print0 | xargs -0 "$BT/d8" --min-api 24 --lib "$AJ" --output out

echo "[4/5] package + zipalign"
python3 - <<'PY'
import zipfile, shutil
shutil.copy('out/base.apk', 'out/unsigned.apk')
with zipfile.ZipFile('out/unsigned.apk', 'a', zipfile.ZIP_STORED) as z:
    z.write('out/classes.dex', 'classes.dex')
PY
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

echo "[5/5] sign (platform key required for ROBOT permission)"
if [ -f "$KEYS/platform.pk8" ] && [ -f "$KEYS/platform.x509.pem" ]; then
  "$BT/apksigner" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
      --out out/XRobotHub.apk out/aligned.apk
  echo "   signed with PLATFORM key"
else
  echo "   !! 缺平台签名密钥，产出 debug 包（无法控制底盘）"
  keytool -genkeypair -keystore /tmp/debug.jks -storepass android -kepass android \
      -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -validity 3650 >/dev/null 2>&1
  "$BT/apksigner" sign --ks /tmp/debug.jks --ks-pass pass:android \
      --key-pass pass:android --out out/XRobotHub.apk out/aligned.apk
fi

"$BT/apksigner" verify --print-certs out/XRobotHub.apk | head -8
ls -lh out/XRobotHub.apk
