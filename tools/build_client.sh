#!/bin/bash
# 把客户端 SDK 打成 jar，供其他 App 直接引用。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-C:/Users/xfree/AppData/Local/Android/Sdk}"
AJ="$SDK/platforms/android-33/android.jar"
JAVAC="$(command -v javac)"
JAR="$(command -v jar)"
OUT="$ROOT/client/out"

rm -rf "$OUT" && mkdir -p "$OUT/classes"

cd "$ROOT/client"
find src -name '*.java' > "$OUT/sources.txt"
"$JAVAC" --release 8 -nowarn -encoding UTF-8 -classpath "$AJ" \
    -d "$OUT/classes" @"$OUT/sources.txt"

"$JAR" cf "$OUT/xrobot-client.jar" -C "$OUT/classes" .
echo "built: $OUT/xrobot-client.jar"
ls -lh "$OUT/xrobot-client.jar"
