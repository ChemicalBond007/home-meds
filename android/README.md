# 赛博药箱 1.0：历史 WebView 外壳工程（`android/`）

> **维护状态说明（历史归档）**
> 本目录（`android/`，包名 `com.homemeds.app`，版本 `1.0.0 / versionCode 1`）是项目早期的 **Android WebView 原生外壳工程**。
> 随着服务端网页已重构为**仅面向管理员的设置中心**，网页端不再提供日常服药清单、药品入库、计划管理与 AI 对话页面。**家庭日常使用与管理员手机端操作请安装独立原生客户端 [`android-v2/`](../android-v2/README.md)（`com.homemeds.app.v2`，Jetpack Compose，2.4.0+）。**
> 本工程按兼容与历史参考目的原样保留在仓库中。

---

## 工程定位与设计概述

`android/` 将正式环境网页包裹在系统 `WebView` 中，并在配置的线上域名（构建时通过 `-PhomeMedsBaseUrl=https://your-domain.example.com` 指定）主框架（Main Frame）内通过 `@JavascriptInterface` 注入名为 `HomeMedsNative` 的原生桥接对象，为早期网页版补充以下系统级能力：

1. **本地日历与 48 小时闹钟同步（`CalendarSync.kt` / `Reminder.kt` / `Planner.kt`）**：
   - 从同源 `/api/plans` 与 `/api/groups` 拉取所选家庭成员的服药计划，按家庭时区与 `RRULE` 写入手机本机私有日历账号「家药箱」（`CalendarContract.ACCOUNT_TYPE_LOCAL`）。
   - 使用 `AlarmManager` 编排未来 48 小时内的本人准点提醒与家人超时 30 分钟提醒，并在每 6 小时（`WorkManager`）、应用启动、计划变更和开机广播（`BOOT_COMPLETED`）时重新同步。
   - 闹钟触发时先请求服务端核对是否已服药，点击通知打开确认路径 `/?plan=...&date=...&time=...&group=...`。
2. **手机端 AI 原生直连桥接（`AiBridge.kt` / `Policy.kt`）**：
   - 在 Android Keystore 中生成 `RSA-2048` 密钥对，向 `POST /api/app/ai/session` 申请由 `RSA-OAEP-SHA1` + `AES-256-GCM` 封装的 15 分钟临时 LLM / Tavily 密钥。
   - 密钥仅在 Kotlin 原生层解封，网页端 JS 仅持有 `hm-key:<sessionId>:llm|tavily` 占位符；原生层在校验请求目标严格属于已配置的提供商 HTTPS 根路径后才注入真实 `Authorization` 头，且禁止跟随重定向。
   - 公开网页抓取（`fetchPage`）在原生层校验公网 URL 与全部 DNS 解析地址，拦截私网与本机回环地址。
3. **系统语音与相机权限桥接（`MainActivity.kt`）**：
   - 提供系统中文语音识别（`SpeechRecognizer`）、中文语音合成朗读（`TextToSpeech`）与网页扫码相机权限请求。

---

## 源码结构

```text
android/app/src/
├── main/
│   ├── AndroidManifest.xml                  # 权限、MainActivity 与 ReminderReceiver 声明
│   └── java/com/homemeds/app/
│       ├── MainActivity.kt                  # WebView 容器與同源页面加载控制
│       ├── HomeMedsApp.kt                   # Application 入口
│       ├── Bridge.kt                        # HomeMedsNative (@JavascriptInterface) JS 原生桥接实现
│       ├── Backend.kt                       # 服务端 API 请求辅助
│       ├── NativeHttp.kt                    # OkHttp 客户端、密钥占位符替换与防 SSRF 公开网页抓取
│       ├── DeviceKeys.kt                    # Android Keystore RSA-2048 密钥对生成与密文解封
│       ├── CalendarSync.kt                  # 本地日历「家药箱」写入与清理
│       ├── Reminders.kt                     # AlarmManager 48 小时闹钟调度与响铃前服务端核对
│       ├── Sync.kt                          # WorkManager 定时同步与日历/闹钟协调
│       ├── Schedule.kt                      # 日程计算、RRULE 生成、日历事件与闹钟计划推导
│       ├── Speech.kt                        # 系统中文语音识别与 TTS 朗读
│       ├── Models.kt                        # 计划、提醒与配置数据模型
│       ├── Config.kt                        # 服务端根地址配置
│       └── Policy.kt                        # 公网 URL/IP 校验（防 SSRF）、密钥路径检查与 RSA-OAEP+AES-GCM 解封
└── test/java/com/homemeds/app/
    ├── PlannerTest.kt                       # 日程、夏令时、RRULE 与闹钟计算单元测试
    └── PolicyTest.kt                        # URL/IP 策略、密钥路径隔离与 WebCrypto 密文解封单元测试
```

---

## 构建与测试方法

需要 **JDK 17+**（推荐 JDK 21）与 **Android SDK Platform 37**（`minSdk = 29`，`targetSdk = 36`，`compileSdk = 37`）。

1. 在 `android/local.properties`（已被 `.gitignore` 忽略）中配置本机 SDK 路径：
   ```properties
   sdk.dir=/path/to/your/Android/Sdk
   ```
2. 执行单元测试、Lint 与 Debug 构建：
   ```powershell
   cd android
   # $env:JAVA_HOME = '<JDK 21 安装路径>'
   .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
   ```
   构建产物位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

3. 本地调试地址覆盖（仅 Debug 构建允许连接 `http://127.0.0.1`）：
   ```powershell
   adb reverse tcp:5173 tcp:5173
   cd android
   .\gradlew.bat assembleDebug -PhomeMedsBaseUrl=http://127.0.0.1:5173
   ```

---

## 升级至 `android-v2/`（2.x）的注意事项

1. **两个版本应用 ID 不同**：1.0 为 `com.homemeds.app`，2.x 为 `com.homemeds.app.v2`。安装 2.x 不会覆盖 1.0。
2. **避免旧日历账号重复创建**：2.x 客户端将本地日历名称升级为「**赛博药箱**」，并会在同步时自动清理本机遗留的「家药箱」本地日历。若手机上仍保留 1.0 应用，请**卸载 1.0 应用**或先在 1.0 中退出登录/关闭日历同步，以免 1.0 后台任务再次创建「家药箱」日历导致重复提醒。
