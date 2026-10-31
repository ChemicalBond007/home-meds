# 家药箱 2.0：原生 Android

独立 Jetpack Compose 客户端，不嵌入浏览器、不下载家药箱网页资源。仅访问现有服务端 `/api/*` JSON / SSE；手机端 AI 可直接访问后台配置的模型、Tavily 和公开资料页。

`android/` 1.0 保持不变。2.0 使用应用 ID `com.homemeds.app.v2`，可与 1.0 并存，不继承其浏览器登录态，需要重新登录。两版使用相同的本地日历账号「家药箱」，**不要同时开启两版的日历同步**，避免相互覆盖日程。

## 编译

需要 JDK 21、Android SDK 37。首次构建需联网下载 Compose、CameraX、ML Kit（随 APK 打包离线模型）、ZXing 等依赖。

在 `android-v2/local.properties` 配置本机 SDK 路径（不提交版本库），例如：

```properties
sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
```

```powershell
cd E:\Temp\Projects\home-meds\android-v2
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。默认服务器为 `https://home-meds.pages.dev`，可以通过 `-PhomeMedsBaseUrl=https://your-host.example` 更换 HTTPS 同源根地址。不支持明文 HTTP。

## 界面与能力

- 今日本人和家人清单、过期/临期提示、扫码查询和入库。
- 两步服药核对、实物确认、追溯码精确匹配、过期二次确认、`expected_stock_version` 乐观锁、跳过与撤销。
- 库存、药品资料、流水；管理员编辑药品/条码映射及 `expected_version` 药盒资料。
- 每日/每周/间隔/按需计划、餐时快捷键、优先药盒、暂停和编辑。
- AI 流式对话、中文语音输入/朗读、待确认新增/修改/暂停草稿。草稿不自动写入正式计划。
- 管理员 TOTP 本地二维码、验证器唤起、一次性恢复码文件保存和重生成；家庭切换、餐时、成员转组/老人模式、药箱管理、其他管理员凭据重置。
- AI 提供商、模型、搜索协议、配额/超时、接口探针与测试查询；五类全局日志、筛选和游标分页。
- 配色复用网页设计令牌。普通控件至少 48dp，老人模式至少 60dp、正文 22sp，隐藏计划和助手底部导航项。

## 提醒

应用启动建立通知渠道与 6 小时 WorkManager 周期任务。登录、切换家庭、计划/服药变更和提醒设置变更触发 1.5 秒防抖同步。

日历使用所选成员的家庭时区和 RRULE。应用闹钟覆盖未来 48 小时；本人按计划时间提醒，非老人用户可接收所选家人超过 30 分钟未记录的提醒。触发时核对当前计划与服药记录，无法联网时通知明确提示尚未核对。点击通知打开对应家庭的原生确认弹窗，过时或已记录的提醒不会直接重复记录。

请在「设置 → 提醒与账号」授权日历、通知和精确闹钟，并检查电池优化。拒绝精确闹钟时使用系统尽力调度，不能保证准点。离线、系统限制后台运行和设备关机都会影响提醒，不能作为唯一用药保障。

退出或换家庭会清理旧日历和闹钟。跨家庭只同步当前所选家庭。

## 网络与安全

- Cookie 存在应用私有偏好中，不使用 `CookieManager`。写请求附带同源 `Origin`，家庭请求附带 `X-Group-Id`；服务端仍负责鉴权。
- 禁用备份与敏感页面截屏。密钥、恢复码、二维码与聊天不写入应用日志。
- 手机 AI 通过 Android Keystore RSA-2048、RSA-OAEP-SHA1 + AES-256-GCM 接收临时凭据；不保证所有设备具备硬件后端，使用系统提供的 Keystore。
- 只向配置的 HTTPS 提供商根路径注入密钥，带凭据请求不跟随重定向；公开抓取验证 URL 和所有 DNS 答案，限制重定向、响应大小、超时与工具次数。
- 内置搜索支持 Responses、Chat Completions 和 Anthropic。外部搜索使用 Tavily，可选 DDG；来源须来自实际检索。AI 药品资料始终待人工核对。
- 未建立手机 AI 会话时可回退服务端 SSE；不会在手机端已经消费配额后静默重跑。助手也可主动关闭「手机直接连接模型」。
- 恢复码原文只能在生成时保存；现有 `/auth/recovery` 仅提供剩余数量，无法重新读取旧码。

## 验证范围

JVM 测试覆盖日程/夏令时/RRULE/48 小时闹钟、密钥解封和地址策略、GS1/GTIN/追溯码、选盒规则、Cookie/Origin/数组接口、SSE、取消请求和思考标签过滤。

还需在真机验收：相机实物码、中文语音引擎、系统日历提供商、通知权限/精确闹钟、重启恢复、各厂商后台限制，以及真实管理员/家庭的端到端流程。JVM 与 Lint 不能代替这些验证。
