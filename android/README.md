# Buddy Switch Agent（Android 移动端）

把 `dev` 分支桌面端的**积分查看 / 自动签到 / 账号登录 / 账号管理**搬到手机上的 Android 客户端。
产物是一个真实可安装的 APK：`dist/agent.apk`（`com.buddyswitch.agent`，minSdk 24 / targetSdk 34）。

> 本目录由 `dev` 分支的 `src/lib/api.ts`（ROUTES 表）与 `crates/buddy-switch-server/src/api_guard.rs`
> 的真实契约推导而来，不是重新发明一套接口。

---

## 1. 为什么是「远程控制台」而不是把桌面端直接编译成 APK

桌面端是 **Tauri（Rust 核心 + React 前端）**，核心能力建立在「本机」之上：

| 依赖 | 桌面端做法 | Android 上 |
|:--|:--|:--|
| 读写客户端认证数据 | 直接操作 `~/.buddy-switch/`、WorkBuddy / Trae / Qoder 客户端配置文件 | 无这些路径，且沙箱禁止跨应用读写 |
| 切换当前登录账号 | 改写本机 auth 文件、重启客户端进程 | 无对应客户端进程 |
| VS Code / JetBrains / CLI 注入 | 安装插件、patch `settings.json` | 无桌面 IDE |
| 额度网关 | 在 `57891~57895` 监听端口，暴露 OpenAI / Anthropic 兼容接口 | 手机不适合常驻监听，且会被系统冻结 |
| 会话副本 / 记忆合并 | 读写本地 JSONL、MEMORY.md | 无本地数据源 |

这些功能**无法在手机上实现**，任何声称「完整移植」的方案都只能是假界面。

好消息是：仓库里已经有 `buddy-switch-server` —— 一个 axum HTTP 服务（`buddy-switch serve`，
默认 `127.0.0.1:57890`），桌面端前端 `api.ts` 本来就是**双通道**（Tauri IPC / HTTP `/api/*`）。
因此最忠实、且唯一真实成立的移动端形态是：

```
手机 APK  ──HTTP /api/*──▶  电脑上的 buddy-switch serve  ──▶  Rust core ──▶  各平台
```

手机端复用同一套 284 条路由契约，把**可远程化**的功能完整实现；桌面专属能力仍留在电脑上执行，
由手机发起指令（例如「切换账号」调用 `/api/switch`，实际写文件由服务端完成）。

详见 [docs/PC-TO-MOBILE.md](docs/PC-TO-MOBILE.md)。

---

## 2. 已实现功能

| 模块 | 功能 | 对应路由 |
|:--|:--|:--|
| **账号登录** | 服务连接配置（地址 / 端口 / 协议 / 令牌）、连接自检 | `GET /api/status` |
| | OAuth 扫码添加账号（发起 → 浏览器授权 → 轮询结果） | `POST /api/oauth/start`、`/api/oauth/status` |
| | Trae / Qoder 各自的 OAuth 入口 | `/api/trae/oauth/*`、`/api/qoder/oauth/*` |
| | 粘贴授权凭据导入 | `POST /api/accounts/paste-credential` |
| **积分查看** | 6 个作用域切换（`wb-cn` / `wb-global` / `trae-cn` / `trae-global` / `qoder-cn` / `qoder-global`） | — |
| | 逐账号额度 / 剩余 / 进度条 / 到期预警 | `POST /api/credits/query` |
| | 单账号积分详情弹层、积分统计、Trae / Qoder 积分 | `/api/credits/stats`、`/api/trae/credits`、`/api/qoder/credits` |
| **自动签到** | 今日签到状态、单账号签到、一键签到全部 | `/api/checkin/status`、`/api/checkin`、`/api/checkin/all` |
| | Trae 批量签到、Qoder 领取签到 | `/api/trae/checkin`、`/api/qoder/checkin/claim` |
| | **Android 端定时自动签到**（AlarmManager + 开机重登记 + 系统通知） | 本地 AlarmManager |
| | 签到日志、服务端自动签到配置 | `/api/checkin/logs`、`/api/checkin/config` |
| **账号管理** | 账号列表 / 搜索 / 当前账号标记 | `/api/accounts/list`、`/api/trae/accounts`、`/api/qoder/accounts` |
| | 切换账号、修改备注、删除、回收站查看与恢复 | `/api/switch`、`/api/accounts/remark`、`/api/accounts/delete`、`/api/accounts/trash/*` |
| **概览 / 设置** | 待办（需重登 / 今日未签 / 积分将到期）、快捷操作、最近签到结果 | 组合 |
| | 演示模式、通知开关、运行日志、API 路由速查 | 本地 |

