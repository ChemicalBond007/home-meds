# 代码精简与 APK 瘦身

## 项目判断与清理边界

当前网页入口为 `src/client/main.tsx → App.tsx`，只提供管理员设置和公开下载页；日常功能由 `android-v2/` 的独立 Compose 客户端承担，不加载网页资源。

沿静态、动态和类型导入检查，原网页的库存、计划、服药核对、聊天、设置、使用日志、扫码及其辅助模块均已无法从网页入口到达。本次移除了这条旧功能链，以及仅为它服务的 `native.ts` WebView 桥接层、浏览器 SSE 读取器、语音朗读组件和旧样式，共 13 个旧源码/样式文件。`zxing-wasm` 及 3 个独占间接依赖也已移除。

- 网页登录只保留管理员流程，不再维护成员注册分支和旧 App 模式判断。
- 网页 API 不再访问原生桥接对象，也不会发送 Android 专用请求头；家庭选择、Cookie、取消请求、HTTP 错误状态和写后快照不变。
- 删除 5 个专用于退役网页功能的测试文件；混合测试只移除退役 UI 的部分，保留服务端 AI 草稿与不落库校验。新增网页 API 回归测试。
- 布局自检改用当前管理台的聚合初始化接口和真实样式，覆盖登录、首次绑定、空家庭、管理面板及下载页。
- 开启 TypeScript `noUnusedLocals` / `noUnusedParameters`，阻止未使用的局部声明继续积累。
- Vite 开发服务不再监听 Android 工程、SDK 工具与本地 QA/Worker 生成目录，避免 APK/报告写入引发无关刷新和 Windows 文件占用告警。
- Android 删除未引用的预览依赖声明和 `voice_prompt` 字符串。

**刻意保留**：Android 正在调用的全部 Worker API、共享 AI/日程/条码规则、数据库迁移、服务端 Web Push 与旧 Service Worker 注销兼容逻辑。没有根据“网页不再引用”误删跨端功能，也没有清理本机缓存、SDK、日志或用户数据。

## APK 优化方法

1. `release` 开启 R8 代码压缩、优化与混淆，并开启资源压缩。仅使用 Android 默认优化规则与依赖自带的 consumer rules，没有用整包 `keep` 规则抵消瘦身。
2. 调试工具仍限于 `debugImplementation`；正式分发不要使用 `assembleDebug` 产物。
3. 默认继续生成兼容 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 的通用包。可选 `-PhomeMedsAbis=arm64-v8a` 只保留对应架构本地库，并使用独立文件名，避免与通用包混淆；非法 ABI 参数会直接报错。
4. ML Kit 离线扫码模型、CameraX、系统相机、多图识药、提醒、语音功能不移除。架构专用包只适用于明确匹配的设备。

### 体积测量

使用本机 JDK 21、同一应用版本和默认服务端配置测量 APK 文件大小。通用包优化前后均为 **release**，避免用 debug 与 release 的差值冒充压缩收益；数值使用 MiB（1,048,576 字节）。

| 产物 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 通用 release | 69.63 MiB | 24.76 MiB |
| ARM64 专用 release（可选） | 未测量 | 9.95 MiB |

通用 release 减少 **64.44%**。原有 debug 为 88.50 MiB，仅作参考。瘦身后的通用包中约 19.47 MiB 是四种 ABI 的原生库，主要来自离线扫码；不取消离线能力时，按设备架构分发比继续删业务代码更有效。

精确文件大小：通用 release 从 73,014,516 字节降至 25,963,200 字节；ARM64 release 为 10,430,083 字节。两份 APK 与各自的混淆映射已保存到本地 `.qa/optimization-2026-11-08/`（不入库）。

网页生产 CSS 从 28.01 kB 降至 12.57 kB；JS 从 380.78 kB 降至 378.65 kB。旧网页业务原本已被构建器 tree-shaking 排除，因此删除源码主要改善维护成本，**并不会直接缩小独立 Android APK**。

## 构建与验证

```powershell
npm test
npm run build

cd android-v2
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleRelease
.\gradlew.bat assembleRelease -PhomeMedsAbis=arm64-v8a
```

浏览器打开 `tests/fixtures/responsive.html` 自检页，运行 `await runResponsiveChecks()`；默认覆盖 9 种宽度、99 个场景，`failures` 应为空。

本次验证：20 个 Web 测试文件、191 项测试通过，生产构建（含严格类型检查和 CSS 兼容校验）、Pages Functions 编译与 Worker `--dry-run` 通过；99 个布局场景无失败。管理员账号安全页的 axe WCAG 2 A/AA 自动检查无违规。新增请求测试首次运行时暴露了测试夹具的 `localStorage` 清理问题，修复后已全量复测。

最终 Android 默认构建的 169 项测试全部通过（无失败、错误或跳过），`lintDebug` / `lintRelease` 均为 0 错误、26 项警告。通用包的四种 ABI、ARM64 包的单一 ABI，以及两者的 3 份离线扫码模型和相应扫码原生库均已核验；3 组非法 ABI 参数均被拒绝。Vite 文件监听烟测确认普通 `public/` 文件仍触发事件，Android 构建与 QA 生成文件不再触发事件。

**签名与验证限制**：仓库没有配置正式签名，release APK 未签名，分发前必须使用自己的正式密钥并保存对应 `mapping.txt`。单元测试与 Lint 不等价于混淆后的真机测试，发布前仍需验证扫码、登录、日历/闹钟、语音及 AI 网络请求。本次不覆盖连接手机上已有的应用或医疗数据。

**已有告警**：Android Lint 仍有版本更新、目标 SDK、同步写入会话配置等警告，未通过禁用检查来掩盖。`npm audit` 报告开发/测试工具依赖链中有 7 项告警（3 high、4 moderate）；本次没有顺带批量升级依赖。
