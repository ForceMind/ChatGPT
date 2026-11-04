# Android 独立网络测试版

[下载 APK](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.24-candidate.1-standalone/ChatGPT-Studio-3.0.0-alpha.24-candidate.1-standalone-android-arm64.apk)，或在[GitHub 独立预发布](https://github.com/ForceMind/ChatGPT/releases/tag/v3.0.0-alpha.24-candidate.1-standalone)下载完整 Bundle ZIP。此前已安装同一 Actions APK 的用户无需重装。适用于 Android 9 及以上的 arm64 手机；安装器可能要求允许当前下载器安装未知应用。

- 应用名：**ChatGPT Studio 测试版**。
- 版本：`3.0.0-alpha.24-candidate.1-standalone`。
- 独立包名：`com.forcemind.chatgpt.profiles.networkpreview`；正式版和旧独立PoC分别为其他包名，可并存，不要卸载正式版。
- 新应用使用独立账号、资产、恢复、Cookie/WebView 和网络配置空间，Android Keystore 按应用UID隔离；不迁入正式版登录，需要自行重新登录。

打开后默认使用系统网络，直接进入网页。打开“更多 → 网络”，添加自己的 VLESS 地址，选择并启用线路。支持已验证的 RAW/TCP none、TLS、REALITY；不提供免费节点、订阅或 WS/gRPC/XHTTP 支持。多个账号共享所选线路，网页和原生文件走同一路由；这不是系统VPN或UDP/WebRTC全路由承诺。节点由你自行配置，不需要向开发者提供密钥或账号密码。

本包以正式逻辑的非调试变体构建：没有CI专用信任CA、合成节点、探针Activity/Provider或测试管理权限。保留正常账号、资产、保活和网络状态界面。使用临时CI测试签名，已通过APK签名及静态安装属性校验，手机安装待你验收；不保证未来测试包可覆盖升级；卸载本测试版会清除它自己的数据，正式版数据不受此包名影响。

## 构建与验收范围

[构建41832194012](https://github.com/ForceMind/ChatGPT/actions/runs/41832194012)已完成77项单元测试（0失败）、lint、编译和签名/最低API28/arm64/16KiB对齐校验；实际读取最终收据、JUnit XML及无sharedUserId且allowBackup=false的APK清单。没有启动浏览器、访问真实ChatGPT、使用真实节点或安装个人手机。真实登录、节点连通、网页/文件和恢复体验由你自行验收。

[完整收据与SHA-256](releases/android-network-standalone-preview.1.json)。ZIP内也提供SHA256SUMS.txt、安装说明和第三方许可证。

2026-11-05 经用户授权已公开为 GitHub Pre-release，附件直接复用通过校验的 Actions 原产物；构建时的收据保留历史状态，当前发布状态见[发布记录](releases/android-network-standalone-preview.1-publication.json)。不设为 Latest，没有合并 main，也没有替换官网正式下载或自动更新清单。固定测试证书和 Actions Secret 尚未创建，本次仍为原临时签名。此前CI采样间歇故障仍按原记录保留，未以这次安装包构建通过宣称根治。
