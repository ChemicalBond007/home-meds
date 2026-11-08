# 赛博药箱（HomeMeds）服务端 HTTP / SSE API 手册

本文档列出 `src/worker/`（Cloudflare Worker + Hono）提供的全部 HTTP 与 Server-Sent Events（SSE）接口契约。

系统架构、数据库触发器与加密模型请参见 [`docs/architecture.md`](architecture.md)。

---

## 1. 通用约定

### 1.1 基础路径与同源保护
- 所有接口路径均以 `/api` 开头（线上通过 Cloudflare Pages Functions `pages/functions/api/[[path]].ts` 经服务绑定 `API` 原样转发至 Worker）。
- **会话认证**：登录成功后服务端通过 `Set-Cookie` 下发 `hm_session`（`HttpOnly; SameSite=Strict; Path=/`，HTTPS 环境下附加 `Secure`）。
- **写操作同源校验**：所有非 `GET / HEAD / OPTIONS` 的写请求必须携带与当前请求 Host 同源的 `Origin` 请求头，否则返回 `403 {"error": "拒绝跨站请求"}`。

### 1.2 常用自定义请求头

| 请求头 | 取值示例 | 说明 |
| :--- | :--- | :--- |
| `X-Group-Id` | `<group_uuid>` | 指定当前操作的家庭分组 ID。普通成员固定只能访问本人所属家庭（伪造其他家庭 ID 会被服务端忽略或拒绝）；全局管理员可通过此头切换所管理的家庭。也可通过查询参数 `?group_id=` 传递。 |
| `X-HomeMeds-State` | `admin` \| `app` | 可选。在家庭数据写请求（`POST`/`PUT`/`PATCH`/`DELETE`）中携带时，若写操作成功（HTTP 2xx），响应 JSON 会额外附带最新聚合快照字段 `admin_state` 或 `app_state`；若快照生成失败或超过 2 MiB，则附带 `state_error` 提示客户端单独刷新。 |
| `X-HomeMeds-Client` | `web-admin` \| `android` | 客户端类型标识。携带 `web-admin` 时，普通成员会话调用 `/api/auth/me` 或登录接口将被直接拒绝（`403`），确保网页管理台仅供管理员使用。 |

### 1.3 会话权限级别
1. **匿名（未登录）**：仅可调用健康检查、注册、登录、通行密钥登录挑战与公开条码格式解析。
2. **受限绑定会话（`binding_only = true`）**：管理员使用 7 天临时密码或一次性恢复码登录后获得的 30 分钟临时会话。**仅允许调用 `/api/auth/*` 下的验证器/通行密钥绑定接口与退出接口**；调用任何业务、家庭、AI 或日志接口均返回 `403`。
3. **普通成员会话（`role = 'user'`）**：可访问本人所属家庭的药品、库存、服药计划、今日清单、服药打卡、Web Push 订阅与 AI 查药/对话接口；不可修改已有共用药品资料或药盒元数据，不可访问管理员接口。
4. **完整管理员会话（`role = 'admin'` 且 `binding_only = false`）**：可使用全部接口，包括跨家庭管理、修改共用药品资料与药盒元数据、配置 AI 提供商、管理通行密钥与恢复码，以及查看五类全局使用日志。

---

## 2. 健康检查与聚合同步接口

