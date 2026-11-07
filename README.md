# 赛博药箱（HomeMeds）

**赛博药箱**（仓库与包名 `home-meds`，早期名称「家药箱」）是一套面向家庭日常用药场景的服药计划、药盒库存与提醒管理系统。

后端基于 **Cloudflare Worker + Hono + D1** 构建，无需自备域名或 R2 存储；前端网页（**Cloudflare Pages**，「赛博药箱管理台」）为管理员提供账号安全、家庭设置、AI 配置与应用下载设置，另提供无需登录的公开下载页；家庭成员的日常服药打卡、扫码/拍照查药入库、服药计划管理、AI 对话助手与管理员全局使用日志由独立原生 **Android 客户端**（[`android-v2/`](android-v2/README.md)，Jetpack Compose）承载。

---

## 文档导航

| 文档 | 内容说明 |
| :--- | :--- |
| [`README.md`](README.md)（本文档） | 项目总览、核心功能、本地开发、部署流程、测试检查、目录结构与安全边界 |
| [`android-v2/README.md`](android-v2/README.md) | 原生 Android 客户端（Jetpack Compose）编译、功能细节、AI Agent Loop、本地提醒与 SemVer 版本策略 |
| [`docs/architecture.md`](docs/architecture.md) | 系统架构拓扑、源码模块职责、D1 数据库 Schema 与触发器、加密认证模型、AI 搜索与提醒设计 |
| [`docs/api.md`](docs/api.md) | Worker HTTP / SSE 接口手册（聚合同步、认证安全、药品库存、计划服药、AI 与全局使用日志） |

---

## 系统组成与端侧分工

### 公开应用下载

访客直接打开 `/download`（也兼容 `/downloads`）即可查看简洁的安卓版下载页，无需登录，不读取管理员会话或家庭资料。管理员在「应用下载」中保存 APK 的公开 HTTPS 下载链接，页面按钮随配置更新；留空保存可关闭下载，未配置时明确提示「暂未开放下载」。签名下载链接和无 `.apk` 后缀的 HTTPS 地址也受支持，临时链接过期后需由管理员更新。

本站只保存链接，不上传、代理或转存安装包，也不会在服务端请求下载目标。链接对所有访客公开，请勿填入私密凭据；配置接口仅限完整管理员会话，并检查同源请求及记录审计。现有网址、模型地址和 APK 默认服务器的脱敏占位配置保持不变。

| 端侧 / 模块 | 目录 | 技术栈 | 主要职责 |
| :--- | :--- | :--- | :--- |
| **网页管理台与下载页** | `src/client/`、`pages/` | React 19 + Vite 8 + TypeScript | **管理台仅限管理员访问，下载页公开**。提供「账号安全」（TOTP 验证器、WebAuthn 通行密钥、恢复码）、「家庭设置」（家庭、成员、药箱、管理员重置）、「AI 配置」（模型、搜索来源、配额与接口检测）及「应用下载」（APK 下载链接）。普通成员会话访问管理台会被拒绝，但可访问 `/download`，且不影响其手机端权限。 |
| **服务端 API 与定时任务** | `src/worker/`、`migrations/` | Cloudflare Worker + Hono + D1 + WebCrypto | 提供 REST / SSE API、D1 关系型数据存储与 SQLite 事务触发器、HKDF/AES-GCM 密钥加解密、操作审计日志，以及每 5 分钟运行的 Cron Web Push 提醒任务。 |
| **跨端共享模型** | `src/shared/` | TypeScript + Zod 4 | 前后端共用的数据校验 Schema、GS1 / GTIN / 20 位药品追溯码解析器、跨时区/DST 日程计算与今日清单状态推导。 |
| **原生 Android 客户端** | `android-v2/` | Kotlin + Jetpack Compose + CameraX + ML Kit + ZXing + WorkManager | 应用 ID `com.homemeds.app.v2`（当前 `2.5.0 / 20500`）。支持分组码优先注册与扫码填写；承载普通成员、老人模式与管理员的全部日常功能：今日清单、两步核对服药、扫码/多角度拍照识药入库、有效期/批号特写识别、库存流水、服药计划、AI 语音/多模态助手、本地日历与精确闹钟提醒，以及管理员全局使用日志。 |

---

## 核心功能概览

