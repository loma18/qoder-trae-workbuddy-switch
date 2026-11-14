# 架构演进：从「必须依赖电脑」到「手机完全独立」（v2.2.0 三平台）

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

## v2.1.1 修正：Qoder 签到「跳过」

用户反馈「qoder 签到提示跳过」。逐文件核对 dev 分支 Rust core 后确认，
这是**移植时挑错了通道**，不是上游限制：

- v2.1.0 的 `QoderApi.checkinOne` 只调用 `/sash/api/v1/me/daily-check-in/{status,claim}`，
  并在 404/405/410 时直接 `skipped("该区域暂未开放每日签到接口")`。
- 而 `qoder/checkin.rs` 模块头明确写着：旧通道**国内版 `DISABLED`、国际版 404**，
  「每日领取 100 Credits」早已迁到**活动平台** `GET /sash/api/v1/me/campaigns` +
  `POST …/{id}/claim`。也就是说，v2.1.0 从没调用过真正能领积分的接口。

修正做法（对齐 `checkin.rs::claim_for` → `campaign.rs::checkin_for`）：

1. 活动平台优先：`desktopHeaders`（`cosy-clienttype=10`、`UA=Qoder`、**不发假机器头**）
   拉活动列表，筛出积分类活动（`benefit.kind` 为 `CREDITS` 或空），
   按 `classify` 逐条领取；相邻 claim 间隔 1 秒。
2. 只有活动平台**整区 404/405/410** 且重发一次仍如此，才回落旧通道
   （issue #66：一次 404 可能只是网关抖动，不能据此断言「本区域没有」）。
3. **三档语义**：成功 / 未开放（`ok:false + disabled:true`）/ 失败。
   把「未开放」写成 `ok:true` 会让界面显示「N 个成功」而额度纹丝不动——
   这是 Rust core `de932d7` 修掉的缺陷，前端与 `JsBridge` 同步改为单独计数与渲染。
4. 拒绝 `pt-` PAT 调用活动平台（上游限制），引导用户扫码登录取 access token。

> 教训与第一节同源：把「某个端点还在」当成「机制没变」，同样会得出错误结论。
> 官方把签到从固定端点迁到**活动列表驱动**后，客户端必须跟着读列表，不能继续打老路径。

## v2.1.2 修正：Trae 国际版签到 / 查积分 404

用户反馈「trae 国际版签到和查积分都是 404」。核对 dev 分支 Rust core + 线上匿名探测后，
这是**移植时把两类网关混为一谈**，不是上游临时故障：

- v2.1 给 Trae 国际版设了 `base = https://grow-normal.trae.ai`，并把
  `/trae/api/v2/ug/checkin_credits/*`、`/trae/api/v2/pay/ide_user_ent_usage` 拼上去。
- 但实测：`/trae/api/v2/*` **只挂载在国内官方网关 `https://api.trae.cn`**（匿名 POST 返回
  `code:1001` 业务 JSON）；`grow-normal.trae.ai` / `api.trae.ai` / `icube-normal.trae.ai` /
  `core-normal.trae.ai` 上全为 nginx HTML 404；`www.trae.ai` 的 200 是 `text/html`
  SPA 首页 fallback。`grow-normal.trae.ai` 其实是**国际版账号 / OAuth 网关**——
  只有 `/trae/api/v3/oauth/ExchangeToken`、`/cloudide/api/v3/...` 返回 400 业务错误。
- 更关键：参考实现 `credits.rs::post_json` → `post_json_for(TraeVariant::default())`，
  而 `mod.rs::endpoints_for` **只返回 `cn_endpoints`**；`global_endpoints`
  （`grow-normal.trae.ai`）只活在端点表和测试里，**从未进入真实签到 / 积分 HTTP 请求**。
  `model_list_upstream.rs` 亦明确「国际版没有实测证据能走同一个路径…**不猜一个域名**
  （猜错就是把账号 JWT 发给一个陌生主机）」。v2.1 恰恰越过了这条边界。

修正做法：

1. **网关单一来源**：签到 / 积分主通道恒为 `api.trae.cn`，与 `variant` 无关；
   国际账号网关仅作回落候选。`variant` 只影响账号标签、诊断文案与回落顺序。
2. **多网关自适应 `postAny`**：区分「网关级路由不存在」（404 + HTML / `404 Page not found`
   / 空体）与「业务 JSON 4xx」。前者换下一个网关，后者交上层解析。
3. **三档语义复用**：全部候选无路由 → `disabled`「未开放」；鉴权被拒 → 可操作诊断；
   成功 / 今日已签 → 正常。特别注意上游用 **HTTP 200 + `code:1001`
   `not able to authenticate you`** 表达鉴权失败，不能只看状态码。
4. **单体积分查询也走报告页**：`disabled` / 失败的长诊断不再被 toast 截断成「查询失败」。

