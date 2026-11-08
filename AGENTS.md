# 仓库指南

赛博药箱是家庭药箱管理项目。仓库包含 Cloudflare Worker 后端、Pages 管理台，以及主力原生 Android 客户端 `android-v2/`（Kotlin + Jetpack Compose）。

## 版本号与发布

适用范围：任何会产出新正式 APK 的改动。

- 只有用户明确说明要「发布正式版」时，才执行正式版发布程序，包括正式版号升级、正式 APK 构建与签名、正式发布记录更新及正式渠道发布。仅要求修改代码、测试、打包、构建 APK 或提供安装包，不视为正式发布授权，默认使用测试版。部署等对外操作仍须遵守对应的明确授权要求。
- 版本号按 Semantic Versioning 2.0.0 决定。发布前必须使用 `semantic-versioning` skill 判断版本升级级别（MAJOR / MINOR / PATCH），并在对话中说明依据。
- 正式版号只在 `android-v2/app/build.gradle.kts` 的 `defaultConfig` 中修改，同时更新 `versionName` 与 `versionCode`。
- `versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`，且 `MINOR` 与 `PATCH` 必须小于 100。例：`2.8.6` 对应 `20806`。
- 同一正式版号不得重复发布。`versionCode` 必须大于上一个正式版。
- 发布时同步更新 `android-v2/README.md` 中的当前版本、APK 文件名示例，以及「版本演进记录」表格。
- 不要修改已发布版本的历史记录条目。

## 测试版

适用范围：所有测试、调试安装与真机验证。除非用户明确要求正式版，测试时一律使用测试版。

### 目标

测试版必须能与正式版同时安装在同一部手机上，互不覆盖。因此测试版必须同时满足以下要求：

- 使用不同的应用 ID：正式版为 `com.homemeds.app.v2`，测试版在其后追加 `.test`，即 `com.homemeds.app.v2.test`。
- 桌面显示名称含「测试」：例如「赛博药箱测试」。
- `versionName` 与正式版相关，但带有测试标注。格式为 `<基准正式版>-test.<N>`，例如 `2.8.5-test.1`。
- `<基准正式版>` 是 `app/build.gradle.kts` 中当前的正式版号，即测试版所基于的正式版。
- `<N>` 从 1 开始，同一基准版本下每次重新构建测试版时递增。
- 测试版的 `versionCode` 与基准正式版相同，因为包名不同，两者不会冲突。
- 测试版 APK 文件名需同样带有「测试」，例如 `赛博药箱测试-2.8.5-test.1.apk`。

### 操作要求

- 测试版入口为 `.\gradlew.bat assembleTest`，实际构建类型为 `qa`（Android Gradle 插件保留 `test` 前缀，不能直接用作构建类型名）。也可用 `assembleQa`，不要用正式版的 `release` 构建来做测试。
- 测试序号由构建自动分配，按基准版保存在已忽略的 `android-v2/.gradle/test-builds/`，不要删除或提交。失败的构建也可能消耗序号；`clean` 不会重置序号。可用 `-PhomeMedsTestBuild=N` 指定高于已分配值的序号。
- 测试版使用独立本地日历「赛博药箱测试」，不得清理正式版或旧正式版日历。包名隔离不代表服务端数据隔离，真机测试应使用测试账号或独立测试服务端。
- 测试版 APK 不得发布或上传到正式渠道，也不得替换用户手机上的正式版。
- 不要修改正式版的 `applicationId`、`versionName` 或 `versionCode` 来做测试。
- 测试版不得改变正式版号。测试版号只由构建配置派生，不手动写死在 `build.gradle.kts` 中。
- 如需为测试版使用不同的服务端地址，通过命令行参数 `-PhomeMedsBaseUrl=https://...` 指定，不要修改 `local.properties` 中已有的正式配置。

## 构建与验证命令

在 `android-v2/` 目录下，于 PowerShell 中运行：

```powershell
.\gradlew.bat testDebugUnitTest lintQa assembleTest
.\gradlew.bat lintRelease assembleRelease
```

- 测试版构建命令应使用 `assembleTest`，具体以构建脚本中的任务名为准。
- 开始构建前确认 `JAVA_HOME` 指向 JDK 21。
- 正式构建会自动运行 `verifyReleaseScanner`，不要跳过。

## 安全与提交

- 不要提交 `local.properties`、`.dev.vars`、`.secrets/` 中的任何内容，也不要把密钥写进代码、日志或回复。
- 正式版签名密钥不得使用调试密钥替代。
- 正式签名已接入 `.secrets/home-meds_android-release/`，构建时读取，不得输出密码或密钥内容。测试版沿用现有签名策略，正式签名材料缺失时仅测试版可回退到调试签名。
- 未经用户明确要求，不要执行部署、远程数据库迁移或密钥轮换。