### 1. 扫码与多角度拍照识药入库
- **多码制本地解析**：支持 GS1（含 `(01)` GTIN、`(17)` 有效期、`(10)` 批号、`(21)` 序列号）、13/14 位 GTIN/EAN 商品条码、中国 20 位药品追溯码及含追溯码参数的网址。Android 2.x 使用本地 ML Kit 与 ZXing 离线识别，同画面下优先提取 20 位追溯码。
- **双层药品与药盒模型**：
  - **药品资料（`products`）**：记录本家庭共用的药名、通用名、规格、厂家、基本单位、默认每盒数量、适应症与注意事项，以及条码/追溯码前 7 位映射（`product_codes`）。普通成员可新建药品资料，**仅管理员可修改已有资料**；已关联库存或计划的药品禁止修改基本单位，防止历史数量语义错乱。
  - **在库药盒（`stock_items`）**：每盒独立记录所属药箱（`warehouses`）、有效期截止日、批号、序列号、20 位追溯码（全库防重）、每盒装量、当前剩余数量、开封状态与乐观锁版本号（`metadata_version`）。有效期与批号归属具体药盒而非共用药品资料。
- **多角度拍照与特写日期识别（Android 2.x）**：支持同一药盒最多 6 张照片（正面、背面、侧面、盒底、说明书）合并识别，以及在入库第二步或药盒资料编辑中单独拍摄特写识别有效期与批号。支持完整日期（`YYYY-MM-DD`）与仅年月（如 `2028-06`，表单提示确认保存时按当月实际月末日记录）。**AI 仅做预填草稿，必须人工核对实物后才写入数据库**。

### 2. 服药计划、今日清单与原子扣库存
- **灵活周期与实时计算**：支持每天（`daily`）、每周固定日（`weekly`）、每隔 N 天（`interval`）与按需（`as_needed`）四类频率，按家庭所在时区（默认 `Asia/Shanghai`）实时计算今日服药清单，不预先生成每日冗余行。超过计划时间 60 分钟未记录自动标为「过时未吃（`overdue`）」。
- **优先药盒与两步实物核对**：
  - 计划可指定一盒「优先药盒」（`preferred_stock_item_id`）；未指定时默认沿用上次服药使用的可用药盒（`last_stock_item_id`），按「已开封优先、到期早优先」排序。若优先药盒已用尽或报废，不会静默改扣其他药盒，必须由用户重新选择。
  - 服药采用**两步确认**：确认时间、用量与目标药盒，并勾选「已核对实物药名、规格、有效期、批号及用量」；若药盒已过期，还须额外勾选过期二次确认。
- **D1 触发器原子校验与当日撤销**：
  - 提交服药记录时携带药盒 `expected_stock_version`，由 SQLite 触发器在事务内一次性完成家庭归属、基本单位一致性、资料版本乐观锁、可用余量校验、扣减库存、自动标记开封/用尽、写入 `stock_movements` 流水并更新计划的 `last_stock_item_id`。
  - 唯一索引阻止同一计划同一时间点重复勾选，并发请求也不会把库存扣成负数。当日撤销（`undo`）在同一事务内原子恢复库存且仅允许恢复一次。

### 3. 老人模式与无障碍设计
- 老人账号（`is_elderly = 1`）默认展示极简大字今日清单，隐藏计划与 AI 助手底部导航，控件触控区域至少 `60px`/`60dp`、正文字号放大（`22sp`），所有状态均采用「文字 + 符号」双重表达，并在系统支持时提供中文语音朗读（TTS）与语音输入。
- **绝不缓存过期的今日服药清单**：离线时明确提示无法记录，避免因陈旧状态造成漏服或重复服药。

### 4. 双轨服药提醒（Web Push + Android 本地日历与闹钟）
- **服务端 Cron Web Push**：每 5 分钟扫描未记录的服药项，到点向本人已订阅设备发送 RFC 8291 `aes128gcm` + RFC 8292 VAPID 加密推送；超过计划时间 30 分钟仍未记录且开启「通知家人」时，向同家庭已订阅的非老人成员发送提醒（包含跨午夜的前一日晚间计划检查）。通过 `notification_log` 去重，点击通知仅打开核对确认流程，**不能在通知栏直接勾掉**。
- **Android 本地日历与精确闹钟**：将所选家人的计划按家庭时区与 `RRULE` 同步至本机私有本地日历「赛博药箱」（不同步云端账号），并预先编排未来 48 小时系统闹钟。闹钟触发时先向服务器实时核对计划与服药状态，已服药或计划已暂停则静默跳过；离线无法核对时仍发出提醒并注明「尚未联网核对」。

