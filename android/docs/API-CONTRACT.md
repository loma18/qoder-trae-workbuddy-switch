# 官方 API 契约（v2.1 · WorkBuddy / Qoder / Trae 三平台独立版）

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
| Trae 国际版 `trae/ai` | ❌ | ✅ | ❌ | ✅ | ✅（JWT 导入后） | 仅首次导出 JWT |

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

## 4. 每日签到（签到即领积分）

| 动作 | 端点 | 要点 |
|:--|:--|:--|
| 查状态 | `GET {openapi}/sash/api/v1/me/daily-check-in/status` | `404/405/410` = 该区域暂未开放（Qoder 已改活动领积分），**跳过**；`checked_in`/`checkedIn`/`claimed`/`status=CLAIMED|CHECKED|DONE` = 今日已签 |
| 领取 | `POST {openapi}/sash/api/v1/me/daily-check-in/claim`，body `{}` | 非 2xx 但响应含 `already`/`checked`/`已签` 也算今日已签 |

## 5. 额度查询

`GET {openapi}/api/v2/quota/usage`，响应取 `userQuota` + `addOnQuota` 两个对象，
各自累加 `remaining` / `used` / `total`，单位 `credits`。两者都缺即视为响应异常。

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

| | 国内版 `cn` | 国际版 `ai` |
|:--|:--|:--|
| `base` | `https://api.trae.cn` | `https://grow-normal.trae.ai` |

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

| 动作 | 端点 | 要点 |
|:--|:--|:--|
| 查状态 | `POST {base}/trae/api/v2/ug/checkin_credits/status`，body `{}` | `checked_in` / `checkedIn` = 今日已签 |
| 领取 | `POST {base}/trae/api/v2/ug/checkin_credits/claim`，body `{}` | `code != 0` 且 `message` 非 `success`/空 → 失败；含「已签」/`already` 视为今日已签 |

## 5. 积分查询

`POST {base}/trae/api/v2/pay/ide_user_ent_usage`，body `{"require_usage":true,"full_data":true}`。

响应 `user_entitlement_pack_list[]`（也可能包在 `data` 下），逐包取
`entitlement_base_info.quota.credits_limit` 减 `usage.credits_amount` 得剩余；
聚合 `total` / `used` / `remaining`，到期取还有额度的包里最早的 `expire_time`（秒，输出时 ×1000）。

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
v2.0 字段与 fewards `AccountRepository.WorkBuddyAccount` 一一对应；**v2.1 新增 `platform` 字段**。

| 字段 | 单位 | 说明 |
|:--|:--|:--|
| `id` / `label` / `uid` / `enterpriseId` / `avatarUrl` | — | 展示与去重 |
| `platform` | — | **`wb` / `qoder` / `trae`**；缺省按 `wb`（历史数据零迁移） |
| `variant` | — | `cn` / `ai` |
| `token` | — | access_token / Qoder token / Trae JWT（明文只存本机） |
| `refreshToken` | — | WorkBuddy 轮换式；Qoder / Trae 一般为空 |
| `expiresAt` | **秒** | access 到期；`0 = 未知`，**不能当已过期** |
| `refreshExpiresAt` | **秒** | refresh 到期；`0 = 未知` |
| `obtainedAt` | **毫秒** | 拿到这份凭据的时刻，用于比新旧 |
| `syncVersion` | — | 电脑端维护的单调版本 |
| `domain` | — | Trae 记录所属 base，防止跨档请求 |

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
| 手动导入 | ✅ 凭据 JSON | ✅ access token / PAT | ✅ `Cloud-IDE-JWT …` |
| 独立签到 | ✅ 国内版 | ✅ | ✅ |
| 独立查积分 | ✅ | ✅ 额度 | ✅ 权益包 |
| 自动签到（`AlarmManager`） | ✅ | ✅ | ✅ |
| 手机端刷新凭据 | ✅ 轮换式 | ❌ 重新粘贴 | ❌ 重新导出 |
| 同步到电脑 | ✅ 可选 | — | — |

三者的「一键签到」都经 `PlatformRouter` 汇总：不支持的档位返回 `skipped=true` 并计入跳过数，
不计入失败、不显示红字。
