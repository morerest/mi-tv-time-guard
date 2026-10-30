#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
BUILD_TOOLS="$SDK_ROOT/build-tools/33.0.2"
PLATFORM_JAR="$SDK_ROOT/platforms/android-33/android.jar"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$BUILD_TOOLS:/opt/homebrew/bin:$PATH"

BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
SRC_MAIN="$PROJECT_DIR/app/src/main"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$DIST_DIR"

echo "==> [1/5] 编译 Android 资源文件与前端 Assets (aapt2)..."
mkdir -p "$SRC_MAIN/assets"
cp "$PROJECT_DIR/pc-control-panel.html" "$SRC_MAIN/assets/index.html"
aapt2 compile --dir "$SRC_MAIN/res" -o "$BUILD_DIR/res.zip"
aapt2 link \
    -I "$PLATFORM_JAR" \
    -A "$SRC_MAIN/assets" \
    --manifest "$SRC_MAIN/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/unsigned.unaligned.apk" \
    "$BUILD_DIR/res.zip"

echo "==> [2/5] 编译 Java 源码 (javac)..."
find "$SRC_MAIN/java" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
javac \
    -source 1.8 -target 1.8 \
    -encoding UTF-8 \
    -classpath "$PLATFORM_JAR" \
    -d "$BUILD_DIR/obj" \
    @"$BUILD_DIR/sources.txt"

echo "==> [3/5] 转换为 Dalvik 字节码 (d8)..."
find "$BUILD_DIR/obj" -name "*.class" > "$BUILD_DIR/classes.txt"
d8 \
    --lib "$PLATFORM_JAR" \
    --min-api 21 \
    --output "$BUILD_DIR/dex" \
    @"$BUILD_DIR/classes.txt"

echo "==> [4/5] 打包 classes.dex 并对齐 (zipalign)..."
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unsigned.unaligned.apk" classes.dex)
zipalign -f -p 4 "$BUILD_DIR/unsigned.unaligned.apk" "$BUILD_DIR/unsigned.apk"

echo "==> [5/5] 生成密钥并执行 V1+V2 签名 (apksigner)..."
KEYSTORE="$PROJECT_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias androiddebugkey \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=MiTvGuard,OU=Home,O=WangQD,L=Beijing,ST=Beijing,C=CN" >/dev/null 2>&1
fi

OUTPUT_APK="$DIST_DIR/mi-tv-time-guard.apk"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_APK" \
    "$BUILD_DIR/unsigned.apk"

chmod +x "$0"
echo "✅ 编译完成！APK 路径: $OUTPUT_APK ($(du -h "$OUTPUT_APK" | awk '{print $1}'))"
