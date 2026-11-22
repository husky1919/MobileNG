#!/data/data/com.termux/files/usr/bin/bash
# 纯 Termux 构建 NeoGeo 模拟器 APK（无 Gradle）
# 依赖: pkg install aapt2 d8 apksigner openjdk-17 clang zip unzip
set -e
cd "$(dirname "$0")"

TARGET=${1:-release}
if [ "$TARGET" = "debug" ]; then
    MF=app/AndroidManifest-debug.xml
    APKNAME=MobileNG-debug.apk
else
    MF=app/AndroidManifest.xml
    APKNAME=MobileNG.apk
fi
echo "target=$TARGET manifest=$MF -> $APKNAME"
ROOT=$(pwd)
JAR=${ANDROID_JAR:-$HOME/dsapp/fw/android.jar}
[ -f "$JAR" ] || { echo "缺少 android.jar，设 ANDROID_JAR 指向它"; exit 1; }

rm -rf build/apk
mkdir -p build/classes build/dex build/apk/lib/arm64-v8a build/apk/assets

echo "[1/6] 编译 JNI"
clang++ -shared -fPIC -O2 -std=c++17 -I"$PREFIX/include" \
    -o build/libngfe.so jni/frontend.cpp -ldl
cp "$PREFIX/lib/libc++_shared.so" build/apk/lib/arm64-v8a/
cp build/libngfe.so build/apk/lib/arm64-v8a/
readelf -d build/libngfe.so | grep NEEDED

echo "[2/6] 准备核心"
CORE=build/core/fbneo_libretro_android.so
if [ -n "${BUNDLE_CORE:-}" ] && [ ! -f "$CORE" ]; then
    curl -L -o build/fbneo.zip \
      "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/fbneo_libretro_android.so.zip"
    unzip -o -q build/fbneo.zip -d build/core
fi
if [ -n "${BUNDLE_CORE:-}" ]; then
    mkdir -p build/apk/assets/core
    cp "$CORE" build/apk/assets/core/libfbneo.so
    echo "   (已打包核心)"
else
    echo "   不打包核心（运行时导入）。BUNDLE_CORE=1 可打包。"
fi

echo "[3/6] 资源 + 清单"
mkdir -p build/apk/assets
cp licenses/FBNeo.txt build/apk/assets/FBNeo.txt
aapt2 link -o build/base.apk -I "$JAR" --manifest "$MF"

echo "[4/6] 编译 Java"
rm -rf build/classes && mkdir -p build/classes
javac -source 11 -target 11 -classpath "$JAR" -d build/classes src/com/ng/emu/*.java 2>&1 | grep -v "bootstrap class path" || true

echo "[5/6] dex"
rm -rf build/dex && mkdir -p build/dex
d8 --lib "$JAR" --min-api 24 --output build/dex $(find build/classes -name "*.class")

echo "[6/6] 打包签名"
cd build/apk
cp ../base.apk ../unsigned.apk
cd ..
cp dex/classes.dex apk/classes.dex
cd apk
zip -q -X -r ../unsigned.apk classes.dex lib assets
cd ..

if [ ! -f ng.jks ]; then
    keytool -genkeypair -keystore ng.jks -alias ng -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass neogeo -keypass neogeo \
        -dname "CN=NeoEmu, OU=dev, O=ng, L=SZ, S=GD, C=CN"
fi
apksigner sign --ks ng.jks --ks-pass pass:neogeo --key-pass pass:neogeo \
    --ks-key-alias ng --min-sdk-version 24 --out "$APKNAME" unsigned.apk
apksigner verify "$APKNAME"
ls -lh "$APKNAME"
echo "OK: $ROOT/build/$APKNAME"
