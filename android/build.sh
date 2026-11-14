#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Buddy Switch Agent — 纯 build-tools 手工构建（不依赖 Gradle / AndroidX / 网络依赖）
#
# 用法：  ./build.sh [输出路径]
# 环境：  ANDROID_SDK_ROOT 或 ANDROID_HOME（默认 /opt/android-sdk）
#         SIGNING_STOREPASS / SIGNING_KEYPASS（可选，默认 buddyswitch）
# 前置：  platforms;android-34 + build-tools;34.0.0 + JDK 8+
# 产物：  dist/agent.apk（已 zipalign + apksigner 签名）
#
# ⚠️ 签名与版本治理（「覆盖安装」而非「卸载重装」的必要条件）：
#   · 签名密钥固定在 signing/release.keystore，绝不放进 build/（build/ 每次清空）。
#   · signing/FINGERPRINT.txt 是证书 SHA-256 锚点，构建后强制校验，漂移即失败。
#   · 版本号唯一数据源为 AndroidManifest.xml，本脚本读取它，不再硬编码。
#   · 若 dist/agent.apk 已存在，要求新 versionCode 严格大于旧值（依次升号）。
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

# ---- 版本号：唯一数据源 AndroidManifest.xml（避免两处漏改） ----
VC=$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' AndroidManifest.xml | head -1)
VN=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
[ -n "$VC" ] && [ -n "$VN" ] || { echo "❌ 无法从 AndroidManifest.xml 读取 versionCode / versionName"; exit 1; }
echo "版本：$VN (versionCode $VC)"

# ---- 依次升号校验：已有产物时 versionCode 必须严格递增 ----
# 同一版本内只改资源 / SPA 重新构建时用 ALLOW_REBUILD=1 ./build.sh 跳过此检查，
# 但签名指纹锚点校验仍强制执行（发新版本时不要开）。
if [ -f "$OUT" ] && [ "${ALLOW_REBUILD:-0}" != "1" ]; then
  OLD_VC=$("$BT/aapt2" dump badging "$OUT" 2>/dev/null \
    | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1 || true)
  if [ -n "$OLD_VC" ] && [ "$VC" -le "$OLD_VC" ]; then
    echo "❌ versionCode 必须依次递增：已发布 $OLD_VC → 本次 $VC"
    echo "   请先修改 AndroidManifest.xml 的 android:versionCode / android:versionName"
    exit 1
  fi
  [ -n "$OLD_VC" ] && echo "版本递增校验通过：$OLD_VC → $VC"
fi

# ---- 签名密钥：固定在 signing/，绝不随 build/ 清理 ----
SIGN_DIR=signing
KS="$SIGN_DIR/release.keystore"
FP="$SIGN_DIR/FINGERPRINT.txt"
KS_ALIAS=buddyswitch
KS_PASS="${SIGNING_STOREPASS:-buddyswitch}"
KEY_PASS="${SIGNING_KEYPASS:-buddyswitch}"
mkdir -p "$SIGN_DIR"
if [ ! -f "$KS" ]; then
  echo
  echo "⚠️  ⚠️  ⚠️  未找到固定签名密钥 $KS"
  echo "    将生成一对新密钥，但由此签出的 APK 与已发布版本【签名不同】，"
  echo "    安装时会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，必须先卸载旧版！"
  echo "    换机 / 重新 clone 后构建，请务必把整个 signing/ 目录带过来。"
  echo
  keytool -genkeypair -noprompt \
    -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" \
    -dname "CN=Buddy Switch Agent, OU=Mobile, O=BuddySwitch, C=CN" >/dev/null 2>&1
fi

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
  --version-code "$VC" --version-name "$VN" \
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
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT" | head -12

# ---- 签名指纹锚点校验：密钥漂移立即失败，杜绝「必须卸载才能装」 ----
ACTUAL=$("$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null \
  | grep 'SHA-256 digest:' | head -1 | sed 's/.*: //' | tr -d '[:space:]' | tr 'A-Z' 'a-z')
if [ -f "$FP" ]; then
  EXPECT=$(head -1 "$FP" | tr -d '[:space:]' | tr 'A-Z' 'a-z')
  if [ -z "$ACTUAL" ] || [ "$ACTUAL" != "$EXPECT" ]; then
    if [ "${RESET_FINGERPRINT:-0}" = "1" ]; then
      echo "⚠️  RESET_FINGERPRINT=1：接受新签名并改写锚点"
      echo "    ⚠️  此后签出的 APK 与历史版本签名不同，已安装用户必须先卸载旧版！"
      echo "    ⚠️  仅在「明确要放弃覆盖升级」时使用，日常构建请勿开启。"
      printf '%s\n' "$ACTUAL" > "$FP"
      echo "新锚点：$ACTUAL"
    else
      echo "❌ 签名指纹漂移！"
      echo "   期望 $EXPECT"
      echo "   实际 $ACTUAL"
      echo "   该 APK 与已发布版本签名不同，覆盖安装会失败。"
      echo "   请恢复 signing/release.keystore（换机构建必须带上整个 signing/ 目录）；"
      echo "   确需更换密钥时，显式执行：RESET_FINGERPRINT=1 ./build.sh"
      rm -f "$OUT"   # 删除签名不一致的产物，避免被误当作可交付版本
      exit 1
    fi
  else
    echo "✅ 签名指纹一致：$ACTUAL（可直接覆盖安装）"
  fi
else
  printf '%s\n' "$ACTUAL" > "$FP"
  echo "✅ 已写入签名指纹锚点：$ACTUAL"
fi

echo
echo "=== 构建完成 ==="
ls -lh "$OUT"
"$BT/aapt2" dump badging "$OUT" 2>/dev/null | head -1 || true
rm -f "$OUT.idsig"   # 清理 v4 签名副产物，避免与交付物混淆
