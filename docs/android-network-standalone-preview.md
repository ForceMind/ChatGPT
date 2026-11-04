# Android 独立网络测试版

[下载 Actions 产物](https://github.com/ForceMind/ChatGPT/actions/runs/41832194012/artifacts/13322559438)，解压后打开其中的 APK 安装。适用于 Android 9 及以上的 arm64 手机；安装器可能要求允许当前下载器安装未知应用。

- 应用名：**ChatGPT Studio 测试版**。
- 版本：`3.0.0-alpha.24-candidate.1-standalone`。
- 独立包名：`com.forcemind.chatgpt.profiles.networkpreview`；正式版和旧独立PoC分别为其他包名，可并存，不要卸载正式版。
- 新应用使用独立账号、资产、恢复、Cookie/WebView 和网络配置空间，Android Keystore 按应用UID隔离；不迁入正式版登录，需要自行重新登录。

打开后默认使用系统网络，直接进入网页。打开“更多 → 网络”，添加自己的 VLESS 地址，选择并启用线路。支持已验证的 RAW/TCP none、TLS、REALITY；不提供免费节点、订阅或 WS/gRPC/XHTTP 支持。多个账号共享所选线路，网页和原生文件走同一路由；这不是系统VPN或UDP/WebRTC全路由承诺。节点由你自行配置，不需要向开发者提供密钥或账号密码。

本包以正式逻辑的非调试变体构建：没有CI专用信任CA、合成节点、探针Activity/Provider或测试管理权限。保留正常账号、资产、保活和网络状态界面。使用临时CI测试签名，当前可正常通过系统安装器安装，不保证未来测试包可覆盖升级；卸载本测试版会清除它自己的数据，正式版数据不受此包名影响。

## 构建与验收范围

[构建41832194012](https://github.com/ForceMind/ChatGPT/actions/runs/41832194012)已完成77项单元测试（0失败）、lint、编译和签名/最低API28/arm64/16KiB对齐校验；实际读取最终收据、JUnit XML及无sharedUserId且allowBackup=false的APK清单。没有启动浏览器、访问真实ChatGPT、使用真实节点或安装个人手机。真实登录、节点连通、网页/文件和恢复体验由你自行验收。

[完整收据与SHA-256](releases/android-network-standalone-preview.1.json)。ZIP内也提供SHA256SUMS.txt、安装说明和第三方许可证。

仅在dev交付Actions产物（保留14天），没有合并main、创建公开Release或更新官网/自动更新清单。此前CI采样间歇故障仍按原记录保留，未以这次安装包构建通过宣称根治。
