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

## 换成自己的密钥（推荐用于正式分发）

CI 优先读这几个 secret，存在时**完全忽略**仓库内的密钥：

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore 的 base64：`base64 -w0 my.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | 库口令，缺省 `frpc-desktop-multi` |
| `ANDROID_KEY_ALIAS` | 别名，缺省 `frpc` |

webhook 里私钥口令与库口令一致，所以只需要一个密码 secret；想分开就再加
`ANDROID_KEY_PASSWORD`。

## 已知代价

密钥公开意味着别人也能签出「能被本 App 覆盖安装」的包。对本项目（个人开源、不上架
应用商店、APK 直接挂在 Release）可以接受；一旦要上架或分发到不受控渠道，请换成上面的
secrets 方案，并把新密钥妥善备份 —— **换密钥那一刻，所有用户都必须卸载重装一次**。

## 升级到固定密钥的这一次

历史版本（≤ v1.4.7）每版都是随机密钥，与本密钥对不上，所以 v1.4.8 需要**卸载重装一次**；
从 v1.4.8 起即可正常覆盖升级。卸载会清掉应用数据（`/data/data/<pkg>`），如需保留
配置，先在应用内「导出全部配置」（`.json`），装好后再导入。