**演示模式**：不连服务端也能浏览全部界面（内置 4 个示例账号与签到日志）。
桌面浏览器直接打开 `assets/index.html` 会自动进入演示模式，方便预览 UI。

---

## 3. 连接电脑端

服务端默认只绑回环，且有 Host 白名单校验（`api_guard.rs`）。手机访问需两步：

```bash
# Linux / macOS
BUDDY_SWITCH_ALLOWED_HOSTS=192.168.1.5:57890 \
  buddy-switch serve --host 0.0.0.0

# Windows PowerShell
$env:BUDDY_SWITCH_ALLOWED_HOSTS="192.168.1.5:57890"
buddy-switch serve --host 0.0.0.0
```

把 `192.168.1.5` 换成电脑的局域网 IP，手机与电脑连同一 Wi-Fi。
然后在 App「设置」里填主机 / 端口 → **测试连接** → **保存配置**。

`ApiClient` 刻意**不发送** `Origin` / `Sec-Fetch-Site`，所有 POST 都带
`Content-Type: application/json`，正好满足服务端的四条来源校验；否则会返回 403
`host_not_allowed` / `not_json`。

> ⚠️ 管理面能读写账号库与网关 Key，仅应在可信局域网内暴露，或用反向代理加鉴权。

---

## 4. 目录结构

```
android/
├── AndroidManifest.xml          # 权限、Activity、两个 Receiver、明文流量配置
├── build.sh                     # 纯 build-tools 手工构建（无 Gradle / AndroidX）
├── assets/index.html            # 移动端 SPA（HTML + CSS + JS，单文件、零依赖）
├── res/                         # 图标 / 主题 / 网络安全配置
├── src/com/buddyswitch/agent/
│   ├── MainActivity.java        # WebView 宿主
│   ├── JsBridge.java            # JS ↔ 原生桥（HTTP / 配置 / 通知 / 打开浏览器）
│   ├── ApiClient.java           # /api/* HTTP 客户端（对齐 api_guard 校验）
│   ├── Prefs.java               # 本地配置与运行日志
│   ├── AutoCheckinReceiver.java # 定时签到（链式 setExactAndAllowWhileIdle）
│   └── BootReceiver.java        # 开机 / 更新后重登记闹钟
├── docs/
│   ├── PC-TO-MOBILE.md          # 功能迁移对照与取舍说明
│   └── API-CONTRACT.md          # 已适配路由速查
└── dist/agent.apk               # 构建产物
```

## 5. 从源码构建

前置：JDK 8+、Android SDK `platforms;android-34` + `build-tools;34.0.0`。

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh              # 输出 dist/agent.apk
```

脚本依次执行 `aapt2 compile → aapt2 link → javac → d8 → 装入 dex → zipalign → apksigner sign`。
**不依赖 Gradle、AndroidX、Material 或任何联网依赖**，可离线复现。
调试签名证书由脚本自动生成（`build/debug.keystore`，仅用于自测，勿用于分发）。

安装：

```bash
adb install -r dist/agent.apk
```

## 6. 限制与合规

- 手机端只连接**你自己填写的**服务端；App 本身不直连 WorkBuddy / Trae / Qoder 的任何接口，
  也不保存凭据明文（凭据输入后立即转发给服务端）。
- 自动签到受 Android 后台限制（Doze / 厂商省电策略）影响，间隔不保证精确；
  建议同时在服务端保留自动签到作为兜底。
- 桌面专属能力（本机文件读写、IDE 注入、网关端口监听、会话副本）**不在手机端**，
  仍需桌面端运行。
- 沿用上游 **PolyForm Noncommercial 1.0.0** 许可：仅个人非商业使用，禁止商用。
  非官方第三方工具，使用前请阅读仓库的 `docs/policies/DISCLAIMER.md`。