> **国际版账号的现实边界**：`region.rs` 明确 CN 与国际是两套互不相通的账号体系、JWT 不通用。
> 国际版 JWT 打国内网关可能被拒，App 如实显示「未开放 / 鉴权被拒」，
> 不伪造成功、不把莫名 404 抛给用户。若确属国际版账号，以电脑端 Trae 客户端为准。

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
| `qoder/checkin.rs::claim_for` · `qoder/campaign.rs::checkin_for` | `QoderApi.checkinOne/checkinViaCampaigns`（v2.1.1） |
| `qoder/campaign.rs::{parse_campaigns,classify,desktop_headers,claim_campaign,interpret_claim}` | `QoderApi` 同名逻辑 |
| `qoder/api.rs`（legacy check-in / quota） | `QoderApi.checkinViaLegacy/creditsOne` |
| `trae/jwt.rs::normalize` · `trae/credits.rs::build_headers` | `TraeApi.addManual` / `buildHeaders` |
| `trae/checkin.rs` · `trae/credits.rs::parse_credit_packages` | `TraeApi.checkinOne/creditsOne` |
| `trae/variant.rs::GLOBAL_ENDPOINTS` · `mod.rs::endpoints_for`（只返回 `cn_endpoints`） | `TraeApi.bases`（主通道恒 `api.trae.cn`，国际网关仅回落）（v2.1.2） |
| `trae/credits.rs::post_json_for` 的网关 404 / `code:1001` 分类 | `TraeApi.postAny/isRouteMissing/isAuthReject`（v2.1.2） |
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
- **Trae 国际版账号的签到 / 积分不保证可用**：上游只在 `api.trae.cn` 提供 v2 路由，
  而国内 / 国际是两套账号体系。v2.1.2 起统一走该网关并如实返回「未开放 / 鉴权被拒」，
  不猜测国际版专属域名（会把账号 JWT 发给陌生主机）。
- **Qoder / Trae 不做手机端凭据刷新**：上游没有对应的移动端刷新端点，App 返回跳过提示，
  过期后重新扫码 / 重新导出。
- **Qoder 活动平台依赖上游活动列表**：账号没有积分类活动、活动限时、或国际版要求官方原生
  机器身份时，可能领不到。App 如实显示「未开放」而不是「跳过 / 成功」；`pt-` PAT 不能用于领取。
  上游每日实例在 10:00（UTC+8）切换。

## v2.2.0 产品改进（用户反馈三项）

这一版没有改「手机独立」这条主线，只补齐三个使用层面的缺口：

| 诉求 | 根因 | 改动 |
|:--|:--|:--|
| 去掉 Qoder 国内 / 国际版「领积分」按钮 | `growthOne` 对 Qoder 恒 `skipped`（没有成长中心），签到即通过活动平台领 100 Credits，按钮永远「跳过」 | `accCard()` 按 `platform==='qoder'` 不渲染该按钮；`Platforms.supportsGrowth()` 保持 false 不变 |
| 查积分补「每月赠送的基础 credits」 | v2.1 各平台只给一个聚合 `remaining`，看不出基础额度与资源包各有多少 | `QoderApi` 拆 `userQuota`(PLAN 基础) / `addOnQuota`(BONUS 资源包) 并尽力取网页端逐包分组；`WorkBuddyApi` 补 `get-user-resource-free-packages` 免费月度包；`TraeApi` 按包名分类月度基础 / 资源包。统一输出 `packages[]` 与 `base*` / `addon*` |
| 首页显示 6 个产品的账号数与汇总积分 | 旧首页只有 WB / Qoder / Trae 三个计数 + 一个总积分，分不出国内 / 国际 | `Platforms.scopes()` 定义 6 个产品、`state.scopes[]` 带 `accountCount`，前端按 `scopeKey` 聚合 `S.credits` 得到每产品汇总积分 |
| 账号备注 | 部分账号名是一串无意义字符，无法辨认 | `AccountStore` 加 `remark` 字段与 `setRemark()`；`JsBridge` 加 `setRemark` action；账号卡片显示备注并可编辑 / 清除 |

**诚实性边界沿用全仓纪律**：
- 附加的「每月基础 Credits」接口失败时保留主通道结果并给出 `baseHint`，不假装查到、不写 0。
- 首页矩阵只累加成功查到的余额，查不到显示「待查询」，不把失败 / 未开放算成 0。
- WorkBuddy 的汇总 `total/remaining` 仍以 `get-user-resource-summary` 为权威，
  免费月度包明细只用于分类展示，**不与汇总相加**（避免两路口径重叠导致重复计数）。

`versionCode 7 → 8` / `2.1.2 → 2.2.0`，固定签名指纹不变，可直接覆盖安装。

逐端点契约见 [API-CONTRACT.md](API-CONTRACT.md)，故障排查见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