### 5. AI 查药与计划对话助手
- **双提供商模式**：支持「DeepSeek（官方，固定 `https://api.deepseek.com`）」与「其他 LLM（任意 OpenAI 兼容地址，默认示例 `https://llm.example.com/api` + `qwen3.5-27b`）」。
- **内置搜索与外部搜索**：
  - **内置搜索**：支持 OpenAI Responses API `web_search`、Chat Completions 联网参数（默认 `enable_search=true` 或自定义 JSON 如 `{"web_search_options":{}}`）以及 Anthropic 兼容 `/v1/messages` 服务端 `web_search`（DeepSeek 默认）。后台提供一键「检测内置搜索并自动选择搜索来源」，仅当接口返回结构化引用/搜索结果块时才采信。正式查询若内置搜索失败或无来源会明确报错，**不会隐式回退到外部搜索**。
  - **外部搜索**：支持 Tavily Search API 与可选的 DuckDuckGo HTML 检索，配合逐跳校验公网地址、防 SSRF 的 `fetch_page` 工具（10 秒超时、2 MB 响应上限、提取正文截断至 20 KB、仅允许读取本轮搜索结果中的 URL）。
- **只读工具与待确认草稿隔离**：对话助手通过 `find_medicines` 与 `list_medicines` 只读检索本家庭药箱目录，通过 `add_plan_draft`、`propose_plan_update`、`propose_plan_pause` 生成经过 Zod 校验的待确认计划卡片（Android 2.x 还支持 `propose_medicine_draft` 药品入库草稿）。**AI 工具无任何数据库写权限**，所有草稿仅保存在客户端内存中，必须由用户打开卡片核对确认后才调用正式写接口落库。
- **手机端安全直连**：Android 客户端通过系统 Keystore 生成 RSA-2048 密钥对，经 `POST /api/app/ai/session` 接收由 RSA-OAEP-SHA1 + AES-256-GCM 封装的 15 分钟临时凭据，仅在原生层解密并严格注入到已配置的提供商 HTTPS 根路径下（禁止重定向），数据库工具仍回调 Worker `/api/app/ai/tool` 执行。

### 6. 请求聚合与写后快照优化
- **管理台一次初始化**：网页管理台首次加载仅请求一次 `GET /api/admin/bootstrap`，一次性获取账号安全状态（含通行密钥列表与恢复码余量）、家庭/成员/药箱列表及脱敏 AI 配置。
- **手机端聚合加载与后台同步**：Android 2.4.0+ 冷启动仅请求一次 `GET /api/app/bootstrap` 获取完整家庭状态；后台 WorkManager 与闹钟刷新仅请求一次 `GET /api/app/sync` 获取计划与时区。旧服务端返回 `404/405/501` 或单家庭聚合体积超过 `2 MiB` 时自动回退分项接口。
- **写操作附带状态快照**：客户端修改家庭数据时携带请求头 `X-HomeMeds-State: admin` 或 `app`，服务端在同一响应中直接返回最新 `admin_state` / `app_state`，省去保存后的二次刷新请求，且在 Android 端直接复用响应中的计划快照重建本地日历与闹钟。

### 7. 管理员安全、操作审计与全局使用日志
- **多因素绑定与恢复**：首位管理员通过命令行生成 7 天有效的临时密码登录（处于 `binding_only` 受限会话），在浏览器本地生成 TOTP 二维码（不调用外部二维码服务）或注册 WebAuthn 通行密钥后，自动签发 10 个一次性恢复码并使临时密码失效。支持按管理员查看与逐把撤销通行密钥，以及通过二次验证（TOTP 或通行密钥）重新生成恢复码。
- **五类全局使用日志**：全局管理员可在 Android 2.x「设置 → 使用日志」跨家庭筛选查看 **操作审计（`audit`）、AI 查询明细（`ai_queries`）、AI 配额用量（`ai_usage`）、服药记录（`doses`）、库存流水（`stock`）**，支持游标分页。操作审计仅记录时间、用户/家庭快照、操作类型、HTTP 状态、路由模板与对象编号，**绝不记录密码、验证码、恢复码、密钥、Cookie、IP 或聊天原文**；审计写入失败时记录 `audit_write_failed` 告警，不会使已提交的业务操作报错回滚。

