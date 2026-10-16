# 家药箱

一个 Cloudflare Worker 同时托管 React PWA、Hono API 和五分钟一次的服药提醒，使用 D1，不需要域名或 R2。

## 本地运行

需要 Node.js 24。

```powershell
cd E:\Temp\Projects\home-meds
npm ci
npm run db:local
npm run build
npm run dev:worker
```

打开 `http://127.0.0.1:8787`。开发前端时，同时运行 `npm run dev`，打开 Vite 提供的地址，API 代理到 8787。不要使用 `--host 0.0.0.0` 向外网暴露本地药箱。

可选的本地演示数据：

```powershell
npm run db:demo
```

演示用户名：`演示家人`、`演示老人`。分组码为 `DEMO-LOCAL-ONLY`。数据只有明确标识的虚构药品，**不要用于真实用药，不要把演示 SQL 应用到远程数据库**。

## 首位管理员

先复制 `.dev.vars.example` 为 `.dev.vars`，在本机安全生成并填写至少 32 字符的随机 `APP_ENC_KEY`。不要把密钥发到聊天里，也不要提交此文件。`LLM_API_KEY`、`TAVILY_API_KEY` 仅供探测脚本使用，Worker 正式查询只读取管理后台加密保存的配置。

```powershell
npm run admin:create -- 管理员名字
```

命令只写本地 D1，并显示一个 7 天有效的临时密码。选择「管理员」登录，在「使用临时密码或恢复码」中填写。此时只有绑定权限。完成通行密钥或验证器绑定后，临时密码和旧恢复码失效，显示 10 个新恢复码。请离线保存。

管理员先创建群组，把分组码交给家人注册。管理员可在「设置 → 家庭管理 → 成员所属群组」把自己加入群组；全局管理员也可以直接切换群组管理。普通成员只能看到本群，不接受客户端伪造的群组选择。

普通用户按用户名登录，这是本项目明确接受的弱认证方案，不适合开放注册、医疗机构或高隐私场景。管理员重置会撤销目标管理员的会话和验证方式。使用恢复码也会撤销已有验证方式，然后要求重新绑定。

临时绑定会话持续 30 分钟，普通会话持续 1 年并按使用续期。登录限流不适用于日常查看清单。生产 Cookie 使用 Secure、HttpOnly 和 SameSite=Strict，所有写 API 检查同源 Origin。

## 主要功能

- 扫码解析 GS1、GTIN/EAN、20 位追溯码和追溯网址。优先使用 BarcodeDetector，回退到自托管 zxing-wasm，无需加载外部 CDN。
- 入库先查本群药品和全局缓存，AI 只预填，必须人工核对后创建药品并入库。每盒单独记录剩余数量，追溯码不可重复使用。
- 普通成员可新建资料，只有管理员可修改已有资料。已有库存或计划的药品不能修改基本单位，避免旧数量被错误解释，需另建正确单位的药品。药箱有记录后不能删除，只能改名。
- 出库、移库、报废、流水、过期及 30 天临期提示。每盒同时显示盒数和剩余基本单位数量。
- 所有成员可查看、修改本群任何人的吃药计划，记录创建人、修改人和服药操作人。
- 每天、每周固定日、每隔 N 天和按需计划，按群组时区实时计算今日清单，不提前生成每日记录。超过计划时间 60 分钟标为过时未吃。
- 两步确认实际服药时间、用量和药盒；按已开封、到期早的顺序列盒，默认记住上次的盒子，支持扫码或不扣库存。过期药必须再次明确确认。
- D1 batch 内的 SQLite 触发器同时执行校验、扣库存、流水和记住药盒。唯一索引阻止重复勾选，并发时也不会扣成负数。当日撤销通过同一事务恢复库存，仅恢复一次。
- 按需服药从计划页记录，不发定时提醒。同日已记录的项即使计划暂停仍保留展示。
- 老人首页清单优先，大字、至少 60px 操作区域、文字加状态符号、两步确认、支持时才显示中文朗读和语音输入。不缓存旧服药清单，离线时明确说明不能记录，避免误导。

## AI 配置

管理员在「设置 → AI 配置」保存 LLM 与 Tavily 密钥。两者用 APP_ENC_KEY 派生的独立 AES-GCM 密钥加密保存，TOTP 使用另一个 HKDF 派生密钥。密钥不回传前端。请使用新的正式 LLM 密钥，轮换曾出现在规划对话中的那一把。

