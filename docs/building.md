# 终端打包与自动导出

在包含 `gradlew.bat` 的项目根目录运行：

```powershell
# Debug 包
.\gradlew.bat :lyricon:assembleDebug

# Release 包
.\gradlew.bat :lyricon:assembleRelease
```

Windows 上打包时，会额外复制 APK 到 `F:\output\<项目文件夹名>\`。
当前项目文件夹名为 `lyricon-source`，因此导出位置为：

```text
F:\output\lyricon-source\
  lyricon-1.0.40.rc-debug-arm64-v8a.apk
  lyricon-1.0.40.rc-release-arm64-v8a.apk
```

目标目录会自动创建。文件名包含模块名、版本号、构建类型和 CPU 架构；
同版本、同类型的包再次打包时覆盖对应副本，其他版本和类型保留。
项目原有的 APK 和 ZIP 输出位置继续保留。

自动导出直接挂在 Gradle 的打包流程上，无需额外执行复制命令。
使用 `assemble` 同时打包两种类型时也会分别导出。
非 Windows 环境不执行 F 盘导出。

## 每次 Git 提交自动递增版本

应用版本统一放在根目录的 `version.properties`。Git 提交时，内部 `versionCode` 加 1，
显示版本 `versionName` 的第三段也加 1，保留 `.rc` 等后缀。
例如 `1.0.40.rc / 51` 在下一次提交时变为 `1.0.41.rc / 52`。
单独打包不会递增版本，APK 导出名称会跟随提交后的版本。

当前工作副本已启用钩子。新克隆的工作副本需在项目根目录执行一次：

```powershell
git config --local core.hooksPath .githooks
```

钩子通过 Windows 自带的 PowerShell 运行；其他系统需安装 `pwsh`，并确保
`.githooks/pre-commit` 与 `.githooks/post-commit` 有执行权限（`chmod +x .githooks/pre-commit .githooks/post-commit`）。
自动递增发生在提交前，也适用于 `--amend`。请使用正常提交流程，
不要用 `--no-verify` 或临时禁用 `core.hooksPath` 跳过钩子。
取消或失败后重试同一次提交，会根据 HEAD 使用同一个待提交版本，避免重复递增。
钩子只自动暂存 `version.properties`，其他文件仍按原先选择的暂存范围提交。
部分提交完成后只同步版本文件的暂存区状态，不影响其他已暂存的文件。
