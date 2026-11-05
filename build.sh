#!/usr/bin/env bash
# 编译 手机手柄.apk。不用 Gradle：aapt2 -> javac -> d8 -> zipalign -> apksigner。
# 在 Git Bash 里运行：bash build.sh        只跑电脑上的协议测试：bash build.sh test
# 工具都用相对路径调用（项目所在路径有中文，aapt2 在 Windows 上对非 ASCII 路径不可靠）。
set -euo pipefail
cd "$(dirname "$0")"

JDK=toolchain/jdk/bin
BT=toolchain/sdk/build-tools/35.0.1
AJAR=toolchain/sdk/platforms/android-35/android.jar
OUT=build
VERSION_CODE=1
VERSION_NAME=1.0

for f in "$JDK/javac.exe" "$BT/aapt2.exe" "$BT/lib/d8.jar" "$AJAR"; do
  [ -e "$f" ] || { echo "缺少 $f，先运行 bash setup-toolchain.sh 下载工具链"; exit 1; }
done

echo "== 协议测试（电脑上模拟 usbip 客户端）"
rm -rf "$OUT/test" && mkdir -p "$OUT/test"
"$JDK/javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$OUT/test" \
  app/src/com/phonepad/ds/core/*.java test/ProtocolTest.java test/PcHarness.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp "$OUT/test" ProtocolTest | tail -n 1
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "协议测试没通过，停止打包"; exit 1; }
[ "${1:-}" = "test" ] && exit 0

echo "== 资源"
rm -rf "$OUT/apk" && mkdir -p "$OUT/apk/gen" "$OUT/apk/classes" "$OUT/apk/dex"
"$BT/aapt2.exe" compile --dir app/res -o "$OUT/apk/res.zip"
"$BT/aapt2.exe" link -o "$OUT/apk/base.apk" -I "$AJAR" --manifest app/AndroidManifest.xml \
  --java "$OUT/apk/gen" --min-sdk-version 26 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/apk/res.zip"

echo "== 编译 Java"
find app/src "$OUT/apk/gen" -name '*.java' > "$OUT/apk/sources.txt"
"$JDK/javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -Xlint:unchecked \
  -bootclasspath "$AJAR" -d "$OUT/apk/classes" @"$OUT/apk/sources.txt"
"$JDK/jar.exe" cf "$OUT/apk/classes.jar" -C "$OUT/apk/classes" .

echo "== 转 dex"
"$JDK/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJAR" --output "$OUT/apk/dex" "$OUT/apk/classes.jar"

echo "== 打包、对齐、签名"
cp "$OUT/apk/base.apk" "$OUT/apk/unaligned.apk"
( cd "$OUT/apk/dex" && "../../../$BT/aapt.exe" add ../unaligned.apk classes.dex > /dev/null )
"$BT/zipalign.exe" -f -p 4 "$OUT/apk/unaligned.apk" "$OUT/apk/aligned.apk"
mkdir -p keystore
if [ ! -f keystore/phonepad.jks ]; then
  "$JDK/keytool.exe" -genkeypair -keystore keystore/phonepad.jks -storepass phonepad -keypass phonepad \
    -alias phonepad -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PhonePad, O=PhonePad, C=CN" > /dev/null 2>&1
fi
"$JDK/java.exe" -jar "$BT/lib/apksigner.jar" sign --ks keystore/phonepad.jks --ks-pass pass:phonepad \
  --key-pass pass:phonepad --ks-key-alias phonepad --out "$OUT/apk/app.apk" "$OUT/apk/aligned.apk"
"$JDK/java.exe" -jar "$BT/lib/apksigner.jar" verify "$OUT/apk/app.apk"
cp "$OUT/apk/app.apk" "手机手柄.apk"
echo "完成：手机手柄.apk（$(stat -c %s 手机手柄.apk) 字节）"
