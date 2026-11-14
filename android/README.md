# Buddy Switch Agent（Android · 三平台独立版 v2.2）

**手机独立完成 WorkBuddy / Qoder / Trae 的登录、签到、领积分、查余额；账号信息同步到电脑是可选的。**

产物：`dist/agent.apk`（`com.buddyswitch.agent`，minSdk 24 / targetSdk 34，versionCode 8 / 2.2.0）。

> ### 版本沿革
>
> - **v1.x**：`buddy-switch-server` 的局域网客户端，登录/签到都靠电脑执行（已废弃）。
> - **v2.0**：参考 [fewards](https://github.com/loma18/fewards) + [workbuddy-console](https://github.com/loma18/workbuddy-console)，
>   改用 WorkBuddy 官方 `/v2/plugin/auth/*` 扫码协议，手机独立登录与签到。
> - **v2.1**：新增 **Qoder 独立扫码登录** 与 **Trae 粘贴 JWT 后独立签到**，
>   协议取自 `qoder-trae-workbuddy-switch` dev 分支的 Rust core。
> - **v2.1.1**：修复 **Qoder 签到提示「跳过」**。v2.1.0 只打已废弃的
>   `/sash/api/v1/me/daily-check-in/*` 通道，该通道国内版 `DISABLED`、国际版 404，
>   遇 404 就直接返回 `skipped`。现改为对齐 Rust core `checkin.rs` /
>   `campaign.rs` 的**活动平台优先**通道：`GET /sash/api/v1/me/campaigns` →
>   逐条 `POST /{id}/claim` 领取「每日 100 Credits」，旧通道仅在活动平台整区
>   404/405/410（并重试确认）时兜底。详见 [docs/API-CONTRACT.md](docs/API-CONTRACT.md)。
> - **v2.1.2（本次）**：修复 **Trae 国际版签到 / 查积分全部 404**。根因是 v2.1 把
>   「国际版账号网关」`grow-normal.trae.ai` 误当成「签到 / 积分 API 网关」——
>   实测 `/trae/api/v2/*` 只挂载在**国内官方网关 `api.trae.cn`**，国际网关只代理
>   OAuth/账号路由（`/trae/api/v3/oauth/*`），打 v2 一律 nginx 404。参考实现
>   `credits.rs::post_json` 固定 `TraeVariant::default()`、`endpoints_for` 只返回
>   `cn_endpoints`，即国际版账号的签到也发往 `api.trae.cn`。现改为**主通道统一
>   `api.trae.cn`（与 variant 无关）**、国际网关仅作回落，自动跳过网关级 404；
>   全无路由判「未开放」，鉴权被拒给出可操作诊断，不再虚报成功或抛莫名 404。
> - **v2.2.0（本次）**：按用户反馈做三项产品改进。
>   ① **Qoder 国内 / 国际版去掉「领积分」按钮** —— Qoder 没有独立成长中心，
>   签到走官方活动平台即领 100 Credits，那个按钮永远返回「跳过」，只会误导。
>   ② **查积分补齐「每月赠送的基础 Credits」** —— 不再只给一个笼统合计：
>   Qoder 把 `userQuota`（每月基础，PLAN）与 `addOnQuota`（资源包，BONUS）拆成明细，
>   并尽力请求网页端逐包接口 `/api/v2/me/usages/big_model_credits` 补充分组与下月重置时间；
>   WorkBuddy 额外请求 `get-user-resource-free-packages` 补免费月度包；
>   Trae 按包名把月度 / 基础包与资源包分类。账号卡片分项展示
>   **每月赠送基础 Credits / 资源包 / 加购**及逐包明细。
>   ③ **首页新增「6 个产品」矩阵** —— WorkBuddy / Qoder / Trae × 国内 / 国际，
>   每项显示账号数与该产品下所有账号的**汇总剩余积分**；查不到显示「待查询」而非 0。
>   另新增**账号备注**：账号名是无意义字符时可写备注辨认（只存本机，刷新 / 同步不覆盖）。

## 能做什么

| 功能 | WorkBuddy | Qoder | Trae | 需要电脑 |
|:--|:--:|:--:|:--:|:--:|
| 手机独立扫码登录 | ✅ | ✅ | ❌ 见下方说明 | ❌ |
| 粘贴凭据 / JWT 导入 | ✅ | ✅ | ✅ | ❌ |
| 每日签到领积分 | ✅ 仅国内版 | ✅ | ✅ | ❌ |
| 成长中心一键领取 | ✅ 仅国内版 | — 签到即领（v2.2 起无此按钮） | — 签到即领 | ❌ |
| 积分余额与到期查询 | ✅ | ✅ | ✅ | ❌ |
| **每月基础 Credits / 资源包分项** | ✅ | ✅ | ✅ | ❌ |
| 首页 6 产品「账号数 + 汇总积分」 | ✅ | ✅ | ✅ | ❌ |
| 账号备注（只存本机） | ✅ | ✅ | ✅ | ❌ |
| 定时自动签到 + 通知 | ✅ | ✅ | ✅ | ❌ |
| 刷新凭据 | ✅ | 重贴 token | 重贴 JWT | ❌ |
| **账号同步到电脑** | ✅ 可选 | ✅ 可选 | ✅ 可选 | 仅此手动动作 |

### Trae 为什么不能独立扫码

`trae/oauth.rs` 的授权 URL 必须携带从桌面客户端 `storage.json` 解密出的
`privateKeyPEM` / `deviceId`（`icube::device_identity_for`），且授权页会主动探测
`127.0.0.1:17388` 确认客户端在线。两者在手机上都不存在，上游不通过。

但 `trae/credits.rs` 与 `trae/checkin.rs` 证明：**签到与积分接口只校验 JWT + 本机自造的
设备请求头**（`device::derive(user_id)`）。因此从桌面客户端导出一次 JWT 后，
手机即可长期独立签到、领积分、查余额，不再依赖电脑。JWT 的 `data.id` 会自动解析成 uid。

> **网关单一来源（v2.1.2 修正）**：`/trae/api/v2/ug/checkin_credits/{status,claim}` 与
> `/trae/api/v2/pay/ide_user_ent_usage` **只挂载在国内官方网关 `https://api.trae.cn`**。
> 国际版 `https://grow-normal.trae.ai` 是**账号 / OAuth 网关**（只代理
> `/trae/api/v3/oauth/*`、`/cloudide/api/v3/*`），并没有这套 v2 路由——实测打过去一律
> nginx 404。因此 App 的签到 / 积分**主通道恒为 `api.trae.cn`，与国内版 / 国际版选择无关**，
> 国际账号网关仅作回落候选。这也与参考实现一致：`credits.rs::post_json` 固定
> `TraeVariant::default()`，`mod.rs::endpoints_for` 只返回 `cn_endpoints`。
>
> **国际版账号的现实边界**：`region.rs` 明确 CN 与国际是**两套互不相通的账号体系、JWT 不通用**。
> 国际版 JWT 打到国内网关可能返回「未开放 / 鉴权被拒」，App 会如实给出诊断而不是伪造成功。
> 若确属国际版账号，签到 / 查积分请以电脑端 Trae 客户端为准。

## 快速开始

1. 安装 `dist/agent.apk`（需允许「未知来源」；v2.0.0 起可直接覆盖升级，不必卸载）。
2. 「账号」→ **＋ 添加账号** → 选平台 → 选国内版 / 国际版。
3. WorkBuddy / Qoder：点**发起登录** → 手机浏览器打开授权页确认 → 回 App 自动轮询完成。
   Trae：在**方式二**粘贴 `Cloud-IDE-JWT …`（可带前缀）→ 导入。
4. 首页点 **一键签到并领取积分**，或账号页逐个操作、按平台筛选。

> WorkBuddy 国际版**没有签到 / 成长中心接口**，可登录与查积分，签到自动跳过。
> **Qoder 国内 / 国际版都没有「领积分」按钮**：签到本身即在官方活动平台发放 100 Credits，
> Trae 同理；「成长中心一键领取」只对 WorkBuddy 国内版有效。
> 「查积分」现在分项显示**每月赠送的基础 Credits**与**资源包 / 加购**，并列出逐包明细；
> 首页「6 个产品」矩阵按产品汇总账号数与剩余积分。账号名难认时点「加备注」写备注。
> Qoder 签到请用**扫码登录**取得的 access token；`pt-` 开头的 PAT 只能查额度，
> 上游不允许用于活动平台领取。若活动列表里没有积分类活动，界面如实显示「未开放」
> 而不是「跳过」或「成功」（不会谎报领取）。
> **Trae 国际版**：签到 / 积分统一走 `api.trae.cn`；若上游因账号体系不通拒绝，
> 界面显示「未开放」或明确鉴权诊断，可在报告页查看完整说明。

**可选同步**：「同步」页填电脑局域网 IP / 端口（默认 57900）/ 配对码 → 测试连接 →
推送账号。协议与 workbuddy-console 的 `server/lansync/*` 一致。不填、不点，App 完全正常工作。

## 架构

纯 Android Framework + WebView，无 Gradle / AndroidX / 任何第三方依赖，用 build-tools 手工构建。
WebView 里的 JS 无法伪造 `Origin` / `Referer` / `User-Agent` 指纹头，也过不了 CORS，
因此所有上游请求由 Java 层 `HttpsURLConnection` 发出。

```
assets/index.html  ←→  JsBridge.call(cbId, action, args)  ←→  PlatformRouter
   （移动端 SPA）         （异步线程 + evaluateJavascript）      │
        │                                                     ├─ WorkBuddyApi（fewards 协议）
        │                                                     ├─ QoderApi（PKCE 设备授权）
        │                                                     └─ TraeApi（JWT + 设备头）
        └────────────── AccountStore（本机账号库，含 platform 字段）
```

| 文件 | 职责 |
|:--|:--|
| `assets/index.html` | SPA：首页（6 产品矩阵）/ 账号（平台筛选、备注、积分分项）/ 同步 / 设置，内嵌自研二维码编码器 |
| `src/.../Platforms.java` | 三平台能力矩阵、6 个产品（3×2）定义与 `scopeKey`、标签、是否支持扫码 |
| `src/.../PlatformRouter.java` | 按账号平台分派签到 / 领积分 / 查额度 / 刷新 |
| `src/.../QoderApi.java` | Qoder 独立登录（PKCE + deviceToken/poll）、活动平台签到（campaigns + claim）、额度（`userQuota` 基础 / `addOnQuota` 资源包拆分 + 网页端逐包补充） |
| `src/.../TraeApi.java` | Trae JWT 解析与导入、`api.trae.cn` 主通道多网关自适应签到 / 积分、权益包按「月度基础 / 资源包」分类、网关级 404 / 鉴权诊断 |
| `src/.../WorkBuddyApi.java` | WorkBuddy 登录、签到、成长中心、积分（summary + 免费月度包分项）、刷新、lansync |
| `src/.../WbVariant.java` | WorkBuddy 国内 / 国际档位域名与指纹头 |
| `src/.../AccountStore.java` | 本机账号库，`platform` 字段隔离三平台、`remark` 备注字段（历史数据默认 wb / 空备注） |
| `src/.../Http.java` | 通用 HTTP(S) 引擎，`Connection: close` 避免死连接复用 |
| `src/.../JsBridge.java` | 统一 `call` 分发（含 `setRemark` / `scopes` 统计）+ 通知 / 打开浏览器 / 自动签到 |
| `src/.../AutoCheckinReceiver.java` | 本地定时签到，经 PlatformRouter 遍历全部平台 |

二维码生成器为自包含实现（Byte mode / ECL-M / mask 0 / version 1–20），纠错块表由
Python `qrcode` 导出并与标准库**逐位比对验证**通过，无 CDN、无运行时依赖。

## 构建

```bash
cd /workspace/android
./build.sh                       # 需要 ANDROID_SDK_ROOT（platform 34 + build-tools 34.0.0）
ALLOW_REBUILD=1 ./build.sh       # 同一 versionCode 内只改资源 / SPA 时重新构建
```

发新版本只需改 `AndroidManifest.xml` 的 `versionCode`（+1）与 `versionName`。

### 签名一致性与版本号依次升号

| 机制 | 实现 |
|:--|:--|
| 签名密钥固定 | `signing/release.keystore`，**不在** `build/` 内；`build.sh` 只复用不重建 |
| 指纹锚点 | `signing/FINGERPRINT.txt`（证书 SHA-256），构建后强制比对，漂移即删产物并失败 |
| 版本号单一数据源 | `build.sh` 从 `AndroidManifest.xml` 读取，不再硬编码 |
| 依次升号 | 已有 `dist/agent.apk` 时要求新 `versionCode` 严格大于旧值，否则拒绝构建 |

当前固定指纹 `2092c870…7b6c`。**v2.0.0（3）→ v2.0.1（4）→ v2.1.0（5）→ v2.1.1（6）→ v2.1.2（7）→ v2.2.0（8）均可直接覆盖安装**；
v1.x 的私钥已永久丢失，那两个历史版本升级需卸载最后一次。
换机 / 重新 clone 后构建必须带上整个 `signing/` 目录，详见 [signing/README.md](signing/README.md)。

## 安全

- access/refresh token、Trae JWT 只存在 App 私有 `SharedPreferences`（`secure_store`），日志不打印明文。
- 同步只允许**局域网私有网段**，填公网 IP 会直接拒绝。
- 导出账号 JSON 是明文，需自行保管。
- Qoder `machine_id`、Trae 设备头按 uid 稳定派生并持久化，避免上游把同一账号识别为频繁换设备。
- 自动签到受 Android Doze / 厂商省电策略影响，间隔不保证精确。

## 参考实现与文档

- 协议来源：[fewards](https://github.com/loma18/fewards)、[workbuddy-console](https://github.com/loma18/workbuddy-console)、
  [qoder-trae-workbuddy-switch](https://github.com/loma18/qoder-trae-workbuddy-switch)（`crates/buddy-switch-core/src/modules/{qoder,trae}/*`）。
- [docs/API-CONTRACT.md](docs/API-CONTRACT.md) — 三平台逐端点契约
- [docs/PC-TO-MOBILE.md](docs/PC-TO-MOBILE.md) — 架构演进与参考实现对照
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) — 登录 / 签到 / 同步排查
- [signing/README.md](signing/README.md) — 签名密钥保管与覆盖安装条件