默认：

- 地址 `https://claw.shu.edu.cn/api`
- 模型 `qwen3.5-27b`
- 额外参数 `{"enable_thinking":false}`
- 工具调用，提示词约束输出；可选预检索、json_schema，不提供 json_object
- Tavily 必需；DuckDuckGo 默认关闭，需在部署后的后台检测后再决定是否开启
- 每群每天查询 20 次，管理员查询不限额；每人每天对话 50 轮，可由后台调整
- 一次查询最多 6 次 LLM 请求（包括唯一一次格式修复）、5 次工具调用；对话每轮最多 5 次工具调用、6 次 LLM 请求

`fetch_page` 每次 10 秒，逐跳检查公开 HTTP/HTTPS 地址，不允许私网 IP、凭证网址、本地域名和非标准端口。读取最多 2MB，提取文字后截断到 20KB。仅允许读取本轮搜索结果中的网址。搜索资料和网页均作为不可信数据处理。

对话历史只在前端内存中保存，刷新即清空，每轮发送最近最多 20 条消息。提议工具没有数据库写权限，只发送经 zod 校验的草稿，确认卡片再调用普通 REST API，重新检查权限和基本单位。**AI 可能补充来源没有的内容，程序不能代替人工核对。**

接口能力测试不会自动改动配置，也不会保存查询结果。探测脚本：

```powershell
npm run probe:ai
```

它从忽略的 `.dev.vars` 读取测试密钥，检查模型列表、提示词 JSON、工具往返和可选 json_schema，记录耗时；有 Tavily 测试密钥时补测搜索。没有配置密钥时明确失败或跳过，不把模拟结果当成真实接口结果。

## Web Push 与安装

推送使用 `@block65/webcrypto-web-push` 的 WebCrypto、RFC 8291 aes128gcm 和 RFC 8292 VAPID，支持 Workers 和 Apple 推送。

```powershell
npm run vapid:generate
```

公钥显示在终端，填入 `wrangler.jsonc` 的 `VAPID_PUBLIC_KEY`。私钥只写入忽略目录 `.wrangler/vapid-private.secret`，文件已存在时不会覆盖。将 `VAPID_SUBJECT` 改为你维护的 HTTPS 联系页面或 `mailto:` 联系地址。

每五分钟 Cron 向尚未记录的服药项发送本人提醒，超过 30 分钟时通知已订阅的同群非老人成员。检查前一天的日程，避免 23:50 的家人提醒跨过午夜后漏发。24 小时以上的旧项不发推送。`notification_log` 按计划、日期、时间、类型和设备去重；推送失败可重试；404/410 删除订阅。网络中断导致外部推送服务已接收但未确认时，重试无法保证外部严格 exactly-once，通知 tag 会合并同一项。

共享手机退出登录时同时移除此账号的本机订阅，重新登录后需重新开启提醒，避免向后来使用设备的人显示原用户的提醒。

点击通知只打开确认流程，**通知里不能直接勾掉**。过期通知不会替今天的项勾选。手机先添加到桌面，再从桌面进入「设置 → 开启提醒」。国内安卓可能缺少 Google 推送服务，iPhone 需要 iOS 16.4 以上；首页提醒是主要渠道。

## 部署（Pages 前端 + Worker 后端）

正式网址：`https://home-meds.pages.dev`。

- `home-meds` Worker（根目录 `wrangler.jsonc`）：全部 API、D1、密钥和每五分钟的 Cron 提醒。已关闭 workers.dev 和预览网址，只能经服务绑定和 Cron 调用。本地 `npm run dev:worker` 仍由它同时提供页面和 API。
- `home-meds` Pages 项目（`pages/`）：发布同一份 `dist` 静态文件；`pages/functions/api/[[path]].ts` 把 `/api/*` 请求原样经服务绑定 `API` 转给 Worker。Cookie、同源检查和通行密钥 RP ID 都取自这个请求，因此都绑定 `home-meds.pages.dev`。

不用 workers.dev 的原因：在当前账号上，所有 workers.dev 请求都返回 1101，连只有一行代码的测试 Worker 也一样，Cloudflare 分析中的脚本名为 `__unknown__`；而同一版本的 Cron 和服务绑定调用都正常，pages.dev 也正常。即使以后 workers.dev 恢复，也建议只保留一个正式网址，避免通行密钥和登录状态分裂。