---

## 快速开始（本地开发）

### 环境要求
- **Node.js 24+** 与 npm
- 构建原生 Android 客户端需 **JDK 21** 与 **Android SDK Platform 37**（详见 [`android-v2/README.md`](android-v2/README.md)）

### 1. 安装依赖与初始化本地数据库

```powershell
npm ci
npm run db:local
```

### 2. 配置本地环境变量

将 `.dev.vars.example` 复制为 `.dev.vars`（已被 `.gitignore` 忽略，**切勿提交或把密钥发到聊天中**）：

```powershell
Copy-Item .dev.vars.example .dev.vars
```

在 `.dev.vars` 中填写本地随机生成的 `APP_ENC_KEY`（至少 32 字符，用于派生 TOTP 与后台 AI 密钥的 AES-GCM 加密密钥）：

```ini
APP_ENC_KEY=请替换为本机生成的至少32位高熵随机字符串
VAPID_PRIVATE_KEY=
# 以下变量仅供 npm run probe:ai 命令行探测脚本使用，Worker 正式运行只读取管理后台加密保存的配置
LLM_BASE_URL=https://llm.example.com/api
LLM_API_KEY=
LLM_MODEL=qwen3.5-27b
TAVILY_API_KEY=
```

### 3. 构建并启动本地服务

```powershell
npm run build
npm run dev:worker
```

- 打开浏览器访问 `http://127.0.0.1:8787` 即可使用本地 Worker 托管的管理台与 API。
- 如需前端热更新联调，保持 `npm run dev:worker` 运行，另开终端执行 `npm run dev`，访问 Vite 地址 `http://127.0.0.1:5173`（`/api` 请求会自动代理至 `127.0.0.1:8787`）。
- **安全提示**：本地开发服务固定绑定 `127.0.0.1`，请勿使用 `--host 0.0.0.0` 将本地药箱暴露到局域网或公网。

### 4. 可选：导入本地演示数据

```powershell
npm run db:demo
```

- 演示账号用户名：`演示家人`、`演示老人`，演示家庭分组码：`DEMO-LOCAL-ONLY`。
- **警告**：`tests/fixtures/demo.sql` 仅包含明确标记的虚构演示药品，**严禁用于真实服药指导，严禁导入远程生产数据库**。

### 5. 创建首位管理员并完成安全绑定

```powershell
npm run admin:create -- 管理员名字
```

1. 命令向本地 D1 写入管理员账号，并在终端输出一个 **7 天有效的临时密码**。
2. 打开管理台登录页，展开「使用临时密码或恢复码」登录（此时处于 30 分钟有效的 `binding_only` 受限绑定会话）。
3. 在页面上完成 **通行密钥（WebAuthn）** 或 **身份验证器（TOTP）** 绑定：
   - **Microsoft Authenticator**：选择「`+` → 添加帐户 → 其他帐户 → 扫描二维码」（不要选「工作或学校帐户」），或支持 Google Authenticator 等标准 TOTP 应用（`otpauth://totp`，前缀 `HomeMeds`，SHA-1、6 位、30 秒）。
   - 扫码仅导入密钥，必须在页面输入当前 6 位动态验证码提交后才算完成绑定。
4. 绑定成功后，临时密码与旧恢复码立即作废，页面将**唯一一次**展示 10 个新恢复码，请立即离线妥善保存。
5. 随后可在「家庭设置」中创建家庭，点击「显示分组码二维码」向可信家人展示。Android「注册成员」先手动输入分组码或扫码填写，再填写用户名并确认注册；空白、错误或已更换的分组码不能创建账号。扫码不自动注册、不更改服务器地址。也可在「AI 配置」中填入正式的 LLM / Tavily 密钥。

分组码二维码在浏览器本地生成，内容为 `{"type":"home-meds.join","version":1,"code":"分组码"}`，不包含网站、API 地址或密钥，不访问第三方二维码服务。更换分组码后旧二维码立即失效，已注册成员不受影响。二维码等同于家庭注册邀请，请勿公开分享。

---

## 常用 NPM 脚本速查

