# Android 应用内网络首版

状态：已交付 Android 独立网络预览 0.1.0，GitHub Actions 合成验证通过。未接入正式客户端。2026-10-30。

## 第一批可操作交付

先在独立包中提供「直连 / 使用线路」与线路列表，支持手动粘贴 VLESS 地址、命名、编辑、删除和选择。沿用白灰黑与原生弹层，无地址栏、无常驻网络工具栏。连接状态使用「未连接、正在连接、已连接、连接失败」；详细错误按需展开，不把开发日志放入普通菜单。

线路由用户提供。WARP/其他免费通道另行核实，不把它列为已接入或承诺免费节点。完整系统VPN、订阅抓取、自动测速、按账号不同出口不纳入首批。

## 路由契约

- 首版以整个独立App为单位选择线路，使用自己的浏览器和凭据空间，不导入正式账号。代理核心监听127.0.0.1，将WebView和原生文件请求接入同一路由，不改系统代理。正式接入时仍须保留原有账号隔离。
- AndroidX ProxyController作用于App中的WebView，必须运行时检查PROXY_OVERRIDE，并等应用/清除回调后再开始新页面请求。当前正式Android使用webkit1.17.1，尚无网络覆盖实现。
- 网页上传使用网页所在路由；原生AssetDownloads/DownloadHelper所发请求需显式使用同一线路配置，不能只改WebView。登录弹窗与其所属账号同线路。
- 线路变更先保存本机草稿与工作区。已有连接并不保证立即切路，不宣称无缝迁移WebSocket；需要重连时由用户明确选择，既有任务保持原路由或显式取消。结果不明的消息、上传不得自动重试。
- 直连失败可提示选择线路；启用代理后的连接错误不静默改成直连。连通检查只说明检查目标和结果，不能据此保证ChatGPT所有业务可用。

## 已接入的独立核心与依赖

独立模块位于 `android/network-poc`，使用项目自行维护的 Go 启停接口，直接依赖 Xray-core `v1.260327.1-0.20261029083850-0afec66085a8`。固定 Go 1.27.3、x/mobile `v0.0.0-20261011034528-197770444867`、NDK 28.2.13676358；不追踪上游 main。生成 arm64/x86_64 AAR，使用 16 KB 页对齐；Android 构建需要 `-checklinkname=0` 兼容上游 anet 对 Go 内部符号的引用。

Xray-core 使用 MPL-2.0，x/mobile 使用 BSD-3-Clause，AndroidX 使用 Apache-2.0。CI 附带 Go 模块版本、依赖许可及安装包摘要；本项目非商业许可只用于自身代码，不能覆盖第三方文件。未复制代理核心源码到本仓库，Go 依赖只在 Actions 下载。

已实现应用内 loopback HTTP 入口，供 WebView 和 Java URLConnection 显式使用；无直连备用 outbound。TCP、WebSocket、TLS、REALITY 及 HTTPS CONNECT 在自有节点矩阵通过。Android 设备侧验证独立记录，不能把主机 Go 测试等同于 Android 或真实 ChatGPT 验收。IPv6 真机、真实服务与手机厂商差异仍未验证。

合成节点的 freedom 出口在固定核心版本中默认阻止回环目标，首轮四种传输因此超时。CI 服务端仅精确放行自有回显服务的 IP/端口，保持应用配置和失败断言不变；未开放任意私网出口。

## 配置、凭据与错误处理

VLESS导入需校验协议、UUID、主机/IPv6、端口及受支持参数。明确拒绝未知或未支持的组合，保留错误原因，不猜测参数。节点凭据仅本机加密；列表只展示用户名称和脱敏端点，不记录完整链接、UUID、密钥或对话内容。导出/分享凭据须用户主动操作。

核心只绑定loopback，端口和生命周期归App持有；启动失败、配置无效或端口冲突必须回收进程/监听，连接状态不能提前显示成功。核心异常退出后保留配置，任务失败可见，不形成无限自动重启。

## 实施顺序与验收

1. 独立模块：配置解析、支持矩阵、加密保存、直连/代理状态流和核心启动停止。GitHub Actions构建，不读取正式App路径或生产签名。
2. 本地合成VLESS服务及回显端点：实际HTTP/TLS、文件字节、上传、错误凭据、错误地址、连接中断、DNS/IPv6和核心退出。地址/口令均为临时CI值，不使用用户节点。
3. 独立WebView手机/平板界面：点击选择线路，确认回调后请求、网页/下载/上传同路由、账号隔离、切换时不重复发送、旧页面/草稿保留。只在明确批准的对应交互范围运行，不默认截图或访问真实ChatGPT。
4. 交付独立候选及来源/摘要。真实节点与真实ChatGPT、OEM/arm64和正式接入另行验收；完成前不替换现有登录空间、不宣称网络功能已发布。

## 首版交付与实测边界

- [最终 Actions](https://github.com/ForceMind/ChatGPT/actions/runs/40924727232) 全部通过，应用源码提交 `badc584e9f99d8c5bb3a69fb5b1c2fdf2acf5488`。构建、JVM 单元测试、Lint 与模拟器运行均在 Actions 完成。
- [arm64 候选下载](https://github.com/ForceMind/ChatGPT/actions/runs/40924727232/artifacts/12918964909)，ZIP 内含 APK、摘要、许可证与依赖清单，保留 14 天。独立包名 `com.forcemind.chatgpt.networkpreview`，临时 CI 签名，不替换正式 App。
- APK 40,668,298 字节，下载归档 14,369,928 字节。去掉手机包中仅供模拟器使用的 x86_64 核心后，APK 从双 ABI 候选的约 76.5 MB 减到约 40.7 MB。arm64 ELF LOAD 对齐实读为 16 KB。
- Android 15 / WebView 124：网页与原生文件 A/B 线路、下载字节、网页 multipart 文件上传、草稿与文档保留、传输阻止切换、失败不回退及显式直连均通过。
- Android 9 / Chrome WebView 69：原生 VLESS A/B 下载与加密存储检查通过；该旧网页组件没有代理覆盖能力，App 明确拒绝网页代理。本项不计为 Android 9 网页代理通过，需要更新网页组件后另验。
- 初轮 runner 未给 KVM 权限，软件模拟器启动约 8 分钟，网页 JS 回调超时；补齐一次性 runner 的硬件加速权限后，原有断言通过。原生缓存仅按固定运行代码/依赖/构建脚本精确复用，当前 host 测试仍每轮执行，缓存许可缺失仍失败。
- 原始摘要与分项范围见 [验证记录](benchmarks/android-network-preview-0.1.0.json)。本机只读取产物元数据/哈希并清理临时下载，没有本机构建、运行 App 或启动浏览器。

剩余验收：真实节点和 ChatGPT、arm64 真机/OEM、IPv6、系统 HTTP 代理/PAC 混用专项及 WebRTC/UDP。旧网页组件降级后的状态恢复、正式账号/资产库接入另行迭代；本批不改正式登录、恢复数据、生产签名、main、官网或公开 Release。

## 官方核对来源

- AndroidX ProxyController：https://developer.android.com/reference/androidx/webkit/ProxyController
- Xray-core 固定源码与许可：https://github.com/XTLS/Xray-core/tree/0afec66085a8
- x/mobile 固定源码与许可：https://go.googlesource.com/mobile/+/197770444867
