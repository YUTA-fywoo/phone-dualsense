@echo off
chcp 65001 >nul
setlocal EnableExtensions
title 手机手柄 - 断开
cd /d "%~dp0"
set "USBIP=%ProgramFiles%\USBip\usbip.exe"
if exist "%USBIP%" goto have_usbip
set "USBIP="
for /f "delims=" %%P in ('where usbip.exe 2^>nul') do if not defined USBIP set "USBIP=%%P"
if not defined USBIP (
  echo 没找到 usbip.exe
  pause
  exit /b 1
)
:have_usbip
"%USBIP%" detach --all

set "ADB=%~dp0adb\adb.exe"
if exist "%ADB%" goto have_adb
set "ADB="
for /f "delims=" %%P in ('where adb.exe 2^>nul') do if not defined ADB set "ADB=%%P"
if not defined ADB goto done
:have_adb
"%ADB%" forward --remove tcp:32400 >nul 2>&1
:done
echo 已断开虚拟手柄。
"%SystemRoot%\System32\timeout.exe" /t 3
