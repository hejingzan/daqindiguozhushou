@echo off
chcp 65001 >nul
title Push to GitHub - DaQin Bandit Helper
echo ============================================
echo   Push project to GitHub (then auto-build APK)
echo ============================================
echo.
echo Prerequisite: Git for Windows must be installed.
echo   Download: https://git-scm.com  (or use GitHub Desktop, see guide)
echo.

where git >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Git not found. Install Git for Windows first,
  echo   or use GitHub Desktop (see GitHub_Desktop指南.md).
  pause
  exit /b 1
)

if not exist .git (
  echo This folder is not a git repo. Initializing...
  git init
  git add .
  git commit -m "DaQin Bandit Helper project"
)

git remote remove origin >nul 2>&1

set /p REPO_URL=Please paste your GitHub repo HTTPS URL:
if "%REPO_URL%"=="" (
  echo No URL entered. Cancelled.
  pause
  exit /b 1
)

git remote add origin %REPO_URL%
git branch -M main

echo.
echo Pushing to GitHub... (login prompt: user=GitHub account, password=Personal Access Token)
echo.
git push -u origin main
if errorlevel 1 (
  echo.
  echo [FAILED] Common reasons:
  echo   1) Wrong repo URL;
  echo   2) Not logged in, or Token lacks 'repo' scope;
  echo   3) Repo is not empty (delete and recreate as empty repo).
  echo.
  echo Get Token: GitHub avatar - Settings - Developer settings -
  echo   Personal access tokens - check 'repo' - Generate.
  pause
  exit /b 1
)

echo.
echo ============================================
echo   PUSH OK! Next on GitHub website:
echo   1) Open repo - Actions tab;
echo   2) "Build APK" - Run workflow - Run;
echo   3) Wait 2-5 min, download daqin-bandit-helper-apk.zip from Artifacts;
echo   4) Unzip to get app-debug.apk, install on phone.
echo ============================================
pause