| 命令 | 说明 |
| :--- | :--- |
| `npm run dev` | 启动 Vite 前端开发服务器（`127.0.0.1:5173`，代理 `/api` 到 `8787`） |
| `npm run dev:worker` | 启动本地 Wrangler Worker 开发服务（`127.0.0.1:8787`） |
| `npm run build` | 执行 TypeScript 类型检查、Vite 生产构建，并运行 `scripts/check-built-css.mjs` 校验 WebView 87 媒体查询兼容性 |
| `npm run check` | 运行 `tsc --noEmit` 静态类型检查 |
| `npm run check:pages` | 编译校验 `pages/functions` 下的 Cloudflare Pages Functions |
| `npm run types` | 根据 `wrangler.jsonc` 生成 `worker-configuration.d.ts`（自动生成文件，勿手改） |
| `npm test` | 使用 Vitest 与 `@cloudflare/vitest-plugin` 在本地 `workerd` 运行时中执行全套单元与接口集成测试 |
| `npm run db:local` | 将 `migrations/` 下的 SQL 迁移应用到本地 D1 数据库 |
| `npm run db:demo` | 向本地 D1 导入 `tests/fixtures/demo.sql` 虚构演示数据（**仅限本地**） |
| `npm run db:remote` | 将 `migrations/` 下的 SQL 迁移应用到远程生产 D1 数据库 |
| `npm run admin:create -- <用户名> [--remote]` | 创建管理员账号并生成 7 天临时密码（默认写本地 D1，加 `--remote` 写远程生产 D1） |
| `npm run vapid:generate` | 生成 Web Push VAPID 密钥对，公钥打印至终端，私钥写入 `.wrangler/vapid-private.secret`（已存在时不覆盖） |
| `npm run probe:ai` | 读取 `.dev.vars` 中的测试密钥，实测 LLM 模型列表、JSON 提示词、工具调用往返及 Tavily 搜索耗时 |
| `npm run deploy` | 本地构建并依次发布 Worker 与 Pages（不自动执行远程 D1 迁移） |

---

## 部署指南（Cloudflare Pages + Worker + D1）

线上正式环境采用 **Cloudflare Pages 前端 + 服务绑定转发至 Cloudflare Worker 后端** 的同源架构（示例域名 `https://your-domain.example.com`）：

- **`home-meds` Worker（根目录 `wrangler.jsonc`）**：承载全部 `/api/*` 路由、D1 数据库绑定、加密密钥与每 5 分钟一次的 Cron 提醒触发器。已显式关闭 `workers_dev` 与预览地址，仅允许通过 Pages 服务绑定（Service Binding）和 Cron 调用（规避 `workers.dev` 域名 1101 错误，同时保证 Cookie、同源 `Origin` 校验与 WebAuthn 通行密钥 `RP ID` 唯一绑定在你的 Pages 正式域名下）。
- **`home-meds` Pages（`pages/` 目录）**：托管 `dist/` 静态资源，并通过 `pages/functions/api/[[path]].ts` 将所有 `/api/*` 请求原样经服务绑定 `API` 转发给 `home-meds` Worker。

### 日常自动发布（GitHub Actions）

仓库已配置 [`.github/workflows/checks.yml`](.github/workflows/checks.yml)：
1. **所有分支推送与 Pull Request**：自动在 Node 24 环境下运行 `npm test`、`npm run build`、`npx wrangler deploy --dry-run` 与 `npm run check:pages`。
2. **`main` 分支推送或手动触发 `workflow_dispatch`**：在检查全部通过后，按顺序串行执行：
   - `npm run db:remote`（应用远程 D1 迁移）
   - `npm run deploy:worker`（发布 Worker）
   - `npm run deploy:pages`（发布 Pages）
   任何一步失败即中止后续步骤；同一分支构建自动排队防并发冲突。
3. **平滑滚动兼容要求**：发布过渡期间，旧 Worker 会在新数据库结构上短暂运行，旧前端也会短暂调用新 Worker。因此新增数据库迁移与 API 改动应保持向后兼容（如只新增表或可空列，破坏性清理放到后续版本）。

### 首次启用 GitHub Actions 自动发布

1. 在 Cloudflare 控制台 **Manage Account → Account API Tokens** 创建自定义令牌，最小权限授予：
   - **Workers Scripts**：Specified Workers → `home-meds`（Editor）
   - **Cloudflare Pages**：Edit / Write
   - **D1**：Edit
2. 在 GitHub 仓库 **Settings → Secrets and variables → Actions** 中添加两个 Repository Secrets：
   - `CLOUDFLARE_API_TOKEN`：上述 API 令牌
   - `CLOUDFLARE_ACCOUNT_ID`：Cloudflare 账号 ID（可通过 `npx wrangler whoami` 查看）

