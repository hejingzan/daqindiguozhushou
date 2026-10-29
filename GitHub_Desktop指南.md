# 用 GitHub Desktop 发布工程（无需命令行、无需装 Git）

## 为什么用这个
你双击 `push_to_github.bat` 报错，是因为解压出来的文件夹里**没有 `.git`**（git 仓库信息），脚本一执行 `git` 命令就报"不是 git 仓库"或"git 不是内部或外部命令"。

GitHub Desktop 可以直接把一个**普通文件夹**变成仓库并发布到 GitHub，自动包含 `.github` 隐藏文件夹（自动构建配置全靠它），**完全绕开命令行**，也不要求你本机装 Git。

---

## 步骤

**第 1 步：安装并登录 GitHub Desktop**
- 下载：https://desktop.github.com/
- 打开后用 GitHub 账号登录（没有就先去 github.com 注册一个）。

**第 2 步：把文件夹加进来**
- 菜单 `File` → `Add Local Repository…` → 选你的 `DaQinBanditHelper` 文件夹 → `Open`。
- 如果弹出提示"这不是一个 Git 仓库"，点击 **`Create a Repository`**，确认路径无误，点 **Create Repository**。
- GitHub Desktop 会自动 `git init` 并把所有文件（含 `.github`）加进来，你不用管命令行。

**第 3 步：提交到本地**
- 左下角 `Summary` 填 `大秦打盗匪助手工程`，点 **`Commit to main`**。

**第 4 步：发布到 GitHub**
- 点顶部 **`Publish repository`**（或右上角 Publish）。
- `Repository name` 填 `daqin-helper`（随意）。
- 可以取消勾选 `Keep this code private`（公开不影响使用）。
- 点 **`Publish Repository`**。首次可能要授权，按提示完成即可。

**第 5 步：触发自动构建、下载 APK**
- 在 GitHub 网页打开你的仓库 → 点 **`Actions`** 标签 → 左侧"构建 APK" → **`Run workflow` → Run**。
- 等 2~5 分钟（首次要联网下载 Gradle 与依赖）。
- 完成后在 **`Artifacts`** 下载 `daqin-bandit-helper-apk.zip` → 解压得到 `app-debug.apk` → 传到手机安装。

---

## 重要提醒
- 确保 `DaQinBanditHelper` 文件夹是**完整**的（里面有 `.github` 文件夹）。Windows 默认隐藏以点开头的文件夹，但 GitHub Desktop 会正常处理，不用手动改显示设置。
- 拿到 `app-debug.apk` 后，权限设置与"一键校准 → 开始打盗匪"流程见 `README_安装.md`。
- 自动点击可能违反游戏《用户协议》、有封号风险，请仅在自担风险、不影响他人前提下使用。
