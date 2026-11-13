# 桌面端 → 移动端：功能迁移对照

依据 `dev` 分支（commit `cf8755ae`）的 `src/pages/`、`src/lib/api.ts`、
`crates/buddy-switch-core/src/modules/`、`crates/buddy-switch-server/src/api.rs` 梳理。

图例：**✅ 手机端已实现** · **🔌 由服务端代执行** · **❌ 无法移植（本机依赖）**

## 一、用户点名的四类功能

| 桌面端功能 | 移动端 | 实现方式 |
|:--|:--|:--|
| 积分查看（账号额度 / 剩余 / 到期 / 逐包明细 / 统计） | ✅ | `POST /api/credits/query` 逐账号拉取 + 卡片 / 进度条 / 到期预警；`GET /api/credits/stats` |
| 自动签到（今日状态 / 单签 / 全签 / 台账 / 配置） | ✅ | 页面操作走 `/api/checkin*`；**手机本地**用 `AlarmManager` 定时触发并推送通知 |
| 账号登录（OAuth 扫码 / 凭据导入 / 连接自检） | ✅ | `POST /api/oauth/start` + `/api/oauth/status`；`/api/accounts/paste-credential`；`GET /api/status` |
| 账号管理（列表 / 搜索 / 切换 / 备注 / 删除 / 回收站） | ✅🔌 | 手机发指令，实际写 `~/.buddy-switch/` 由服务端完成：`/api/switch`、`/api/accounts/*` |

## 二、逐模块对照

| 桌面模块（core / pages） | 手机端 | 说明 |
|:--|:--|:--|
| `credits.rs` / `credit_usage.rs` / `credit-expiry.ts` | ✅ | 额度、到期、统计的**读取与展示** |
| `checkin.rs`、`qoder/checkin.rs`、`trae/checkin.rs` | ✅ | 签到状态与领取动作全部远程化 |
| `oauth.rs`、`qoder/oauth.rs`、`trae/oauth.rs` | ✅ | 发起与轮询；授权页交给系统浏览器 |
| `account.rs`、`accounts/*.tsx` | ✅🔌 | 账号 CRUD 与切换由服务端执行 |
| `scheduler.rs` / `schedule.rs` / `rotate.rs` | ✅（部分） | 手机端自建定时签到；服务端轮换配置可读取，但轮换执行留在电脑 |
| `risk_control.rs` / `client_limits.rs` | 🔌 | 风控由服务端统一实施，手机只展示状态 |
| `overview-metrics.ts` / `OverviewPage.tsx` | ✅ | 待办：需重登 / 今日未签 / 积分将到期 |
| `token-stats` / `usage-*` / `gateway_ledger` | ⚠️ 部分 | 统计接口存在，手机端当前只呈现积分维度，Token 趋势图未搬 |
| `auth_file.rs`、`at_rest.rs`、`vault` | 🔌 | 凭据读写与加密存储完全在服务端 |
| `process.rs`、`programs.rs` | ❌ | 探测 / 启动本机客户端进程 |
| `vscode_ext.rs`、`vscode_cn_inject.rs`、`jetbrains.rs`、`codebuddy_cli.rs` | ❌ | 安装插件、patch IDE `settings.json`、切换 CLI |
| `memory.rs`、`session.rs`、`session_bundle.rs` | ❌ | 读写本地 JSONL / MEMORY.md、会话副本加法同步 |
| `backup.rs`、`export_import.rs` | ❌ | 读写本机备份文件、打开文件管理器 |
| `auth_webview.rs`（Tauri 登录窗） | ✅ 替代 | 改用系统浏览器打开 OAuth 验证地址 |
| `tray.rs`、`companion.rs`（托盘 / 悬浮窗） | ✅ 替代 | 改用系统通知 |
| `buddy-switch-gateway`（57891/57892/57894/57895 监听） | ❌ | 手机不适合常驻端口监听；仍由电脑提供 |

## 三、架构决策

1. **复用而非重写**：项目已有 `buddy-switch-server`（axum）+ webui 双通道，
   `api.ts` 的 `ROUTES` 表把 284 条 Tauri command 映射为 HTTP 路由。手机端直接用这份契约，
   避免与桌面端行为漂移。
2. **不引入第三方依赖**：为能在纯 build-tools 下离线构建 APK，原生层只用 Android Framework
   （WebView / HttpURLConnection / AlarmManager / Notification），UI 用内置单文件 SPA，
   无 AndroidX / Material / Gradle / React Native。
3. **安全边界**：服务端 `api_guard.rs` 要求 Host 在白名单、POST 带 JSON、无 `Origin` /
   `Sec-Fetch-Site` 跨站标记。`ApiClient` 严格按此构造请求。凭据不落手机本地。
4. **演示模式**：桌面浏览器预览或无服务端时，内置示例数据驱动全部页面，便于验收 UI。

## 四、明确未覆盖的部分

- 桌面端「一键切号写本机 auth 文件」在手机上只能**发指令**，前提是电脑上的服务端在运行；
  手机本身不持有客户端配置文件。
- Token 用量趋势、会话副本树、网关 Key 管理、模型探测等页面未搬到手机（数据量大、
  交互复杂，且更适合桌面宽屏）。相关 `/api/*` 路由仍在 [API-CONTRACT.md](API-CONTRACT.md)
  中列出，后续可增量接入。
- 本 APK 不含任何上游平台的登录 SDK 或私有接口调用，所有上游请求都由服务端发起。
