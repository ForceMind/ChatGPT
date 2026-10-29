# ChatGPT Studio · Android 独立网络预览

本模块验证应用内网络，不替换正式客户端。独立包名 `com.forcemind.chatgpt.networkpreview`，使用独立应用数据和 CI 测试签名；不读取正式 App 的账号、登录、恢复或签名文件。

## 使用流程

安装独立候选后，从网络菜单添加自己的 VLESS 地址，命名并选择线路。状态依次显示连接中、已连接或失败；切回直连需主动选择。首页直接进入网页，没有地址栏。切换线路保留当前页面，不自动刷新、不重新发送消息或上传。

文件传输进行时禁止切换线路，避免一项任务跨出口。已建立的网页连接不保证即时迁移，新请求走新线路；需要时由用户主动重新连接网页。连通检查只说明指定检查目标的响应情况。

原生下载接口处理 HTTP/HTTPS 地址，普通预览包保持 HTTPS 要求；明文 HTTP 仅在 CI 包对指定回显地址开放。网页上传使用同一 WebView 代理。`blob:`/`data:` 导出、下载文件管理和正式资产库整合不在本独立包的首批范围内。

支持范围由 `VlessNode` 严格校验：TCP/raw、WebSocket、TLS，以及 TCP REALITY。未知参数、无效 UUID、端口或不支持的组合会明确拒绝。凭据用 Android Keystore 的 AES-GCM 加密保存在本包私有目录；不提供免费节点、系统 VPN、订阅或 WARP。

## 构建和验证

仅在 GitHub Actions 构建。入口为现有 `Android Recovery PoC`，选择 `experiment=network-preview`；默认 `build_only=true`。只有取得具体交互测试授权时，才改为 false。

工具链：Go 1.27.3、固定 Xray-core 提交、固定 x/mobile、Gradle 9.3.1、AGP 9.1.1、Android SDK 37.0、NDK 28.2、最低 Android 9。依赖由 CI 下载，核心源码未复制进仓库。详见 [依赖许可](THIRD_PARTY_NOTICES.md)。

CI 使用两条自有临时 VLESS 线路和本地回显服务，检查网页、下载和上传出口、文件字节、切换保留、错误状态及关闭清理；不访问真实 ChatGPT、不截图、不使用私人节点。Android 9 的旧 WebView 可能不支持 `PROXY_OVERRIDE`，这种情况下明确拒绝网页代理，只能记录原生核心验证结果，不能算作完整网页路由通过。

预览包与 `.ci` 测试包分开。只有测试包允许访问指定明文回显域名；预览包保持正常 TLS 校验。`network-application` 提供 APK、检查报告及摘要，`network-core` 提供代理库、完整模块版本和依赖许可。

真实节点、ChatGPT 登录、手机厂商系统、arm64 真机和正式 App 接入仍需后续验证。CI 签名不是生产签名，本包不承诺跨批次原地升级。