### 部署到全新的 Cloudflare 账号

```powershell
# 1. 登录并创建 D1 数据库
npx wrangler login
npx wrangler whoami
npx wrangler d1 create home-meds
```

将返回的 `database_id` 更新至根目录 `wrangler.jsonc`，并运行 `npm run vapid:generate` 生成 VAPID 密钥对（将公钥填入 `wrangler.jsonc` 的 `VAPID_PUBLIC_KEY`，将 `VAPID_SUBJECT` 改为你维护的联系网址或 `mailto:` 地址）。

准备生产密钥（切勿提交版本库，上传后请移入密码管理器并删除本地明文文件）：
- `APP_ENC_KEY`：至少 32 字符的高熵随机字符串（**必须永久备份，丢失或替换将导致已保存的 TOTP 与后台 AI 密钥无法解密**）
- `VAPID_PRIVATE_KEY`：`npm run vapid:generate` 生成的私钥

```powershell
# 2. 执行远程迁移、构建并上传密钥与 Worker / Pages
npm run db:remote
npm run build
npx wrangler deploy --secrets-file .secrets/production.env
cd pages; npx wrangler pages project create home-meds --production-branch main; cd ..
npm run deploy:pages

# 3. 创建远程首位管理员
npm run admin:create -- 管理员名字 --remote
```

---

## 测试与质量验证

### 1. 服务端与前端单元测试（Vitest + workerd）

```powershell
npm run types
npm run check
npm test
npm run build
npx wrangler deploy --dry-run
npm run check:pages
```

- 测试运行于 `@cloudflare/vitest-plugin` 提供的真实 `workerd` 运行时、真实本地 D1 与 WebCrypto 环境中，外部 AI、搜索与推送端点通过 `tests/fetch.ts` 模拟（完整经过 `workerd` 的 `Request` 构造校验，如 `redirect: 'error'`）。
- `tests/` 目录共 25 个测试文件，覆盖：跨时区/DST 日程计算、GS1/GTIN/追溯码解析、并发扣库存与乐观锁回滚、重复服药拦截、当日撤销幂等、家庭数据隔离、`binding_only` 受限会话、TOTP 防重放、一次性恢复码与通行密钥重生成挑战绑定、管理台通行密钥列举与删除、操作审计脱敏与写入故障隔离、五类全局日志跨家庭筛选与游标分页、AI 能力探测、内置搜索结构化引用校验、格式修复预算、草稿不落库、流式失败审计、Web Push 加密去重、跨午夜家属提醒及 `410 Gone` 失效订阅清理。

### 2. Android 客户端测试（JUnit + Robolectric + Compose UI Test）

详见 [`android-v2/README.md`](android-v2/README.md)。在 `android-v2/` 下执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

### 3. 响应式布局与无障碍回归

- **响应式布局自检页**：启动 `npm run dev` 后，在浏览器打开 `http://127.0.0.1:5173/tests/fixtures/responsive.html`，在开发者工具控制台运行 `await runResponsiveChecks()`。该工具使用合成数据并拦截所有网络请求，自动遍历 `320px` 至 `1440px` 共 9 种视口宽度下的 189 个界面与弹窗场景，检测横向溢出、移动端单列表单与导航切换，返回的 `failures` 应为空数组。
- **无障碍检查（WCAG 2.1 AA）**：在 `390×844` 视口下通过 axe 检查老人首页、扫码入口与服药确认弹窗，颜色对比度与触控尺寸均满足 AA 标准。

---

## 仓库目录结构

