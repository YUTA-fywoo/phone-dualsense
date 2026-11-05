@echo off
chcp 65001 >nul
setlocal EnableExtensions
title 手机手柄 - 连接
cd /d "%~dp0"
set "APK=%~dp0..\手机手柄.apk"
set "PKG=com.phonepad.ds"
set "LOCALPORT=32400"

rem ---- 找 usbip.exe ----
set "USBIP=%ProgramFiles%\USBip\usbip.exe"
if exist "%USBIP%" goto have_usbip
set "USBIP="
for /f "delims=" %%P in ('where usbip.exe 2^>nul') do if not defined USBIP set "USBIP=%%P"
if defined USBIP goto have_usbip
echo 没找到 usbip.exe。先安装 usbip-win2（只需装一次）：
echo   https://github.com/vadimgrn/usbip-win2/releases  下载 USBip-版本号-x64.exe 安装
goto fail
:have_usbip

rem ---- 找 adb.exe：先看 电脑端\adb，再看 PATH，都没有就问要不要从 Google 下载 ----
set "ADB=%~dp0adb\adb.exe"
if exist "%ADB%" goto have_adb
set "ADB="
for /f "delims=" %%P in ('where adb.exe 2^>nul') do if not defined ADB set "ADB=%%P"
if defined ADB goto have_adb
echo 没找到 adb。可以从 Google 官网下载 platform-tools（约 8MB），放到 电脑端\adb 里。
choice /c YN /m "现在下载吗"
if errorlevel 2 goto fail
set "PTZIP=%TEMP%\platform-tools-latest-windows.zip"
set "PTDIR=%TEMP%\phonepad-platform-tools"
curl.exe -fL -o "%PTZIP%" https://dl.google.com/android/repository/platform-tools-latest-windows.zip
if errorlevel 1 goto adb_failed
if exist "%PTDIR%" rmdir /s /q "%PTDIR%"
powershell -NoProfile -Command "Expand-Archive -Force -LiteralPath $env:PTZIP -DestinationPath $env:PTDIR"
if errorlevel 1 goto adb_failed
if not exist "%~dp0adb" mkdir "%~dp0adb"
copy /y "%PTDIR%\platform-tools\adb.exe" "%~dp0adb\" >nul
copy /y "%PTDIR%\platform-tools\AdbWinApi.dll" "%~dp0adb\" >nul
copy /y "%PTDIR%\platform-tools\AdbWinUsbApi.dll" "%~dp0adb\" >nul
rmdir /s /q "%PTDIR%"
del /q "%PTZIP%"
set "ADB=%~dp0adb\adb.exe"
if not exist "%ADB%" goto adb_failed
:have_adb

echo [1/4] 等手机插上 USB 线……
echo       手机要打开“USB 调试”；第一次连这台电脑时，手机上会弹窗，点“允许”。
"%ADB%" start-server >nul 2>&1
"%ADB%" -d wait-for-device
if errorlevel 1 goto fail

"%ADB%" -d shell pm path %PKG% 2>nul | findstr /b "package:" >nul
if not errorlevel 1 goto installed
echo [2/4] 手机上还没装“手机手柄”，现在安装……
echo       小米/红米手机会弹出“是否允许通过 USB 安装”，在倒计时结束前点“继续安装”。
if not exist "%APK%" (
  echo       找不到 %APK%
  goto fail
)
"%ADB%" -d install -r "%APK%"
if errorlevel 1 goto install_failed
:installed
echo [2/4] 打开手机上的“手机手柄”
"%ADB%" -d shell am start -n %PKG%/.MainActivity >nul 2>&1
ping -n 3 127.0.0.1 >nul

echo [3/4] 建立 USB 转发（电脑 %LOCALPORT% 端口 → 手机 3240 端口）
"%ADB%" -d forward tcp:%LOCALPORT% tcp:3240
if errorlevel 1 goto fail

echo [4/4] 接入虚拟 PS5 手柄
set /a TRY=0
:attach
"%USBIP%" --tcp-port %LOCALPORT% attach -r 127.0.0.1 -b 1-1
if not errorlevel 1 goto ok
set /a TRY+=1
if %TRY% GEQ 5 goto fail
echo       手机端可能还没准备好，2 秒后重试（%TRY%/5）……
ping -n 3 127.0.0.1 >nul
goto attach

:ok
echo.
echo 已接入。电脑上会出现“DualSense Wireless Controller”，Steam 里显示为 PS5 手柄。
echo 用完直接关掉手机 App，或者运行“断开手柄.bat”。
timeout /t 8
exit /b 0

:adb_failed
echo       adb 下载或解压失败。也可以自己从 https://developer.android.com/tools/releases/platform-tools 下载，
echo       把 adb.exe、AdbWinApi.dll、AdbWinUsbApi.dll 放进 电脑端\adb 文件夹。
goto fail

:install_failed
echo       安装失败。小米/红米手机要在开发者选项里打开“USB 安装”，并在弹窗里点“继续安装”；
echo       也可以把 手机手柄.apk 拷到手机上手动安装。
goto fail

:fail
echo.
echo 没有接上。看上面的提示，或者看 README.md 里的“常见问题”。
pause
exit /b 1
