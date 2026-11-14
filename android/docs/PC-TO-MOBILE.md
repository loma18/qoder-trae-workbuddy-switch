# 架构演进：从「必须依赖电脑」到「手机完全独立」（v2.1 三平台）

## 一段需要纠正的判断

v1.x 的结论是：**手机无法独立登录，只能当电脑端 `buddy-switch-server` 的客户端**。
我上一轮回答还专门列了"三道硬约束"（设备私钥、`127.0.0.1:17388` 回环回调、进程内会话状态），
论证手机必须依赖电脑。

**这个结论对 `qoder-trae-workbuddy-switch` 那套实现成立，但对 WorkBuddy 整体不成立。**
我把"某一个仓库的实现限制"当成了"上游协议的限制"，这是判断错误。
用户指出 [fewards](https://github.com/loma18/fewards) 与
[workbuddy-console](https://github.com/loma18/workbuddy-console) 早已跑通手机独立登录与签到，
核实源码后确认：**上游存在另一套完全不同的授权协议**。

## 两套协议并存

| | `qoder-trae-workbuddy-switch`（Tauri 桌面端） | `fewards` / `workbuddy-console` |
|:--|:--|:--|
| 项目形态 | Rust core + React，Tauri | Kotlin 原生 Android + Electron/Node |
| 登录机制 | 桌面客户端 OAuth：设备私钥来自 `icube::device_identity_for(variant)`，回环 `127.0.0.1:17388` 监听，授权页从浏览器侧探测端口 | **官方 `/v2/plugin/auth/*` 扫码协议**：`auth/state` → 轮询 `auth/token` → `login/account` |
| 凭据落点 | 写进电脑本地 `~/.buddy-switch/` 账号库 | **手机本地 SharedPreferences** |
| 手机能否独立 | ❌ 结构上不行（无设备私钥、回调打不到电脑） | ✅ 生产已在跑 |
| 设备身份 | 绑死桌面客户端 `storage.json` | 不需要，纯 HTTP 信封 |
| 电脑的角色 | 必需（服务端 + 账号库 + 网关） | **可选**（只用于同步账号） |

关键在于：**`fewards` 用的根本不是 Tauri 版那套 OAuth**。
`fewards/AGENTS.md` 明确写着扫码授权"移植自 workbuddy-manager 的 `server/services/tencent.py`"，
走的是腾讯 CodeBuddy 插件的 `/v2/plugin/auth/*` 端点，返回 `state` + `authUrl`，
轮询拿到 `accessToken/refreshToken` 后直接落手机本地库。
`authUrl` 是普通授权链接，手机浏览器打开即可完成确认，没有任何回环监听。

所以 v1.x 那三道约束**不是物理限制，是选错了协议**。

## v2.0 怎么做（WorkBuddy 独立版）

按用户要求：**手机独立登录、独立签到领积分，同步到电脑是可选的**。

1. 网络目标从「局域网 `buddy-switch-server`」换成「腾讯官方 API」：
   `copilot.tencent.com` / `www.codebuddy.cn` / `www.workbuddy.ai`。
2. 账号库从「电脑端账号库」换成「手机本地 `AccountStore`」。
3. 登录从「给电脑发 OAuth 指令」换成「手机自己跑扫码三步」。
4. 签到/领积分从「电脑端执行」换成「手机直连 `daily-checkin` + 成长中心」。
5. 同步降级为设置项里的一个**手动、可选**动作，用 workbuddy-console 已有的
   `lansync` 协议（`/api/lansync/push|fetch|ping`），不点就完全不碰电脑。

WebView 里的 JS 无法伪造 `Origin` / `Referer` / `User-Agent` / `X-Requested-With`
指纹头，也过不了 CORS，所以所有官方请求由 Java 层 `Http.java` 发出（这点沿用 v1.x 的桥接架构，
构建方式也沿用纯 build-tools 手工构建——沙箱里没有 Gradle，用不了 fewards 的 Compose/Miuix）。

## v2.1 怎么做（补齐 Qoder / Trae）

用户要求「需要支持 trae 和 qoder」。重新逐文件核对 dev 分支 Rust core 后，结论是
**两个平台的可行边界不同，不能一刀切**：

| 平台 | 独立登录可行性 | 依据 | 本 App 的落地方式 |
|:--|:--|:--|:--|
| **Qoder** | ✅ 可以 | `qoder/oauth.rs::login_start_for` **不发任何网络请求**，只在本地生成 `machine_id`（缺失时自造 UUID 持久化）、PKCE verifier/challenge、nonce 和 `{website}/device/selectAccounts?...` 授权 URL；随后纯 HTTP 轮询 `GET /api/v1/deviceToken/poll`、`GET /api/v1/userinfo`。无设备私钥、无本地回环。 | 新增 `QoderApi.java`，完整复用手机独立扫码登录；再按 `api.rs::openapi_headers` 构造 `X-Machine-ID` / `X-Session-ID` 等头，实现签到与额度查询 |
| **Trae** | ❌ 不行 | `trae/oauth.rs` 的授权 URL 必须携带从桌面客户端 `storage.json` **解密出的 `privateKeyPEM` / `deviceId`**，且授权页主动探测 `127.0.0.1:17388`；手机上两者都不存在。 | 不做假扫码。但 `trae/checkin.rs` / `trae/credits.rs` 证明签到与积分只校验 **JWT + 本机自造设备头**，所以新增 `TraeApi.java` 支持「粘贴 `Cloud-IDE-JWT …` 导入」，导入后手机即可长期独立签到、领积分、查余额 |

新增/改动的层次：

- `Platforms.java`：三平台 × 两区域能力矩阵（历史账号无 `platform` 字段 → 归为 `wb`，零迁移）。
- `AccountStore.java`：`makeFor(platform, uid, label, token, variant)`，id 前缀按平台 + 档位隔离去重。
- `PlatformRouter.java`：`JsBridge` 与 `AutoCheckinReceiver` 统一走这一层分派，避免两处各写一套判断。
- `JsBridge.java`：`loginStart` / `loginPoll` / `loginCancel` / `addManual` 按平台分派；
  新增 `platforms` action 返回能力表；`credits` / `refresh` / `checkin` / `growth` 改走 `PlatformRouter`。
- `AutoCheckinReceiver.java`：定时签到遍历全部平台账号，结果带 `platformLabel`。
- `assets/index.html`：统计卡 4 → 5 张、账号页平台筛选、添加账号弹窗平台选择、
  Trae 隐藏「发起登录」改为「粘贴 Trae JWT」。

**关键取舍**：没有为了"看起来支持"而伪造 Trae 设备私钥或假造回环端口——那样上游一定不通过，
只会让用户白等。改为如实说明边界，给出「导出一次 JWT → 手机长期独立」这条真正能跑通的路径。
用户最初要求「手机单独登录、同步可选」的方向在 WorkBuddy / Qoder 上完整实现，
Trae 则在上游允许的范围内做到独立签到与查积分。

## 参考实现 → 本 App 的映射

| 参考实现 | 本 App |
|:--|:--|
| `core/workbuddy/WorkBuddyVariant.kt` · `server/workbuddy/variant.mjs` | `WbVariant.java` |
| `core/workbuddy/WorkBuddyLogin.kt` | `WorkBuddyApi.loginStart/loginPoll` |
| `core/workbuddy/WorkBuddyEngine.kt` | `WorkBuddyApi.checkinOne` |
| `core/workbuddy/WorkBuddyGrowth.kt` | `WorkBuddyApi.growthOne`（任务接单/领奖 + Buddy 旅行） |
| `core/workbuddy/WorkBuddyCredits.kt` | `WorkBuddyApi.creditsOne` |
| `core/workbuddy/WorkBuddyTokenRefresh.kt` | `WorkBuddyApi.refreshOne` |
| `core/workbuddy/WorkBuddyLabel.kt`（JWT 解 profile） | `AccountStore.decodeJwt` |
| `data/repository/AccountRepository.kt` | `AccountStore.java` |
| `core/lansync/LanSyncProtocol.kt` · `LanSyncClient.kt` | `WorkBuddyApi.syncPing/syncPush/syncFetch` |
| `server/lansync/server.mjs` 的 5 个路由 | 「同步」页（可选） |
| `work/TaskWorker.kt` + 控制中心磁贴 | `AutoCheckinReceiver` + AlarmManager |
| `qoder/oauth.rs` · `qoder/region.rs` | `QoderApi.loginStart/loginPoll` |
| `qoder/api.rs::openapi_headers` · `qoder/device.rs::derive_id` | `QoderApi.openapiHeaders` |
| `qoder/api.rs`（check-in / quota） | `QoderApi.checkinOne/creditsOne` |
| `trae/jwt.rs::normalize` · `trae/credits.rs::build_headers` | `TraeApi.addManual` / `buildHeaders` |
| `trae/checkin.rs` · `trae/credits.rs::parse_credit_packages` | `TraeApi.checkinOne/creditsOne` |
| （新）三平台能力矩阵与分派 | `Platforms.java` · `PlatformRouter.java` |

## 仍然存在的边界

- **国际版（WorkBuddy AI）没有签到 / 成长中心接口**：这是上游事实，不是 App 的限制。
  `supportsCheckin / supportsGrowth = false`，App 显式跳过并在结果里说明跳过了几个。
- **成长中心只移植了主体**：任务接单/领奖 + Buddy 旅行。fewards 还有连签补登日历、
  兑换码、抽奖、能量/热力图等模块，未搬入（避免误消耗补登卡等有副作用的动作）。
- **同步是单向可裁决的，且只覆盖 WorkBuddy**：refresh_token 轮换式，两端各持一份时
  一次刷新会让另一份作废。`fetch` 是只读端点，手机端按"失效才补、有效不动"的规则合并。
  Qoder / Trae 凭据模型不同，不接入 lansync。
- **Trae 不能独立扫码 OAuth**：上游要求桌面客户端设备私钥与 `127.0.0.1:17388` 回环探测。
  App 提供「粘贴 JWT 导入」，导入后签到/查积分完全独立；JWT 过期需从桌面客户端重新导出一次。
- **Qoder / Trae 不做手机端凭据刷新**：上游没有对应的移动端刷新端点，App 返回跳过提示，
  过期后重新粘贴 / 重新导出。

逐端点契约见 [API-CONTRACT.md](API-CONTRACT.md)，故障排查见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
