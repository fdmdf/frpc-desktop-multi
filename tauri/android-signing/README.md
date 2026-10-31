# Android 发布签名

`release.keystore` **故意入库**。这不是疏忽，而是这个项目唯一能让「覆盖安装」成立的做法。

## 为什么必须固定密钥

Android 只允许同包名、**同签名**的 APK 覆盖安装。v1.4.6 与 v1.4.7 的证书指纹实测：

| 版本 | 证书 SHA-256 |
| --- | --- |
| v1.4.6 | `06282f3eda92928718284350c401989f4e28ab0393ec8878b47d4d5f1d615b85` |
| v1.4.7 | `1d64bc6e4a9fbdd0af3f37c9f828f2e26ce993635b0182b3f17af1e71b90c01b` |

原因是 CI 的签名步骤原本每次都用 `keytool -genkeypair` **临时生成一把新密钥**：

```bash
KS="$RUNNER_TEMP/release.keystore"
keytool -genkeypair -v -keystore "$KS" ...          # 每次构建都不一样
```

临时密钥只活在 runner 的 `$RUNNER_TEMP` 里，构建结束即销毁 —— 于是每个版本都是
「不同开发者的 App」，升级必然 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（部分 ROM 文案是
「签名不一致 / 应用未安装」）。现在固定成本仓库里的这一把。

## 参数

| 项 | 值 |
| --- | --- |
| 文件 | `tauri/android-signing/release.keystore`（PKCS12） |
| 别名 | `frpc` |
| 库口令 / 私钥口令 | `frpc-desktop-multi` |
| 算法 | RSA 2048 / SHA256withRSA |
| 有效期 | 10950 天（约 30 年，Android 要求证书覆盖应用生命周期） |
| DN | `CN=frpc-desktop-multi, O=fdmdf, C=CN` |
| 证书 SHA-256 | `E2:D0:7C:A1:12:B8:BF:D8:F3:09:AA:AD:72:B4:1A:E2:8A:6B:FA:2B:42:D1:4E:9F:0C:07:86:92:91:5E:4A:02` |

```bash
# 查看指纹
keytool -list -v -keystore tauri/android-signing/release.keystore \
  -storepass frpc-desktop-multi -alias frpc | grep SHA256
```

## 唯一来源：仓库里的这个文件

CI 的签名步骤**只认** `tauri/android-signing/release.keystore`：

- 不读 secrets，不读环境变量，不自动生成；
- 文件不存在 → 步骤直接失败（`::error::签名密钥缺失`），不打 unsigned 包冒充 release；
- 签名前用 `keytool -list -v` 校验指纹必须等于下面这个 `EXPECTED_SHA256`，
  签名后再用 `apksigner verify --print-certs` 复核产物证书，两次任一不符都失败。

这样「发出去的包签名一定一致」是构建约束，而不是靠人记得。

| 校验点 | 位置 |
| --- | --- |
| `EXPECTED_SHA256`（期望值常量） | `.github/workflows/tauri.yml` 的 `Sign APK` 步骤 |

## 更换密钥（例如密钥泄露）

1. 生成新 keystore，替换 `release.keystore`：

   ```bash
   keytool -genkeypair -v -keystore tauri/android-signing/release.keystore \
     -alias frpc -keyalg RSA -keysize 2048 -validity 10950 \
     -storepass frpc-desktop-multi -keypass frpc-desktop-multi \
     -dname "CN=frpc-desktop-multi, O=fdmdf, C=CN"
   ```

2. 把 `keytool -list -v` 里的 `SHA256:` 那行同步到 workflow 的 `EXPECTED_SHA256`
   （不改这一步，CI 会**拒绝出包**——这是故意的）。
3. 更新本 README 的参数表。

⚠️ **换密钥那一刻，所有用户都必须卸载重装一次**，没有例外。

## 已知代价

密钥公开意味着别人也能签出「能被本 App 覆盖安装」的包。对本项目（个人开源、不上架
应用商店、APK 直接挂在 Release）可以接受；一旦要上架或分发到不受控渠道，就该把密钥
移出仓库、改走私有分发流程 —— 但那样也必须**同时**保持指纹固定，且同步改
`EXPECTED_SHA256`，否则等于回到「签名不一致」的老问题。

## 升级到固定密钥的这一次

历史版本（≤ v1.4.7）每版都是随机密钥，与本密钥对不上，所以 v1.4.8 需要**卸载重装一次**；
从 v1.4.8 起即可正常覆盖升级。卸载会清掉应用数据（`/data/data/<pkg>`），如需保留
配置，先在应用内「导出全部配置」（`.json`），装好后再导入。
