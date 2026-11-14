# 签名密钥保管说明

这个目录决定一件很实际的事：**新版本能否直接覆盖安装，还是必须先卸载旧版。**

## 覆盖安装的三个条件

Android 允许覆盖安装（而不是 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）要求同时满足：

| 条件 | 本项目 |
|:--|:--|
| 包名一致 | `com.buddyswitch.agent` |
| 签名证书**逐字节相同** | `release.keystore` 中别名 `buddyswitch` 的证书 |
| `versionCode` **严格递增** | 见 `AndroidManifest.xml`，每次发版 +1 |

## 目录文件

| 文件 | 含私钥 | 推送到公开 GitHub | 说明 |
|:--|:--:|:--:|:--|
| `release.keystore` | ✅ | ❌ **绝不推送** | 唯一签名密钥（PKCS12，别名 `buddyswitch`，口令 `buddyswitch`） |
| `FINGERPRINT.txt` | ❌ | ✅ 可推送 | 证书 SHA-256 锚点，`build.sh` 构建后强制比对 |
| `README.md` | ❌ | ✅ 可推送 | 本说明 |

### 当前签名指纹

```
SHA-256: 2092c870cf0c10b8607a7392094977157d099a77fe0ed0d6c7681e0ce46b7b6c
SHA-1  : D4:07:A0:0A:72:71:29:9B:C9:34:80:32:04:C4:A5:6E:A4:75:EC:F3
MD5    : a3:ad:13:c0:5d:07:29:d1:b9:2b:10:c6:96:07:f6:48
DN     : CN=Buddy Switch Agent, OU=Mobile, O=BuddySwitch, C=CN
```

校验任意两个 APK 能否直接覆盖升级：

```bash
BT=/opt/android-sdk/build-tools/34.0.0
$BT/apksigner verify --print-certs old.apk | grep 'SHA-256 digest'
$BT/apksigner verify --print-certs new.apk | grep 'SHA-256 digest'
$BT/aapt2 dump badging old.apk | head -1    # versionCode
$BT/aapt2 dump badging new.apk | head -1
```

**指纹相同 + versionCode 变大 = 直接覆盖安装。**

## 为什么以前会「必须卸载才能装」

旧版 `build.sh` 把密钥放在 `build/debug.keystore`，而脚本开头有 `rm -rf build`，
**每次构建都重新生成一对新密钥**。因此 v1.0 / v1.1 / v2.0.0 三个版本签名互不相同，
升级时必然报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

现在密钥固定在 `signing/`（不在 `build/` 清理范围内），`build.sh` 只复用不重建，
并在构建后用 `FINGERPRINT.txt` 做锚点校验：指纹一旦漂移就**删除产物并构建失败**。
同时 `versionCode` 只从 `AndroidManifest.xml` 读取，且要求比已有 `dist/agent.apk` 严格递增。

## 换机 / 重新 clone 后构建

`release.keystore` 不会出现在公开仓库里（防止他人用同一密钥签发同名应用诱导覆盖安装）。
**必须把整个 `signing/` 目录从旧机器带过来**，否则：

- `build.sh` 检测不到密钥会生成一对新的 → 指纹锚点校验失败 → 构建终止并删除产物，
  **不会产出一个让用户必须卸载的 APK**；
- 这是刻意设计：宁可构建失败，也不悄悄换签名。

如果确实要在新环境启用一套全新签名（并接受所有已装用户必须卸载一次）：

```bash
RESET_FINGERPRINT=1 ./build.sh
```

> 仅在明确放弃覆盖升级时使用。执行后 `FINGERPRINT.txt` 会被改写为新指纹。

## 历史边界

| 版本 | versionCode | 签名 | 升到 v2.0.1 |
|:--|:--:|:--|:--|
| v1.0 / v1.1 | 1 / 2 | 各自独立，且私钥已随 `build/` 清空而**永久丢失** | 需卸载一次 |
| v2.0.0 | 3 | `2092c870…7b6c` | ✅ **直接覆盖** |
| v2.0.1 起 | 4+ | `2092c870…7b6c`（固定） | ✅ 后续所有版本直接覆盖 |

v1.x 的私钥无法找回，因此那两个历史版本的用户**最后一次**升级需要卸载；
从 v2.0.0 → v2.0.1 开始，以及此后的每一次升级，都不再需要卸载。
