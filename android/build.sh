#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Buddy Switch Agent — 纯 build-tools 手工构建（不依赖 Gradle / AndroidX / 网络依赖）
#
# 用法：  ./build.sh [输出路径]
# 环境：  ANDROID_SDK_ROOT 或 ANDROID_HOME（默认 /opt/android-sdk）
# 前置：  platforms;android-34 + build-tools;34.0.0 + JDK 8+
# 产物：  dist/agent.apk（已 zipalign + apksigner 签名）
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT="${1:-dist/agent.apk}"

for f in "$JAR" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
  [ -e "$f" ] || { echo "缺少 Android SDK 组件：$f"; echo "请执行：sdkmanager 'platforms;android-34' 'build-tools;34.0.0'"; exit 1; }
done

B=build
rm -rf "$B"; mkdir -p "$B/res" "$B/gen" "$B/classes" "$B/dex"
mkdir -p "$(dirname "$OUT")"

echo "[1/7] aapt2 compile  编译资源"
"$BT/aapt2" compile --dir res -o "$B/res/"

echo "[2/7] aapt2 link     生成 base.apk + R.java"
FLATS=$(find "$B/res" -name '*.flat' | sort | tr '\n' ' ')
# shellcheck disable=SC2086
"$BT/aapt2" link -o "$B/base.apk" \
  -I "$JAR" \
  --manifest AndroidManifest.xml \
  --java "$B/gen" \
  -A assets \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0 \
  --auto-add-overlay $FLATS

echo "[3/7] javac          编译 Java 源码"
find src "$B/gen" -name '*.java' > "$B/sources.txt"
if ! javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$JAR" -classpath "$JAR" \
  -d "$B/classes" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
  cat "$B/javac.log"; echo "javac 编译失败"; exit 1
fi
grep -v 'bootstrap class path' "$B/javac.log" || true

echo "[4/7] d8             转换为 classes.dex"
CLASSES=$(find "$B/classes" -name '*.class' | sort | tr '\n' ' ')
# shellcheck disable=SC2086
"$BT/d8" --lib "$JAR" --min-api 24 --output "$B/dex" $CLASSES
[ -f "$B/dex/classes.dex" ] || { echo "d8 未生成 classes.dex"; exit 1; }

echo "[5/7] zip            把 dex 装入 APK"
(cd "$B/dex" && zip -q -u ../base.apk classes.dex)

echo "[6/7] zipalign       4 字节对齐"
"$BT/zipalign" -f 4 "$B/base.apk" "$B/aligned.apk"

echo "[7/7] apksigner      签名并输出 $OUT"
if [ ! -f "$B/debug.keystore" ]; then
  keytool -genkeypair -noprompt \
    -keystore "$B/debug.keystore" -alias buddyswitch \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass buddyswitch -keypass buddyswitch \
    -dname "CN=Buddy Switch Agent, OU=Mobile, O=BuddySwitch, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$B/debug.keystore" --ks-pass pass:buddyswitch --key-pass pass:buddyswitch \
  --ks-key-alias buddyswitch \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT" | head -12

echo
echo "=== 构建完成 ==="
ls -lh "$OUT"
"$BT/aapt" dump badging "$OUT" 2>/dev/null | head -6 || true
