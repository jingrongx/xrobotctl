#!/bin/bash
# 构建 XRobot 中枢 APK。平台签名是硬性要求（拿 com.ubtrobot.permission.ROBOT）。
set -e
export MSYS_NO_PATHCONV=1
SDK="${ANDROID_HOME:-C:/Users/xfree/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
BT34="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-33/android.jar"
JAVAC="$(command -v javac)"
HUB="$(cd "$(dirname "$0")/../hub" && pwd -W 2>/dev/null || pwd)"
KEYS="$(cd "$(dirname "$0")/../keys" && pwd -W 2>/dev/null || pwd)"

cd "$HUB"
rm -rf out classes
mkdir -p out classes

echo "[1/5] link manifest"
"$BT/aapt2.exe" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 24 --target-sdk-version 26

echo "[2/5] compile java"
find src -name '*.java' > out/sources.txt
"$JAVAC" --release 8 -nowarn -encoding UTF-8 -classpath "$AJ" -d classes @out/sources.txt

echo "[3/5] dex"
CLASSES=$(find classes -name '*.class' | tr '\n' ' ')
"$BT/d8.bat" --min-api 24 --lib "$AJ" --output out $CLASSES

echo "[4/5] package"
python -c "
import zipfile, shutil
shutil.copy('out/base.apk','out/unsigned.apk')
z=zipfile.ZipFile('out/unsigned.apk','a',zipfile.ZIP_STORED)
z.write('out/classes.dex','classes.dex')
z.close()
"
"$BT34/zipalign.exe" -f 4 out/unsigned.apk out/aligned.apk

echo "[5/5] sign"
if [ -f "$KEYS/platform.pk8" ] && [ -f "$KEYS/platform.x509.pem" ]; then
  "$BT34/apksigner.bat" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
      --out out/XRobotHub.apk out/aligned.apk
  echo "   signed with PLATFORM key (可拿 ROBOT 权限)"
else
  echo "   !! 缺 keys/platform.pk8，用 debug 签名（无法控制底盘）"
  "$BT34/apksigner.bat" sign --ks "$HOME/.android/debug.keystore" \
      --ks-pass pass:android --key-pass pass:android --out out/XRobotHub.apk out/aligned.apk
fi

ls -lh out/XRobotHub.apk