```text
.
├── .github/workflows/
│   └── checks.yml            # CI 检查与 main 分支自动部署流水线（D1 迁移 → Worker → Pages）
├── android-v2/               # 原生 Android 2.5.0 Jetpack Compose 客户端（com.homemeds.app.v2）
├── docs/                     # 架构设计与服务端 API 详细文档
│   ├── architecture.md       # 系统架构、D1 数据模型、安全加密与 AI/提醒工作流说明
│   └── api.md                # HTTP / SSE API 路由、请求头、参数与响应格式手册
├── migrations/               # Cloudflare D1 SQLite 迁移脚本（0001 至 0007）
│   ├── 0001_initial.sql      # 核心表结构、索引与服药扣库存/撤销恢复 SQLite 触发器
│   ├── 0002_stock_movements.sql # 出库/报废/移库校验与库存状态更新触发器
│   ├── 0003_code_integrity.sql  # 药品条码映射不可变性约束触发器
│   ├── 0004_audit_logs.sql   # 管理员操作审计日志表与多维索引
│   ├── 0005_stock_metadata_version.sql # 药盒资料版本乐观锁与追溯码前缀同步触发器
│   ├── 0006_plan_preferred_box.sql     # 服药计划优先药盒（preferred_stock_item_id）字段扩展
│   └── 0007_app_ai_sessions.sql        # 原生客户端 AI 临时会话与工具调用配额表
├── pages/                    # Cloudflare Pages 部署配置与 /api/* 服务绑定转发函数
│   ├── functions/api/[[path]].ts
│   └── wrangler.jsonc
├── public/                   # PWA Manifest、Service Worker、离线兜底页与自托管图标
├── scripts/                  # 运维与构建校验脚本
│   ├── admin-create.mjs      # 创建管理员账号与 7 天临时密码
│   ├── check-built-css.mjs   # 校验构建产物 CSS 的 WebView 87 兼容性
│   ├── probe-ai.mjs          # 本地实测 LLM 与 Tavily 接口连通性及耗时
│   └── vapid.mjs             # 生成 Web Push VAPID 密钥对
├── src/
│   ├── client/               # React 19 网页管理台源码（账号安全、家庭设置、AI 配置、扫码组件）
│   ├── shared/               # 前后端共享模块（Zod Schema、条码/GS1/追溯码解析、跨时区日程计算）
│   └── worker/               # Cloudflare Worker Hono 后端源码（认证、库存、计划、AI、推送、审计）
├── tests/                    # Vitest + workerd 集成测试与本地演示数据（fixtures/demo.sql、responsive.html）
├── .dev.vars.example         # 本地开发环境变量模板
├── index.html                # 网页管理台入口 HTML
├── LICENSE                   # MIT 开源许可证
├── package.json              # 项目依赖、脚本与元数据
├── tsconfig.json             # TypeScript 编译配置
├── vite.config.ts            # Vite 构建与本地 /api 代理配置
└── wrangler.jsonc            # Cloudflare Worker、D1 绑定与每 5 分钟 Cron 配置
```

---

## 已知限制与安全边界

1. **普通成员弱认证定位**：普通家庭成员采用「分组码注册 + 用户名直接登录」的无密码方案，旨在降低家庭老人与成员的使用门槛。该设计**仅适用于受信任的家庭内部自建场景**，不适用于开放公共注册、医疗机构或高合规隐私场景。
2. **医疗与用药安全免责**：AI 检索、多角度照片识药与有效期/批号识别均可能出错或遗漏；系统的药品资料、服药计划草稿与提醒**不能代替执业医师或药师的医嘱，也不能代替服药前对实物药盒、药名、规格、有效期、批号和用量的肉眼核对**。
3. **提醒不等于已服药**：无论是 Web Push 还是 Android 本地日历/精确闹钟，均可能受设备断网、关机、系统休眠、国产 ROM 后台限制或缺少推送服务影响而延迟或漏发；收到提醒绝不代表已经完成服药。
4. **主加密密钥（`APP_ENC_KEY`）不可丢失**：服务端使用 `APP_ENC_KEY` 经 HKDF 派生出独立的 AES-GCM 密钥来加密管理员 TOTP 密钥与后台 LLM/Tavily API 密钥。一旦丢失或直接替换 `APP_ENC_KEY`，已有加密数据将无法解密（当前不包含密钥轮换重加密迁移工具）。
5. **应用内审计非防篡改合规存证**：`audit_logs` 是保存在同一 D1 数据库中的应用层只读审计记录，用于家庭管理员日常排查，不具备独立硬件或外部 WORM 防篡改能力。
6. **手机端 AI 直连的内存边界**：Android 客户端开启「手机直接连接模型」时，服务端下发的加密凭据会在手机原生层内存中短暂解密以直连提供商 HTTPS 接口。在已 Root 或开启调试注入的设备上存在被读取内存的理论风险；如介意可在「设置 → 助手偏好」中关闭手机直连，改走服务端 SSE。

---

## 许可证

本项目基于 [MIT License](LICENSE) 开源，Copyright (c) 2026 chen。
