@echo off
chcp 65001 >nul
title 构建《大秦打盗匪助手》APK
echo ============================================
echo   构建《大秦打盗匪助手》安装包 (APK)
echo ============================================
echo.
echo 本脚本使用本机已安装的 Gradle 构建。
echo 若本机未安装 Gradle，请改用 Android Studio 打开本目录后
echo 点击菜单 Build -^> Build APK(s) 直接出包。
echo.
cd /d "%~dp0"
call gradle assembleDebug
if errorlevel 1 (
  echo.
  echo [失败] 构建出错，请确认本机已安装 Gradle 并加入 PATH，
  echo        或改用 Android Studio 打开本目录出包。
  pause
  exit /b 1
)
echo.
echo ============================================
echo   构建成功！APK 位于：
echo   app\build\outputs\apk\debug\app-debug.apk
echo ============================================
echo   把该文件传到手机，允许“未知来源”安装即可。
echo.
pause
