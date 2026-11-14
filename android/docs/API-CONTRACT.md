# 官方 API 契约（v2.2.0 · WorkBuddy / Qoder / Trae 三平台独立版）

> 本 App 的 Java 层逐条实现以下端点，全部由手机直连官方域名，**不经过电脑**。
> - WorkBuddy：来自 [fewards](https://github.com/loma18/fewards) `core/workbuddy/*` / `core/lansync/*`，
>   与 [workbuddy-console](https://github.com/loma18/workbuddy-console) `server/workbuddy/*` / `server/lansync/*` 交叉核对。
> - Qoder：来自 `qoder-trae-workbuddy-switch` dev 分支 `buddy-switch-core/src/modules/qoder/{oauth,region,api,device}.rs`。
> - Trae：来自同仓库 `modules/trae/{jwt,credits,checkin}.rs`。
>
> 所有请求由 `Http.java` 的 `HttpsURLConnection` 发出（`Connection: close`），因为 WebView 内 JS
> 无法伪造 `Origin` / `Referer` / `User-Agent` / `X-Requested-With`，且受 CORS 限制。
> v1.x 的 `buddy-switch-server` `/api/*` 局域网路由已整体废弃。

## 0. 三平台能力矩阵（`Platforms.java` / `PlatformRouter.java`）

| 平台 | 扫码登录 | 每日签到 | 成长中心 | 查积分 | 手机独立 | 电脑角色 |
|:--|:--:|:--:|:--:|:--:|:--:|:--|
| WorkBuddy 国内版 `wb/cn` | ✅ `/v2/plugin/auth/*` | ✅ | ✅ | ✅ | ✅ | 可选（lansync 同步） |
| WorkBuddy 国际版 `wb/ai` | ✅ 同协议换域名 | ❌ 上游无接口 | ❌ | ✅ | ✅ | 可选 |
| Qoder 国内版 `qoder/cn` | ✅ PKCE 设备授权 | ✅ 签到即领积分 | ❌ | ✅ 额度 | ✅ | 不需要 |
| Qoder 国际版 `qoder/ai` | ✅ | ✅ | ❌ | ✅ | ✅ | 不需要 |
| Trae 国内版 `trae/cn` | ❌ 上游限制 | ✅ 粘贴 JWT 后 | ❌ | ✅ | ✅（JWT 导入后） | 仅首次导出 JWT |
| Trae 国际版 `trae/ai` | ❌ | ⚠️ 见 §三·4.1 | ❌ | ⚠️ 见 §三·4.1 | ✅（JWT 导入后） | 仅首次导出 JWT |

> **Trae 国际版（v2.1.2 修正）**：签到 / 积分请求统一发往 `api.trae.cn`（与国内版同网关），
> 但国内 / 国际是两套账号体系，上游可能拒绝国际版 JWT。此时按三档语义如实返回
> 「未开放」或「鉴权被拒」诊断，不虚报成功。详见 [三、Trae](#三traetraeapijava) 的 4.1 节。

分派规则：`PlatformRouter.checkinOne/creditsOne/growthOne/refreshOne` 按账号的 `platform` 字段
路由到 `WorkBuddyApi` / `QoderApi` / `TraeApi`。历史账号无 `platform` 字段时 `Platforms.of()`
一律归为 `wb`，**升级后旧账号行为完全不变**。`JsBridge` 与 `AutoCheckinReceiver` 都只调
`PlatformRouter`，避免两处各写一套平台判断。

---

# 一、WorkBuddy（`WbVariant.java` / `WorkBuddyApi.java`）

## 1. 两档位

两档是**两套独立客户端**、两套域名、两套登录态，**绝不跨档请求**：国际版 token 打国内域只会 401。

| | `cn` 国内版 | `ai` 国际版 |
|:--|:--|:--|
| 客户端名 | WorkBuddy | WorkBuddy AI |
| `apiEndpoint`（积分/成长/Origin/Referer） | `https://www.codebuddy.cn` | `https://www.workbuddy.ai` |
| `chatBase`（登录/刷新） | `https://copilot.tencent.com` | `https://www.workbuddy.ai` |
| 扫码 `platform` | `CLI` | `workbuddy-ai` |
| UA 平台名 | `WorkBuddy` | `WorkBuddy AI` |
| 签到接口 | ✅ | ❌ 显式跳过 |
| 成长中心 | ✅ | ❌ 显式跳过 |

归一化：`ai` / `workbuddy-ai` / `intl` → `ai`，其余（含缺失）→ `cn`（历史数据零迁移）。
账号 id 在国际版带前缀 `wb_ai_<uid>`，避免两档同 uid 互相覆盖。

## 2. 扫码登录（纯 HTTP，手机独立完成）

出站指纹头（两档共用，仅 Origin/Referer 随档位变）：

```
Content-Type: application/json
Accept: application/json, text/plain, */*
X-Requested-With: XMLHttpRequest
User-Agent: CLI/2.63.2 CodeBuddy/2.63.2
Origin: {apiEndpoint}
Referer: {apiEndpoint}/
```

| 步 | 请求 | 响应 / 判定 |
|:--|:--|:--|
| ① 发起 | `POST {chatBase}/v2/plugin/auth/state?platform={oauthPlatform}`，body `{}` | `data{state, authUrl}`；**`authUrl` 即二维码内容，也是可直接在浏览器打开的授权页** |
| ② 轮询 | `GET {chatBase}/v2/plugin/auth/token?state={state}`，每 2 秒 | `code != 0` = 仍在等待（实测未扫码 `11217 "login ing..."`），**不是错误**；`code==0` 时 `data{accessToken, refreshToken, expiresIn, domain}` |
| ③ 账号 | `GET {chatBase}/v2/plugin/login/account?state={state}`，加 `Authorization: Bearer {accessToken}` | `data{uid, nickname, enterpriseId}` |

- 统一信封 `{code, msg, data}`；**判定只看 `code`**，缺 `code` 字段必须算失败。
- 本地会话 TTL **5 分钟**（`STATE_TTL_MS`），轮询全程沿用**发起时的档位**，不跟随界面上后来切换的档位。
- 响应 `domain` 与档位不符时**拒绝入库**（跨档 token 会一直显示"登录失效"）。空 domain 放行。
- 扫码成功后**只纳管、不自动签到**；签到由首页「一键签到并领取积分」或账号页单独触发。
- 昵称兜底：JWT 的 `nickname`（去掉控制字符）→ `preferred_username` → 默认文案。

> 这套协议**不需要**桌面客户端设备私钥，也**没有** `127.0.0.1:17388` 回环回调，
> 因此手机可以独立完成。这是与 `qoder-trae-workbuddy-switch` Tauri 版 OAuth 的根本区别。

## 3. 每日签到（仅国内版）

`POST https://copilot.tencent.com/billing/meter/{checkin-status|daily-checkin}`，body `{}`

```
Content-Type: application/json
Accept: application/json
Authorization: Bearer {access_token}
```

| 情形 | 判定 |
|:--|:--|
| HTTP 401 / 403 | token 已过期，需刷新或重新扫码 |
| 状态接口 2xx 且 `data.today_checked_in == true` | 今日已签，短路返回成功（该字段**不可靠**，仅省一次请求） |
| `daily-checkin` `code == 0` | 领取成功（`data.credit` / `data.streak_days`） |
| `daily-checkin` `code == 10001`（HTTP 400） | **今日已签，视为成功** |
| 其它 | 失败，展示 `msg` |

国际版账号由 `supportsCheckin=false` **显式跳过并返回成功**，不计入失败、不显示红字。

## 4. 成长中心领积分（仅国内版）

`BASE = https://www.codebuddy.cn/v2/activity/growth`

```
Authorization: Bearer {access_token}
X-Client-Platform: web
Content-Type / Accept: application/json
Origin: https://www.codebuddy.cn
Referer: https://www.codebuddy.cn/profile/plans-usage
X-User-Id: {uid}                          （有则带）
X-Enterprise-Id / X-Tenant-Id: {enterpriseId}  （有则带）
```

| 动作 | 端点 | 要点 |
|:--|:--|:--|
| 查任务 | `GET /tasks` | 取 `tasks[]`：`task_code` / `title` / `locked` / `accept_status` / `reward_credit` |
| 接单 | `POST /tasks/accept` | body **`{"task_codes":[...]}`**（复数；单数一律 400），分批 ≤20 |
| 领奖 | `POST /tasks/{code}/claim` | 只有 `accept_status == "completed"` 才发奖；`already_claimed` 跳过 |
| Buddy 旅行 | `GET /buddy/travel/status` → `POST /buddy/travel/claim` | `arrived` 才能领，带可选 `record_id` |

响应字段可能被 `data` / `result` / `resp` / `response` 包裹，需递归查找。
**写操作不重试**（claim / depart 等超时时服务端可能已处理完成）。

## 5. 积分余额

`POST {apiEndpoint}/billing/meter/get-user-resource-summary`，body `{}`，头同上。

- 信封 `code != 0` 视为业务失败；401/403 = 登录态失效。
- `data.Packages[]` 为**权威数额**：`CycleTotalCapacity` / `CycleRemainCapacity` / `CycleUsedCapacity` / `PackageCode` / `CapacityUnit`。
- 明细端点 `get-user-resource-paid-packages` / `get-user-resource-free-packages` 只用于补到期时间，
  **不要把逐笔历史相加**（会与周期口径重复计算）。
- **v2.2.0**：为把「每月赠送的基础 Credits」单独查出来，`enrichFreePackages` 额外 POST
  `get-user-resource-free-packages`（body 带当天 `SlicePeriodStartTime/EndTime` +
  `FREE_PACKAGE_CODES`，对齐 core `credits.rs::free_packages_body`），把免费月度包标为
  `kind:'base'`，summary 的其余包标为 `kind:'addon'`，按 `PackageCode` 去重。
  **汇总 `total/remaining` 仍以 summary 为权威、绝不重算**（两路口径可能重叠），
  只额外给出 `baseTotal/baseRemaining/addonTotal/addonRemaining` 与 `packages[]` 明细；
  接口失败或返回空时退化成按包名（免费 / 赠送 / 月度 / 基础 / `free`）识别，并在
  `baseHint` 如实说明「未取到独立明细」，绝不假装查到了。
- 包到期取「还有额度的那几笔里最早的」；`DeductionEndTime` 比 `CycleEndTime` 晚整 30 天视为长期占位。
- 汇总接口不含到期字段时，界面如实显示「未知」，不要伪造 1970。

## 6. 凭据刷新（轮换式）

`POST {chatBase}/v2/plugin/auth/token/refresh`，body `{}`

```
...登录指纹头（UA 改为 WorkBuddy/5.5.4 {uaPlatform}/5.5.4 CLI/2.137.1）...
X-CodeBuddy-Request: 1
X-Refresh-Token: {refresh_token}      ← 只允许出现在这个头里
X-Auth-Refresh-Source: plugin
X-User-Id: {uid}
```

响应兼容 `{code,data}` 信封与裸对象，字段名兼容 `accessToken`/`access_token`、
`refreshToken`/`refresh_token`、`expiresIn`/`expires_in`、`refreshExpiresIn`/`refresh_expires_in`。

> **refresh_token 是轮换式的**：刷新成功后旧值立刻作废。手机和电脑各持一份时，
> 一次刷新就会让另一份失效。排查"这台能用、那台掉登录"时要分别看两边的 access/refresh 到期时间。

---

# 二、Qoder（`QoderApi.java`）

设备授权是**纯 HTTP**（PKCE + 轮询），`machine_id` 缺失时自造 UUID 并持久化，
全程无本地回环、无桌面客户端设备私钥，**手机可独立登录**。

## 1. 两区域常量表

| | `cn` 国内版 | `ai` 国际版 |
|:--|:--|:--|
| `website`（授权页 / Origin / Referer） | `https://qoder.com.cn` | `https://qoder.com` |
| `openapi`（业务接口） | `https://openapi.qoder.com.cn` | `https://openapi.qoder.sh` |
| `client_id` | `1c5e33e1-364d-4ce6-b02c-acaa81274a5c` | `e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb` |
| `User-Agent` | `QoderWork/1.1.64` | `Qoder/1.1.64` |
| `nonce` 形态 | 带连字符 UUID | 去连字符 32 位 |
| `redirect_uri` | `qoder-work-cn://`（授权 URL 必带） | 不带 |

归一化：只有 `"ai"` → `ai`，其余 → `cn`。`machine_id` 按档位存 `Prefs["qoder.machine." + v]`，
首次随机生成后**长期复用**（上游按机器身份做风控）。

## 2. 设备授权登录

### ① 发起（`loginStart`，不发任何网络请求）

本地生成 64 字符 PKCE `verifier`（字母表 `A-Za-z0-9-._~`）、`challenge = BASE64URL(SHA256(verifier))`、
`nonce`，构造授权页 URL：

```
{website}/device/selectAccounts
    ?challenge={challenge}
    &challenge_method=S256
    &nonce={nonce}
    &client_id={client_id}
    &machine_id={machine_id}
    [&redirect_uri=qoder-work-cn://]     ← 仅国内版
```

`authUrl` 即二维码内容，也可直接在手机浏览器打开。会话 TTL 5 分钟（`TTL_MS`），
`state` 前缀固定为 `qoder_`（`JsBridge` 据此把轮询分派到 Qoder）。

### ② 轮询换 token（`loginPoll`）

```
GET {openapi}/api/v1/deviceToken/poll
    ?nonce={nonce}&verifier={verifier}&challenge_method=S256
Accept: application/json, text/plain, */*
User-Agent: {ua(variant)}
```

| HTTP / 响应 | 判定 |
|:--|:--|
| 网络异常 | `status=pending`，可重试 |
| `202` / `404` | **仍在等待扫码 / 确认授权**，不是错误 |
| 2xx 但无 `token` 字段 | 授权处理中，继续轮询 |
| 2xx 且拿到 token | 进入 ③ |

token 字段名兼容 `token` / `device_token` / `access_token` / `accessToken`；
`expires_in` 有则换算为 `expiresAt`（秒），否则默认 1 小时。

### ③ 取账号身份

```
GET {openapi}/api/v1/userinfo
Authorization: Bearer {token}
```

取 `id` → `uid`，`name` / `nickname` / `email` → 昵称，落 `AccountStore.makeFor("qoder", ...)`。

> **Qoder 不做手机端 token 刷新**：`refreshOne()` 返回「请在电脑端刷新后重新粘贴导入」的跳过提示。

## 3. 业务请求头（`openapiHeaders`，对齐 `api.rs`）

```
Content-Type: application/json
Accept: application/json, text/plain, */*
User-Agent: {ua(variant)}
Authorization: Bearer {token}
X-Request-ID: md5("req:"+uid)[0:24] + "-" + 6位时间尾数
X-Machine-ID: md5("machine:"+uid)      ← device.rs::derive_id，按 uid 稳定派生
X-Session-ID: md5("session:"+uid)
Origin: {website}
Referer: {website}/
```

`uid` 为空时用 `anonymous` 兜底，避免生成空串机器码。

## 4. 每日签到 / 领积分（**活动平台优先**，v2.1.1 修正）

> ⚠️ **v2.1.0 的缺陷**：只调用旧 `/sash/api/v1/me/daily-check-in/*`，
> 遇 404/405/410 就返回 `skipped`「该区域暂未开放」——这就是用户看到的「跳过」。
> 而 Rust core `qoder/checkin.rs` 模块头明确写着：
>
> | 通道 | 端点 | 现状 |
> |:--|:--|:--|
> | **活动平台（主力）** | `GET /sash/api/v1/me/campaigns` + `POST …/{id}/claim` | 官方现行机制，「每日领取 100 Credits」在这里 |
> | 旧签到通道（兜底） | `/sash/api/v1/me/daily-check-in/*` | 实测**国内版 `DISABLED`、国际版 404**，只在仍开放时可用 |
>
> ⇒ 顺序固定：**先活动平台**；只有它**整区不可用**（404/405/410）时才回落旧通道，
> 且按 issue #66 先原样重发一次确认（一次 404 可能只是网关抖动）。旧通道返回
> `DISABLED` 也**不**再回退——那是「活动关着」，不是「通道不存在」。

### 4.1 活动平台请求头（`desktopHeaders`，对齐 `campaign.rs::desktop_headers`）

> 与第 3 节 `openapiHeaders` **不同**，别混用。

```
Content-Type: application/json
Accept: application/json, text/plain, */*
User-Agent: Qoder                       ← 不是 QoderWork/1.1.64
Authorization: Bearer {token}
X-Request-ID: md5("req:"+uid) + "-" + 6位尾数
X-Machine-ID: md5("machine:"+uid)
X-Session-ID: md5("session:"+uid)
Origin: {website}
Referer: {website}/
cosy-clienttype: 10                    ← 桌面端身份
cosy-version: 0.4.3
```

> ★ **刻意不发 `cosy-machine*` 头**：手机没有官方原生机器身份，发派生假头会被服务端
> 判定为非官方客户端，把 `CLAIMABLE` 的「每日领取 100 Credits」**整条过滤掉**，比不发更糟。
> 国际版服务端可能要求真实机器身份，此时活动列表可能不可见，属已知上游限制。
>
> ★ **`pt-` 开头的 PAT 不能用于活动平台**（`campaign.rs::access_token_for`），
> 只能查额度。签到必须扫码登录取 access token。

### 4.2 查活动列表

`GET {openapi}/sash/api/v1/me/campaigns`

| 响应 | 判定 |
|:--|:--|
| `404/405/410` | 整区没有活动平台 → 重发一次确认 → 仍如此才回落旧通道 |
| 其它非 2xx | 失败，展示 HTTP 状态 |
| 2xx | 取 `campaigns[]`（也可能包在 `data` 下）；缺数组即失败 |

单条活动字段（兼容 camelCase / snake_case）：

| 字段 | 说明 |
|:--|:--|
| `campaignId` / `campaignKey` | 领取路径用的 id；两者至少有一个 |
| `claimStatus` | `CLAIMABLE` / `CLAIMED` / 其它 |
| `actionType` | `CLAIM_BENEFIT` / `VIEW_DETAILS` / 空 |
| `benefit.kind` | **`CREDITS` 或空** = 积分类（空 kind 是老活动，实测「每日领取 100 Credits」就是空） |
| `benefit.amount` | 奖励额度，可能是浮点 `100.0` |
| `unavailableReason` | `REDEMPTION_CODE_OUT_OF_STOCK` / `ACHIEVEMENT_NOT_COMPLETED` / … |
| `achievementCompleted` | 新人任务是否完成 |
| `placements[].content.zh.title` | 中文标题 → 英文 → campaignKey → id 兜底 |

**归类顺序（`campaign.rs::classify`，★ 不可调换）**：先判 `CLAIMED` 再过滤 `actionType`
—— 上游领取后会把 `actionType` 降级为 `VIEW_DETAILS`，先过滤就会把「已领」误判成
「暂无可领活动」。

| 归类 | 判据 | 处置 |
|:--|:--|:--|
| Already | `CLAIMED` 且（amount>0 或 actionable） | 今日已领，成功档 |
| View | 非 actionable / `CLAIMED` 但无奖励 | 跳过，不参与领取 |
| Pending | 非 `CLAIMABLE` 且 reason=`REDEMPTION_CODE_OUT_OF_STOCK` | 名额发完 |
| Locked | reason=`ACHIEVEMENT_NOT_COMPLETED` 或 `achievementCompleted=false` | 需先完成新人任务 |
| Claimable | `CLAIMABLE` 且 actionable | 发起领取 |

### 4.3 领取

`POST {openapi}/sash/api/v1/me/campaigns/{id}/claim`，body `{}`，头同 4.1。
官方幂等；相邻两次 claim 间隔 ≥1 秒防风控。

| 响应 | 判定 |
|:--|:--|
| HTTP `409` | 今日已领（幂等确认），成功档 |
| 非 2xx 且 `errorCode` = `ALREADY_CLAIMED` / `REPLAYED` | 今日已领，成功档 |
| 其它非 2xx | 失败 |
| 2xx `failureCode=SAME_PERSON_ALREADY_CLAIMED` 或 `status=BLOCKED` | 同人已领（同机其它账号本轮领过），不算失败也不算成功 |
| 2xx `success=false` 且无 status | 失败，展示 `error` |
| 2xx `status` ∈ `CLAIMED`/`GRANTED`/`SUCCESS` | 领取成功，累计 `benefit.amount` |
| 2xx `replayed=true` | 今日已领 |

已知失败码（`FAILURE_REASONS`，**仅用于展示，判据永远是 claimStatus/actionType**）：
`REDEMPTION_CODE_OUT_OF_STOCK`（名额发完）/ `ACHIEVEMENT_NOT_COMPLETED`（新人任务）/
`CAMPAIGN_NOT_ACTIVE`（活动已结束）/ `RISK_BLOCKED`（风控）/ `RISK_DEPENDENCY_UNAVAILABLE`。

### 4.4 三档语义（★ 红线，不许回退）

| 档 | 判据 | 出口 |
|:--|:--|:--|
| 成功 | 真领到 / 今日已领 | `ok:true` |
| **未开放** | 列表里**根本没有积分类活动**，或整区无活动平台 | `ok:false` + `disabled:true` |
| 失败 | 上游报错 / 网络失败 | `ok:false` |

> 把「未开放」写成 `ok:true` 会让界面显示「N 个成功」而额度纹丝不动——
> 这是 Rust core `de932d7` 修掉的缺陷（issue #46-D / #58 §6）。
> 前端把 `disabled` 单独渲染为「未开放」（警示色），`JsBridge` 也不把它计入成功或失败数。

返回明细：`claimed` / `already` / `blocked` / `pending` / `locked` / `earned` /
`creditsGained` / `message` / `parts`（逐条活动文案）/ `nextAvailableNote`。
**上游每日实例在 10:00（UTC+8）切换**，窗口 10:00 → 次日 09:59。

### 4.5 旧通道兜底

仅活动平台整区不可用时走 `GET/POST /sash/api/v1/me/daily-check-in/{status,claim}`：
`status=DISABLED` → `disabled`（**不是 skipped**）；`CLAIMED`/`checked_in` → 今日已签；
`CLAIMABLE` → POST claim。响应 `success=false` 时**复查一次状态**（上游可能先扣后报错）。
`rewardCredits` 可能是 `100.0`，必须按浮点解析，否则「签到成功却报 +0」。

## 5. 额度查询（v2.2.0：拆分「每月基础 Credits」与「资源包」）

`GET {openapi}/api/v2/quota/usage`，响应取 `userQuota` + `addOnQuota` 两个对象，
各自累加 `remaining` / `used` / `total`，单位 `credits`。两者都缺即视为响应异常。

> 上游 `core/qoder/api.rs::parse_quota` 与 `core/qoder/web_usage.rs` 的实测样本说明：
> **`userQuota` = 套餐 / 每月赠送的基础 Credits（PLAN，按 `nextResetAt` 月度重置）**，
> **`addOnQuota` = 赠送 / 加购资源包（BONUS）**。v2.1 只把两者相加给一个笼统合计，
> 用户看不到「每月赠送的基础 credits」，v2.2 起必须分开。

`creditsOne` 返回（新增字段全部向后兼容，旧字段语义不变）：

| 字段 | 说明 |
|:--|:--|
| `total` / `used` / `remaining` / `unit` | 聚合值（= 基础 + 资源包） |
| `baseTotal` / `baseRemaining` | 每月赠送基础 Credits（`userQuota`） |
| `addonTotal` / `addonRemaining` | 资源包 / 加购（`addOnQuota`） |
| `packages[]` | `{name, code, kind:'base'\|'addon', total, used, remaining, expireAt}` |
| `source` | `quota/usage`（主通道）或 `web-usage`（拿到网页端逐包明细） |
| `nextResetAt` / `lastResetAt` / `planName` | 可选，来自网页端逐包接口 |

**附加通道（尽力而为，绝不拖累主结果）**：
`GET {website}/api/v2/me/usages/big_model_credits`（core `web_usage.rs` 的
`PATH_WEB_USAGE_CREDITS`），返回 `plan_quota` / `resource_package_quota` /
`dedicated_resource_package_quota` 三个分组，各带 `quota_summary`
（`limit_value` / `used_value` / `remaining_value`）与 `quota_detail[]`
（`source: PLAN | RESOURCE_PACKAGE_SOURCE_BONUS`、`expires_at` 秒或毫秒混用、
`status: ACTIVE | EXPIRED`），以及 `lastResetAt` / `nextResetAt`。

- 该接口**只认浏览器会话 Cookie**（core 实测 Bearer 一律 401），手机只有 Bearer token，
  因此这里用 Bearer **短超时试一次**：拿到合法 JSON 分组就用它覆盖 base/addon 与逐包明细；
  401/403 / SPA HTML fallback / 解析不出分组一律**静默回落** `quota/usage` 的拆分结果，
  `source` 保持 `quota/usage`，绝不把附加请求失败写成 0 或让主查询失败。
- `total_quota` 是**汇总**不是分组，不解析（否则 PLAN 包会重复出现）。
- 过期包（`status=EXPIRED`）不进明细。

---

# 三、Trae（`TraeApi.java`）

## 1. 为什么不做独立扫码登录

`trae/oauth.rs` 的授权 URL 必须携带从桌面客户端 `storage.json` **解密出的 `privateKeyPEM` / `deviceId`**
（`icube::device_identity_for`），且授权页会主动探测 `127.0.0.1:17388` 判断客户端在线。
这两者在手机上都不存在，上游不通过 —— **这是上游协议限制，不是 App 的取舍**。

但 `trae/credits.rs` 与 `trae/checkin.rs` 证明：**签到与积分接口只校验 JWT + 本机自造的设备请求头**。
因此用户从桌面客户端导出一次 `Cloud-IDE-JWT …` 后，手机即可长期独立签到、领积分、查余额，
此后完全不再依赖电脑。`JsBridge.loginStart` 对 Trae 直接返回引导文案，不发起网络请求。

## 2. JWT 导入（`addManual`）

### 网关单一来源（★ v2.1.2 修正，勿回退）

`/trae/api/v2/*` 这套签到 / 积分路由**只挂载在国内官方网关 `https://api.trae.cn`**，
与「国内版 / 国际版」选择无关。国际版 `https://grow-normal.trae.ai` 是**账号 / OAuth 网关**，
只代理 `/trae/api/v3/oauth/*`、`/cloudide/api/v3/*`；打 v2 一律返回 nginx 404。

| 网关 | 角色 | `/trae/api/v2/ug/*`、`/trae/api/v2/pay/*` |
|:--|:--|:--|
| `https://api.trae.cn` | **签到 / 积分 API 网关（主通道，恒用）** | ✅ 挂载（返回业务 JSON） |
| `https://grow-normal.trae.ai` | 国际版账号 / OAuth 网关（仅回落候选） | ❌ nginx 404（路由不存在） |

> 依据：`credits.rs::post_json` → `post_json_for(TraeVariant::default())` 固定取
> `endpoints_for(..).account_base`；`mod.rs::endpoints_for` 只返回 `cn_endpoints`，
> `global_endpoints`（`grow-normal.trae.ai`）从未进入真实签到 / 积分请求。
> `model_list_upstream.rs` 亦明确「国际版没有实测证据能走同一个路径…不猜一个域名」。
> 实测（匿名 POST）：`api.trae.cn` 返回 `code:1001` 业务 JSON；`grow-normal.trae.ai`
> / `api.trae.ai` / `icube-normal.trae.ai` / `core-normal.trae.ai` 全为 nginx HTML 404；
> `www.trae.ai` 的 200 是 `text/html` SPA 首页 fallback，不是 API。

因此 `bases(variant)`：**主通道恒为 `api.trae.cn`**；`ai` 档额外挂账号网关作回落候选。
`variant` 只用于账号标签、诊断文案与回落顺序，**不决定主 API 网关**。
`domain` 字段记录主通道 `api.trae.cn`。

- `normalize()` 剥掉 `Cloud-IDE-JWT ` / `Bearer ` 前缀与首尾空白。
- 解 JWT payload（Base64URL → 补 `=` → decode）：`data.id` → `auth_id` → `sub` → `user_id` → `uid` 取 userId；
  `nickname` / `name` / `email` 取昵称，兜底「Trae 账号」；`exp` → `expiresAt`（秒）。
- 找不到 userId 直接拒绝导入（后续接口强依赖 uid 派生设备头）。

## 3. 请求头（`buildHeaders`，对齐 `credits.rs::build_headers`）

> 源码注释标注「线上实测契约，不要顺手改」。

```
accept: */*
accept-language: zh-CN
authorization: Cloud-IDE-JWT <jwt>        ← 已带前缀则原样使用
content-type: application/json
user-agent: VSCode 1.107.1 (TRAE SOLO CN)
x-market-client-id: VSCode 1.107.1
x-market-user-id: {device.marketUserId}
x-user-region: CN
x-device-id: {device.deviceId}            ← 15 位数字
x-lgw-req-sdk-type: 3
package-type: stable_cn
x-request-id: {uuid}
x-lscbd-aid: 787976
x-lscbd-platform: linux
app-version: 0.1.45
x-tt-trace-id: 00-{16位hex}-01
vscode-sessionid: {device.sessionId}
sec-fetch-dest: empty
sec-fetch-mode: no-cors
sec-fetch-site: none
```

**设备身份按 uid 稳定派生并持久化**（`Prefs["trae.dev." + md5(uid)]`）：`deviceId` / `marketUserId` /
`sessionId` 首次随机生成后不再变化。上游按机器身份做风控，同一账号每次换设备头会被关联或限流。

## 4. 签到领积分

| 动作 | 端点（{base} = `api.trae.cn` 主通道） | 要点 |
|:--|:--|:--|
| 查状态 | `POST {base}/trae/api/v2/ug/checkin_credits/status`，body `{}` | `checked_in` / `checkedIn` = 今日已签 |
| 领取 | `POST {base}/trae/api/v2/ug/checkin_credits/claim`，body `{}` | `code != 0` 且 `message` 非 `success`/空 → 失败；含「已签」/`already` 视为今日已签 |

### 4.1 多网关自适应与三档语义（`postAny`，v2.1.2）

`postAny(variant, path, headers, body, readMs)` 依次尝试 `bases(variant)`：

1. **网关级「路由不存在」判定**：`HTTP 404` 且响应体是 nginx HTML / `404 Page not found` /
   空体（非 `{…}` JSON）→ 该网关没挂这套路由，`continue` 换下一个。
   业务 JSON 的 4xx **不算**路由缺失，必须交上层解析。
2. 全部候选都没有路由 → `noRoute()` 返回 `{ok:false, disabled:true, message, detail}`，
   前端显示「**未开放**」，既不计成功也不计失败。
3. 拿到业务响应后先过 `isAuthReject()`：`HTTP 401/403`、`code:1001/401/403`、
   或 `not able to authenticate you` / `unauthorized` / `invalid token` 等文本
   → `authRejected()` 给出可操作诊断（上游在国内网关用 **HTTP 200 + code:1001** 表达鉴权失败，
   不是 401，所以不能只看状态码）。
4. 其余按业务码正常解析成功 / 今日已签 / 失败。

> **红线**：国际版账号与国内是两套账号体系（`region.rs`：「JWT 不通用」）。
> 国际版 JWT 打国内网关被拒时**绝不伪装成成功**，也不把网关 404 直接抛给用户。

## 5. 积分查询

`POST {base}/trae/api/v2/pay/ide_user_ent_usage`，body `{"require_usage":true,"full_data":true}`，
同样经 `postAny` 多网关自适应 + `isAuthReject` 诊断。

响应 `user_entitlement_pack_list[]`（也可能包在 `data` 下），逐包取
`entitlement_base_info.quota.credits_limit` 减 `usage.credits_amount` 得剩余；
聚合 `total` / `used` / `remaining`，到期取还有额度的包里最早的 `expire_time`（秒，输出时 ×1000）。

> **v2.2.0**：包名在 **pack 顶层** `group_name` / `display_desc`
> （core `trae/credits.rs` 线上实测，`entitlement_base_info` 里只有 quota 与时间字段），
> 旧版只从 `info` 取会拿到空名。现按 `group_name → display_desc → package_name → name` 回退，
> 并按名称含「月度 / 月卡 / 基础 / 免费 / 赠送」标为 `kind:'base'`（每月赠送基础 Credits），
> 其余标 `kind:'addon'`（资源包），同时给出 `baseTotal/baseRemaining/addonTotal/addonRemaining`。

> Trae / Qoder 都**不提供手机端刷新**：`refreshOne()` 返回跳过提示，JWT / token 过期后需重新导出粘贴。

---

# 四、可选：同步到电脑（lansync，仅 WorkBuddy）

`http://{局域网IP}:57900/api/lansync/*`，除 ping 外均需 `Authorization: Bearer {配对码}`。
对齐 workbuddy-console `server/lansync/server.mjs` 与 fewards `LanSyncProtocol.kt`。
**Qoder / Trae 账号不参与 lansync**（凭据模型不同）。

| 方法 | 路径 | 用途 |
|:--|:--|:--|
| GET | `/api/lansync/ping` | 探测在线 / 是否需配对，不鉴权 |
| POST | `/api/lansync/push` | 手机推账号 → 电脑端合并落库，回传最终凭据 |
| POST | `/api/lansync/fetch` | **只读**取回电脑端凭据（不写电脑端账号库） |
| GET | `/api/lansync/pending` | 拉"电脑刷新过、还没通知手机"的队列 |
| POST | `/api/lansync/ack` | 确认收到，出队 |

push body：

```json
{
  "version": 1,
  "device": {"name": "<手机型号>", "platform": "android"},
  "mode": "merge",
  "accounts": [{
    "uid": "...", "label": "...", "enterpriseId": "...",
    "accessToken": "...", "refreshToken": "...",
    "expiresAt": 1730000000,        // 秒
    "refreshExpiresAt": 1730000000, // 秒
    "obtainedAt": 1730000000000,    // 毫秒
    "version": 0,
    "variant": "cn"                 // cn | ai
  }]
}
```

**单位最容易写错**：`expiresAt` / `refreshExpiresAt` 是**秒**，`obtainedAt` / `refreshedAt` 是**毫秒**。
差 1000 倍不会崩，只会让账号"刚同步完就显示过期"。`version` 由电脑端维护，手机恒发 0。

`fetch` 的合并规则（对齐 fewards 设置页「从电脑同步凭据」）：
- 手机端**已失效**的账号补 token；**没有**的账号直接建号（不必先扫码）；仍有效的**一律不动**；
- 已有账号只写 token 字段（label / 头像不动），加密信封账号（`encrypted=true`）不建号；
- 档位只做 `cn → ai` 一个方向的纠正（历史版本把国际版存成了国内版）。

**安全**：地址只允许私有网段或回环
（`127.x` / `10.x` / `192.168.x` / `172.16–31.x` / `169.254.x` / localhost），
不解析域名，填公网 IP 直接拒绝——token 是长期凭据。

---

# 五、本机账号数据模型（`AccountStore.java`）

App 私有 `SharedPreferences("secure_store")` 的 `sec.wb.accounts`（JSON 数组）。
v2.0 字段与 fewards `AccountRepository.WorkBuddyAccount` 一一对应；**v2.1 新增 `platform`**，
**v2.2 新增 `remark`**（历史数据缺字段按空串处理，零迁移）。

| 字段 | 单位 | 说明 |
|:--|:--|:--|
| `id` / `label` / `uid` / `enterpriseId` / `avatarUrl` | — | 展示与去重 |
| `remark` | — | **v2.2**：用户自定义备注，最多 60 字，只存本机；`AccountStore.setRemark()` 单独写入，刷新 / 同步 / 重新扫码的瘦数据不覆盖（空值时从旧记录保留） |
| `platform` | — | **`wb` / `qoder` / `trae`**；缺省按 `wb`（历史数据零迁移） |
| `variant` | — | `cn` / `ai` |
| `token` | — | access_token / Qoder token / Trae JWT（明文只存本机） |
| `refreshToken` | — | WorkBuddy 轮换式；Qoder / Trae 一般为空 |
| `expiresAt` | **秒** | access 到期；`0 = 未知`，**不能当已过期** |
| `refreshExpiresAt` | **秒** | refresh 到期；`0 = 未知` |
| `obtainedAt` | **毫秒** | 拿到这份凭据的时刻，用于比新旧 |
| `syncVersion` | — | 电脑端维护的单调版本 |
| `domain` | — | Trae 记录签到 / 积分主通道 `api.trae.cn`（v2.1.2 起不再按档位切换） |

`makeFor(platform, uid, label, token, variant)` 生成 id 前缀按平台 + 档位隔离：
WorkBuddy 国内 `wb_` / 国际 `wb_ai_`，Qoder / Trae 为 `{platform}_{variant}_`。
去重顺序：同 `id` → 同 `uid` + `platform` + 档位 → 老账号（uid 空）同 `label` + 档位 → 否则新增；
命中原地替换。**不要退回"只按 id 去重"**。

JWT 解 profile：`nickname`（trim 控制字符）优先，否则 `preferred_username`；`sub` → uid；
`exp` → expiresAt；`picture`/`avatar_url` → avatarUrl。**没有头像接口**，不要接腾讯用户资料 API。

---

# 六、手机端接入状态速查

| 能力 | WorkBuddy | Qoder | Trae |
|:--|:--|:--|:--|
| 扫码登录 | ✅ 官方 `/v2/plugin/auth/*` | ✅ PKCE + `deviceToken/poll` | ❌ 上游需设备私钥 + 本地回环 |
| 手动导入 | ✅ 凭据 JSON | ✅ access token（PAT 不能签到） | ✅ `Cloud-IDE-JWT …` |
| 独立签到 | ✅ 国内版 | ✅ 活动平台 campaigns（v2.1.1） | ✅ 国内版；国际版走 `api.trae.cn` 主通道，被上游拒绝则「未开放 / 鉴权诊断」（v2.1.2） |
| 独立查积分 | ✅ | ✅ 额度 | ✅ 权益包（同上，国际版可能被上游拒绝） |
| 自动签到（`AlarmManager`） | ✅ | ✅ | ✅ |
| 手机端刷新凭据 | ✅ 轮换式 | ❌ 重新扫码 | ❌ 重新导出 |
| 同步到电脑 | ✅ 可选 | — | — |

三者的「一键签到」都经 `PlatformRouter` 汇总。**结果分三档**：
- 不支持的档位（WorkBuddy 国际版、Qoder/Trae 的成长中心）返回 `skipped=true`，显示「跳过」；
- 上游确实没有可领取内容 / 路由时返回 `ok:false + disabled=true`，显示「**未开放**」——
  Qoder 活动列表无积分类活动、Trae 国际版 `api.trae.cn` 也无 v2 路由时都归此档。
  `JsBridge` 单独计 `disabledCount`，不计入成功、也不计入失败，**绝不谎报「N 个成功」**；
- 真领取成功 / 今日已领返回 `ok:true`，并显示逐条活动明细与 `+N Credits`。

> 单个「查积分」也支持 `disabled`：v2.1.2 起 `App.credits` 对非成功结果打开报告页完整展示诊断，
> 不再被 toast 截断成一句「查询失败」。

---

# 七、v2.2.0 新增契约（UI / 本机能力，不涉及上游新端点）

## 7.1 Qoder 去掉「领积分」按钮

账号卡片的「领积分」按钮（→ `growth` → `PlatformRouter.growthOne`）对
**Qoder 国内 / 国际版一律不渲染**。理由：`Platforms.supportsGrowth()` 对 Qoder 恒为 false，
`growthOne` 只会返回 `skipped`「该平台没有成长中心；Qoder / Trae 的签到即领积分」——
签到（`checkin` → 活动平台 campaigns + claim）本身已发放每日 100 Credits，
再放一个永远「跳过」的按钮属于误导。WorkBuddy 国内版保留该按钮。
首页「一键签到并领取积分」是批量动作，不受此约束。

## 7.2 首页「6 个产品」矩阵

`state` 响应新增 `scopes[]`，由 `Platforms.scopes()` 定义，固定 6 项
（3 平台 × 2 档位，顺序 `wb_cn / wb_ai / qoder_cn / qoder_ai / trae_cn / trae_ai`）：

```
{ key, platform, variant, label, variantLabel, scopeLabel,
  checkin, growth, accountCount }
```

- `accountCount` 由 `JsBridge.state()` 用 `Platforms.scopeKey(platform, variant)` 分组统计。
- **汇总积分不落盘**：前端 `renderScopes()` 按同一 `scopeKey` 聚合 `S.credits[id].remaining`，
  只累加 `ok === true` 且 `remaining` 为数值的结果。
  无账号显示 `—`；有账号但没查到显示「**待查询**」；
  部分账号查到时标注 `已查/总数`。**失败 / 未开放绝不算成 0**。
- `reload()` 每次都静默执行一次 `creditsAll`，保证矩阵不是旧值。

## 7.3 账号备注

`JsBridge.call()` 新增 action：

| action | args | 返回 |
|:--|:--|:--|
| `setRemark` | `{id, remark}` | `ok + message` / `ok:false + error` |

→ `AccountStore.setRemark()`：按 `id` 定位，只改 `remark` 字段（trim、上限 60 字），
**绝不触碰 token 等凭据**；`put()` 在瘦数据缺 `remark` 时从旧记录保留；
`publicList()` / `importAll()` / `exportAll()` 均透传该字段。
前端账号卡片显示备注并给出「加备注 / 改备注」弹窗，可一键清除。