| 方法 | 路径 | 权限 | 说明 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/health` | 公开 | 返回 `{"ok": true, "service": "home-meds"}`。 |
| `GET` | `/api/admin/bootstrap` | 管理员（含 `binding_only`） | **网页管理台初始化聚合接口**。当 `binding_only = true` 时仅返回 `user` 与 `groups: []`；完整管理员一次性返回 `user`、`recovery_remaining`、`passkeys`（全部管理员已登记通行密钥列表）、`groups`、`group_id`、`members`、`warehouses` 与脱敏 `ai_settings`。 |
| `GET` | `/api/app/bootstrap` | 已登录成员/管理员 | **Android 2.4.0+ 客户端冷启动与刷新聚合接口**。一次性返回 `api_version: 1`、`user`、`groups`、`group_id`、`members`、`products`、`stock`、`plans`、`warehouses` 与 `today`（含今日清单与 30 天临期/过期药盒预警）。若单家庭快照超过 `2 MiB` 返回 `413 {"fallback": true}`，客户端自动回退分项接口。 |
| `GET` | `/api/app/sync` | 已登录成员/管理员 | **Android 2.4.0+ 后台提醒轻量同步接口**。返回当前家庭的 `group_id`、`timezone` 与 `plans` 列表，供 WorkManager 与本地日历/闹钟重建使用。 |

---

## 3. 认证与管理员安全接口（`/api/auth/*`）

### 3.1 注册、登录与会话

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/auth/me` | 公开/已登录 | 可选头 `X-HomeMeds-Client: web-admin` | 查询当前登录用户与 `binding_only` 状态；若为完整管理员额外返回 `recovery_remaining`（剩余可用恢复码数量）。未登录返回 `{"user": null}`。 |
| `POST` | `/api/auth/register` | 公开 | `{"username", "join_code", "is_elderly"?: boolean}` | 普通家庭成员须提供当前有效的家庭分组码（去除首尾空白后 1–100 字符）注册并自动登录，受 IP 限流保护。注册写入时原子校验分组码；空白、错误或轮换前的码返回 `400`，不创建账号或会话。家庭归属由分组码决定，不接受客户端伪造 `role`、`group_id` 或家庭选择头。 |
| `POST` | `/api/auth/login` | 公开 | `{"username", "totp_code"?, "bootstrap_secret"?, "recovery_code"?}` | 登录接口：普通成员仅传 `username`（在 `web-admin` 客户端下拒绝普通成员）；管理员传 `totp_code` 获得完整会话，或传 `bootstrap_secret`（7 天临时密码）/ `recovery_code`（一次性恢复码）获得 30 分钟 `binding_only` 绑定会话。 |
| `POST` | `/api/auth/logout` | 已登录 | 无 | 注销当前会话并清除 `hm_session` Cookie。 |

### 3.2 TOTP 验证器、通行密钥（WebAuthn）与恢复码

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/totp/setup` | 管理员（含 `binding_only`） | 无 | 生成新的 TOTP 密钥（AES-GCM 加密暂存至 `totp_secrets`），返回 `secret` 与 `uri`（`otpauth://` URL，前端本地渲染二维码）。 |
| `POST` | `/api/auth/totp/bind` | 管理员（含 `binding_only`） | `{"code": "6位数字"}` | 校验当前 TOTP 验证码（防重放），激活 TOTP 绑定，升级会话为完整管理员权限，作废临时密码并签发返回 **10 个全新恢复码**（`recovery_codes`）。 |
| `POST` | `/api/auth/passkey/register/options` | 管理员（含 `binding_only`） | 无 | 生成 WebAuthn 通行密钥注册挑战（Challenge 有效期 5 分钟）。 |
| `POST` | `/api/auth/passkey/register` | 管理员（含 `binding_only`） | `{"challenge_id", "response"}` | 验证并保存通行密钥公钥凭据，升级会话为完整管理员权限，作废临时密码并签发返回 10 个新恢复码。 |
| `POST` | `/api/auth/passkey/options` | 公开 | `{"username"}` | 为已绑定通行密钥的管理员生成 WebAuthn 登录挑战。 |
| `POST` | `/api/auth/passkey/login` | 公开 | `{"challenge_id", "response"}` | 验证通行密钥断言，更新签名计数器并创建完整管理员会话。 |
| `GET` | `/api/admin/passkeys` | 完整管理员 | 无 | 列出系统中所有管理员及其已登记的通行密钥元数据（ID、所属管理员、上次计数器、传输方式 `transports`，不含公钥原文）。 |
| `DELETE` | `/api/admin/users/:id/passkeys` | 完整管理员 | 路径参数 `:id`（管理员用户 ID），查询参数 `?credential_id=<凭据ID>` | 撤销指定管理员的指定通行密钥，并清理该管理员未完成的通行密钥登录挑战（不影响现有会话、TOTP 或恢复码）。 |
| `GET` | `/api/auth/recovery` | 完整管理员 | 无 | 返回当前管理员剩余未使用的恢复码数量 `{"remaining": number}`。 |
| `POST` | `/api/auth/recovery/passkey/options` | 完整管理员 | 无 | 为重新生成恢复码发起绑定当前会话的 WebAuthn 二次验证挑战（不能用于登录）。 |
| `POST` | `/api/auth/recovery/regenerate` | 完整管理员 | `{"method": "totp", "code": "6位数字"}` 或 `{"method": "passkey", "challenge_id", "response"}` | 经 TOTP 或通行密钥二次验证通过后，立即作废旧恢复码并生成返回 **10 个全新恢复码**（每管理员每 10 分钟限尝试 10 次）。 |

---

## 4. 家庭、成员与药箱管理接口

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/groups` | 已登录 | 无 | 普通成员返回本人所属家庭；管理员返回全部家庭列表（含 `timezone` 与 `meal_times`）。 |
| `POST` | `/api/groups` | 完整管理员 | `{"name", "join_code"?, "timezone"?: "Asia/Shanghai", "meal_times"?}` | 创建新家庭分组（自动创建默认药箱）。 |
| `PUT` | `/api/groups/:id` | 完整管理员 | `{"name", "timezone", "meal_times"}` | 更新家庭名称、IANA 时区与三餐默认时间。 |
| `POST` | `/api/groups/:id/rotate-code` | 完整管理员 | `{"join_code"?}` | 轮换重置指定家庭的分组码。 |
| `GET` | `/api/members` | 已登录 | `?group_id=` 或 `X-Group-Id` | 获取当前家庭的成员列表。 |
| `GET` | `/api/admin/users` | 完整管理员 | 无 | 获取系统中全部用户与管理员列表。 |
| `PATCH` | `/api/members/:id` | 完整管理员 | `{"username"?, "is_elderly"?, "group_id"?}` | 修改成员姓名、切换老人模式（`is_elderly`），或将成员/管理员转移至指定家庭。 |
| `POST` | `/api/admin/users/:id/reset` | 完整管理员 | 路径参数 `:id` | 重置目标管理员的验证方式（清空其 TOTP、通行密钥、恢复码与活动会话），并生成返回新的 7 天临时密码 `temporary_secret`。 |
| `GET` | `/api/warehouses` | 已登录 | `?group_id=` 或 `X-Group-Id` | 获取当前家庭的存放药箱（库位）列表。 |
| `POST` | `/api/warehouses` | 已登录 | `{"name"}` | 在当前家庭新建存放药箱（如「客厅药箱」「老人卧室」）。 |
| `PUT` | `/api/warehouses/:id` | 已登录 | `{"name"}` | 修改存放药箱名称。 |
| `DELETE` | `/api/warehouses/:id` | 已登录 | 路径参数 `:id` | 删除尚未关联任何药盒记录的空药箱（已有药盒记录的药箱禁止删除，只能改名）。 |

---

## 5. 扫码、药品资料与药盒库存接口

### 5.1 扫码解析与查库

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/codes/resolve` | 已登录 | `?code=<条码或追溯码原文>` | 解析条码（GS1 / GTIN / 20 位追溯码）并在当前家庭中查找匹配的药品资料（`product`）、具体药盒（`box`，当扫描已入库 20 位追溯码时精确命中）及全局缓存（`cached`）。 |

### 5.2 共用药品资料（`products`）

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/products` | 已登录 | 无 | 获取当前家庭的全部共用药品资料。 |
| `GET` | `/api/products/:id/codes` | 已登录 | 路径参数 `:id` | 获取指定药品已绑定的条码与追溯码前 7 位前缀列表。 |
| `POST` | `/api/products` | 已登录 | `{"name", "generic_name"?, "manufacturer"?, "specification"?, "base_unit", "units_per_package", "indications"?, "precautions"?, "status"?, "sources"?, "codes"?: [...]}` | 在当前家庭新建共用药品资料（可选同时绑定商品条码或追溯码前 7 位前缀）。 |
| `PUT` | `/api/products/:id` | **完整管理员** | `{"name", "generic_name", "manufacturer", "specification", "base_unit", "units_per_package", "indications", "precautions", "status", "sources", "codes"?: [...]}` | 管理员编辑共用药品资料及条码映射。若该药品已存在药盒库存或服药计划，**禁止修改 `base_unit`**（返回 `409`）；若条码与本家庭其他药品冲突则整体回滚（返回 `409`）。 |

### 5.3 在库药盒与出入库流水（`stock_items` / `stock_movements`）

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/stock` | 已登录 | `?status=active\|empty\|discarded\|all`（可选） | 获取当前家庭的药盒库存列表。 |
| `POST` | `/api/stock` | 已登录 | `{"product_id" \| "product": {...}, "warehouse_id", "box_count": 1..50, "units_per_package", "expiry_date"?, "batch_number"?, "serial_number"?, "trace_code"?, "gtin"?}` | 药盒入库（支持关联已有 `product_id` 或在入库时一并创建新药品；若填写 20 位 `trace_code` 则全库唯一不可重复入库，同时自动维护前 7 位品种映射）。 |
| `POST` | `/api/stock/:id/move` | 已登录 | `{"type": "out" \| "discard" \| "move", "amount"?, "to_warehouse_id"?}` | 对指定药盒执行手动出库（`out`）、报废（`discard`）或移库（`move`），由 SQLite 触发器原子校验余量、更新状态、递增 `metadata_version` 并记录流水。 |
| `PUT` | `/api/stock/:id` | **完整管理员** | `{"expiry_date", "batch_number", "serial_number", "trace_code", "units_per_package", "opened", "expected_version": number}` | 管理员修改具体药盒的批次资料与每盒装量（不改变当前剩余库存 `units_remaining`）。要求传入 `expected_version` 乐观锁；新的 `units_per_package` 必须容纳当前剩余库存及今日仍可撤销的服药量总和，否则返回 `409`。 |
| `GET` | `/api/movements` | 已登录 | 无 | 获取当前家庭最近出入库、服药扣减、撤销恢复、移库与报废流水。 |

---

## 6. 服药计划、今日清单与服药打卡接口

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/plans` | 已登录 | 无 | 获取当前家庭的全部服药计划（含关联成员名、药品规格/厂家及优先药盒摘要）。 |
| `GET` | `/api/plans/:id/boxes` | 已登录 | 路径参数 `:id` | 获取指定服药计划对应的可用药盒列表（按已开封、到期早排序）、`preferred_stock_item_id` 与 `last_stock_item_id`。 |
| `POST` | `/api/plans` | 已登录 | `{"user_id", "product_id"?, "preferred_stock_item_id"?, "medicine_name", "amount", "unit", "times": ["08:00"], "frequency", "weekdays"?, "interval_days"?, "start_date", "end_date"?, "meal_relation"?, "notes"?, "enabled"?, "notify_family"?}` | 新建服药计划。若关联了 `product_id`，`unit` 必须与该药品的 `base_unit` 一致；若指定了 `preferred_stock_item_id`，该药盒必须属于该药品且处于可用状态。 |
| `PUT` | `/api/plans/:id` | 已登录 | 同 `POST /api/plans` | 修改已有服药计划（记录最后修改人 `updated_by`）。 |
| `DELETE` | `/api/plans/:id` | 已登录 | 路径参数 `:id` | 删除指定服药计划。 |
| `GET` | `/api/today` | 已登录 | 无 | 按家庭时区实时计算并返回今日服药清单（`items`）及今日日期与时区。 |
| `GET` | `/api/doses` | 已登录 | `?date=YYYY-MM-DD`（可选） | 查询指定日期的服药打卡记录列表。 |
| `POST` | `/api/doses` | 已登录 | `{"plan_id", "scheduled_time"?, "local_date"?, "status": "taken" \| "skipped", "amount"?, "stock_item_id"?, "expected_stock_version"?, "confirmed_accuracy": true, "allow_expired"?}` | **记录服药或跳过**。当 `status = 'taken'` 时必须传 `confirmed_accuracy: true`；若所选药盒已过期，必须传 `allow_expired: true`。底层通过 D1 SQLite 触发器原子校验 `expected_stock_version`、校验余量、扣减库存并写入流水。版本冲突或余量不足返回 `409`。 |
| `POST` | `/api/doses/:id/undo` | 已登录 | 路径参数 `:id`（服药记录 ID） | **撤销当日服药记录**。仅限撤销家庭本地日期为「今天」且状态为 `taken` 的记录；由 D1 触发器在同一事务中将状态改为 `undone` 并原路恢复药盒库存。 |

---

## 7. Web Push 提醒订阅接口（`/api/push/*`）

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/push/config` | 已登录 | 无 | 返回 `{"public_key": c.env.VAPID_PUBLIC_KEY, "configured": boolean}`。 |
| `POST` | `/api/push/subscribe` | 已登录 | `{"endpoint": "https://...", "keys": {"p256dh": "...", "auth": "..."}}` | 仅接受 HTTPS，每用户每小时限 10 次（超出 429），最多保留最新 5 个订阅。 |
| `POST` | `/api/push/unsubscribe` | 已登录 | `{"endpoint": "https://..."}` | 移除当前用户在本设备上的指定 Web Push 订阅（共享设备退出登录时调用，防止向后续登录者推送原用户的服药提醒）。 |

---

## 8. AI 配置、查药与对话接口

### 8.1 管理员 AI 配置与能力探测（`/api/ai/*`）

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/ai/settings` | 完整管理员 | 无 | 获取脱敏后的 AI 配置（不含密钥明文，仅返回 `has_llm_key`、`has_tavily_key` 等）及今日家庭/个人已用配额。 |
| `PUT` | `/api/ai/settings` | 完整管理员 | 提供商、根地址、密钥、模型、搜索来源（`builtin` \| `tavily`）、内置搜索协议/参数、每日配额与超时等配置 | 更新并加密保存 AI 提供商配置（密钥使用 `APP_ENC_KEY` 派生的 AES-GCM 密钥加密存储）。 |
| `POST` | `/api/ai/models` | 完整管理员 | `{"provider"?, "base_url"?, "api_key"?}` | 从提供商 `/models` 接口动态拉取可选模型列表（支持使用输入框中尚未保存的临时密钥测试，不会因此保存密钥）。 |
| `POST` | `/api/ai/search-probe` | 完整管理员 | 同上 + 模型与内置搜索参数 | 实测提供商是否支持返回结构化检索来源的内置联网搜索（仅更新前端草稿，保存配置后生效）。 |
| `POST` | `/api/ai/probe` | 完整管理员 | 无 | 使用已保存的正式配置逐项检测模型列表、JSON 提取、工具调用往返及搜索连通性耗时。 |

### 8.2 服务端流式查药与对话接口（SSE）

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/ai/lookup` | 已登录 | `{"query"?, "code"?}` | **SSE 流式公开药品检索**。依次推送进度、含 `draft` 药品草稿与 `sources` 来源列表的结果事件（状态固定为待人工确认）或错误事件。 |
| `POST` | `/api/ai/chat` | 已登录 | `{"messages": [...], "target_user_id"?, "intent"?: "chat" \| "draft"}` | **SSE 流式用药计划助手对话**。支持多轮只读检索家庭药箱（`find_medicines` / `list_medicines`）并生成待确认计划草稿卡片、正文分块与模型推荐回复（`<suggested_replies>`）。 |

### 8.3 原生客户端 AI 会话与 Worker 代理接口（`/api/app/ai/*`）

供 Android 客户端在本地执行 AI Agent Loop 与多角度照片识药时配合使用：

| 方法 | 路径 | 权限 | 请求体 / 参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/app/ai/session` | 已登录 | `{"kind": "lookup" \| "chat", "query"?, "user_id"?, "intent"?: "chat" \| "draft"}` | lookup 必须提供 query，chat 必须提供 user_id。扣除每日配额，返回 15 分钟 session_id、expires_at、settings 及聊天 system_prompt，绝不返回提供商密钥或加密凭据。携带 public_key 的旧 APK 请求返回 501，文字自动回退服务端，照片需升级。 |
| `POST` | `/api/app/ai/provider` | 会话所属用户及家庭 | `{"session_id", "service": "llm" \| "tavily", "path", "body": {...}}` | 唯一采用 4 MiB 请求体上限的 API。会话未结束且未过期，每会话模型最多 12 次、搜索最多 5 次。模型路径仅允许 `/chat/completions`、`/responses`、`/messages`、`/anthropic/v1/messages`；Tavily 仅 `/search`，仅外部搜索模式的 lookup 会话。Worker 强制模型与 token 上限、剥离非 lookup 联网参数、注入密钥并禁止重定向。成功 JSON/SSE 流式返回，上游非 2xx 仅返回状态码，不返回响应体。 |
| `POST` | `/api/app/ai/tool` | 已登录 | `{"session_id", "name": "find_medicines" \| "list_medicines" \| "get_member_plans" \| "request_plan_details" \| "add_plan_draft" \| "propose_plan" \| "propose_plan_update" \| "propose_plan_pause", "arguments": {...}}` | 在服务端安全执行只读家庭数据库查询或校验生成待确认计划草稿（每个会话最多允许调用 5 次数据库工具）。 |
| `POST` | `/api/app/ai/finish` | 已登录 | `{"session_id", "success": boolean}` | 结束手机端 AI 会话，将会话最终状态写入 `ai_lookup_logs` 与 `audit_logs`（超时未结束的会话由每 5 分钟 Cron 自动回收记为失败）。 |

---

## 9. 管理员全局使用日志接口

| 方法 | 路径 | 权限 | 查询参数 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/admin/logs` | **完整管理员** | `kind`: `audit` \| `lookup` \| `usage` \| `dose` \| `movement`（默认 `audit`）<br>`user_id`?: 操作人或成员 ID<br>`group_id`?: 筛选指定家庭（不传则查全部家庭）<br>`outcome`?: `success` \| `failure`<br>`from`?: `YYYY-MM-DD`<br>`to`?: `YYYY-MM-DD`<br>`cursor`?: 上一页返回的 `next_cursor`<br>`limit`?: 分页条数 | 跨家庭查询五类全局日志，返回筛选下拉选项、当前页条目与下一页游标（`next_cursor`）。 |

---

## 10. 公开应用下载与管理员配置

| 方法 | 路径 | 权限 | 说明 |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/download` | 公开，无需登录 | 仅返回 `{ "android_apk_url": "..." }`，未配置时为空字符串；不返回账号、家庭或模型配置。 |
| `GET` | `/api/admin/download` | 完整管理员 | 读取下载设置，管理员聚合初始化也包含 `download`，通常无需单独请求。 |
| `PUT` | `/api/admin/download` | 完整管理员、同源请求 | 请求体为 `{ "android_apk_url": "HTTPS 链接或空字符串" }`；保存后直接返回更新后的设置，空字符串关闭下载。 |

下载链接最多 4096 个字符，仅允许公开 HTTPS 地址，不支持内网、IP 字面量或含账号密码的 URL；不要求 `.apk` 后缀，原样保留签名查询参数。配置存储在既有 `app_settings` 表，无需迁移。访客 `/download` 与 `/downloads` 只请求公开下载接口，下载按钮直接导航到配置目标，服务端不抓取或代理安装包。
