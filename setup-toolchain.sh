#!/usr/bin/env bash
# 下载编译要用的工具链到 toolchain/：JDK 17（微软 OpenJDK）、Android build-tools 35.0.1、android-35 平台。
# 在 Windows 的 Git Bash 里运行：bash setup-toolchain.sh
# 使用 Android SDK 代表你同意 Android SDK 许可协议：https://developer.android.com/studio/terms
set -euo pipefail
cd "$(dirname "$0")"

TC=toolchain
DL="$TC/dl"
mkdir -p "$DL" "$TC/sdk/build-tools" "$TC/sdk/platforms"

JDK_URL=https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip
BT_URL=https://dl.google.com/android/repository/build-tools_r35.0.1_windows.zip
BT_SHA1=1cbfa5564b62504a1111352edda35914cf723a20
PF_URL=https://dl.google.com/android/repository/platform-35_r02.zip
PF_SHA1=0bb560a90a7a2cbd0dd8348224d518b638fe7949

unzip_to() { # 压缩包 目标目录（用 .NET 解压，Git Bash 一般没有 unzip）
  powershell.exe -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory('$(cygpath -w "$1")', '$(cygpath -w "$2")')"
}

if [ ! -x "$TC/jdk/bin/java.exe" ]; then
  echo "== 下载 JDK 17"
  curl -fSL --retry 3 -o "$DL/jdk17.zip" "$JDK_URL"
  want=$(curl -fsSL "$JDK_URL.sha256sum.txt" | awk '{print $1}')
  got=$(sha256sum "$DL/jdk17.zip" | awk '{print $1}')
  [ "$want" = "$got" ] || { echo "JDK 校验失败"; exit 1; }
  rm -rf "$DL/x" && unzip_to "$DL/jdk17.zip" "$DL/x"
  mv "$DL/x"/jdk-* "$TC/jdk" && rm -rf "$DL/x"
fi

if [ ! -f "$TC/sdk/build-tools/35.0.1/aapt2.exe" ]; then
  echo "== 下载 build-tools 35.0.1"
  curl -fSL --retry 3 -o "$DL/build-tools.zip" "$BT_URL"
  echo "$BT_SHA1  $DL/build-tools.zip" | sha1sum -c -
  rm -rf "$DL/x" && unzip_to "$DL/build-tools.zip" "$DL/x"
  mv "$DL/x"/* "$TC/sdk/build-tools/35.0.1" && rm -rf "$DL/x"
fi

if [ ! -f "$TC/sdk/platforms/android-35/android.jar" ]; then
  echo "== 下载 android-35 平台"
  curl -fSL --retry 3 -o "$DL/platform-35.zip" "$PF_URL"
  echo "$PF_SHA1  $DL/platform-35.zip" | sha1sum -c -
  rm -rf "$DL/x" && unzip_to "$DL/platform-35.zip" "$DL/x"
  mv "$DL/x"/* "$TC/sdk/platforms/android-35" && rm -rf "$DL/x"
fi

rm -rf "$DL"
"$TC/jdk/bin/java.exe" -version 2>&1 | head -1
echo "工具链就绪，接下来运行：bash build.sh"