日常发布：

```powershell
npm run deploy   # 构建，先发布 Worker，再发布 Pages
```

`wrangler.jsonc` 已绑定正式 D1 数据库和 VAPID 公钥。正式 `APP_ENC_KEY` 与 VAPID 私钥已作为 Worker 密钥上传，之后的发布不会改动它们。本地的 `.secrets/production.env` 在忽略目录中，请把内容移入密码管理器后删除本地副本。

部署到新账号时：

```powershell
npx wrangler login
npx wrangler whoami
npx wrangler d1 create home-meds
```

将返回的 `database_id` 填入 `wrangler.jsonc`，确认目标账号，然后：

```powershell
npm run db:remote
npm run build
npx wrangler deploy --secrets-file .secrets/production.env
cd pages; npx wrangler pages project create home-meds --production-branch main; cd ..
npm run deploy:pages
npm run admin:create -- 管理员名字 --remote
```

`.secrets/production.env` 使用 `.env` 格式，包含 `APP_ENC_KEY`（至少 32 字符的随机值）和 `VAPID_PRIVATE_KEY`（`npm run vapid:generate` 生成的私钥）。也可以改用 `npx wrangler secret put` 从安全输入逐个上传。不要把真实密钥放入命令参数或终端输出。APP_ENC_KEY 必须稳定保存，丢失或直接替换会使已有 TOTP 和后台 API 密钥无法解密；本版不包含加密密钥重加密迁移工具。Pages 项目名若已被占用，网址会带后缀，需要同步修改 `VAPID_SUBJECT`。

部署后在 `https://home-meds.pages.dev` 绑定管理员。通行密钥绑定此网址，本地和正式域名不能互用。再在后台填入正式 LLM/Tavily 密钥，检测接口能力，实测 DuckDuckGo。最后在真机安装 PWA、订阅并设置一条人工确认过的测试计划，核对 Cron、本人通知、家人通知、点击确认和库存变化。

Wrangler 的远程 migration、secret 和 deploy 都会修改远程状态。不要把 `db:demo` 或测试用密钥用于生产。

## 检查

```powershell
npm run types
npm run check
npm test
npm run build
npx wrangler deploy --dry-run
npm run check:pages
```

Workers 测试使用 `@cloudflare/vitest-plugin`，它是计划中 `@cloudflare/vitest-pool-workers` 的官方更名后继。旧包已停止更新，旧 runtime 与本项目兼容日期不一致，因此改用当前后继包，而非降低测试兼容日期。测试在 workerd 内运行真实 D1 和 WebCrypto，外部 AI、搜索和推送服务使用模拟响应。

测试覆盖时区及 DST 边界、每日/每周/隔日计算、GS1、并发扣库存与回滚、重复勾选、当日撤销、群组隔离、绑定会话、TOTP 重放、一次性凭证、AI 格式修复、限额、草稿不落库、推送加密与去重、跨午夜家人提醒和 410 清理。CI 运行测试、构建和部署打包检查，不自动部署。

浏览器测试使用本地演示数据。在 390×844 视口下，老人首页、扫码入口和服药确认弹窗的 axe（WCAG 2.1 AA）检查无违规；剩余 incomplete 项为只含符号的勾选按钮和弹窗遮罩后的元素，已人工核对颜色对比度达到 AA。隐藏的桌面浏览器面板不能进行键盘输入，若面板隐藏，对比度检查也可能显示 incomplete。自动检查不等同于真机和 Lighthouse 验收；相机、WebAuthn 系统弹窗、语音、安装和实际推送需要有权限的真实设备验证。免费版 CPU、Cron 大量加密和实际国内推送可达性也需要上线测量。

## 目录

```text
src/client/       React 界面与扫码组件
src/shared/       zod、数据类型、扫码解析、日程计算
src/worker/       Hono、账号、库存、计划、AI、推送
migrations/      D1 结构与事务触发器
scripts/         管理员创建、AI 探测、VAPID
tests/           workerd 单元和接口测试、本地演示 SQL
public/          PWA manifest、SW、离线页、自托管图标
pages/           Pages 前端配置和 /api 转发函数
```
