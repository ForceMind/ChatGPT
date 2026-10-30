# ChatGPT Studio · Android 独立网络预览

本模块验证应用内网络，不替换正式客户端。独立包名 `com.forcemind.chatgpt.networkpreview`，使用独立应用数据和 CI 测试签名；不读取正式 App 的账号、登录、恢复或签名文件。

手机预览包仅包含 arm64 原生库；x86_64 仅放进 CI 模拟器包，避免安装包重复携带两套核心。

## 使用流程

安装独立候选后，从网络菜单添加自己的 VLESS 地址，命名并选择线路。状态依次显示连接中、已连接或失败；切回直连需主动选择。首页直接进入网页，没有地址栏。切换线路保留当前页面，不自动刷新、不重新发送消息或上传。

文件传输进行时禁止切换线路，避免一项任务跨出口。已建立的网页连接不保证即时迁移，新请求走新线路；需要时由用户主动重新连接网页。连通检查只说明指定检查目标的响应情况。

原生下载接口处理 HTTP/HTTPS 地址，普通预览包保持 HTTPS 要求；明文 HTTP 仅在 CI 包对指定回显地址开放。网页上传使用同一 WebView 代理。`blob:`/`data:` 导出、下载文件管理和正式资产库整合不在本独立包的首批范围内。

代理范围为 HTTP/HTTPS 网页与文件请求。WebRTC 语音、UDP 和系统其他应用流量不属于首版已验证的路由范围。

支持网页代理覆盖时，“直连”明确绕过系统 HTTP 代理，网页与文件保持一致；旧 WebView 缺少此能力时，两者均跟随系统网络设置，并拒绝启用 VLESS 线路。

支持范围由 `VlessNode` 严格校验：TCP/raw、WebSocket、TLS，以及 TCP REALITY。未知参数、无效 UUID、端口或不支持的组合会明确拒绝。凭据用 Android Keystore 的 AES-GCM 加密保存在本包私有目录；不提供免费节点、系统 VPN、订阅或 WARP。

## 构建和验证

仅在 GitHub Actions 构建。入口为现有 `Android Recovery PoC`，选择 `experiment=network-preview`；默认 `build_only=true`。只有取得具体交互测试授权时，才改为 false。

工具链：Go 1.27.3、固定 Xray-core 提交、固定 x/mobile、Gradle 9.3.1、AGP 9.1.1、Android SDK 37.0、NDK 28.2、最低 Android 9。依赖由 CI 下载，核心源码未复制进仓库。详见 [依赖许可](THIRD_PARTY_NOTICES.md)。

CI 使用两条自有临时 VLESS 线路和本地回显服务，检查网页、下载和上传出口、文件字节、切换保留、错误状态及关闭清理；不访问真实 ChatGPT、不截图、不使用私人节点。Android 9 的旧 WebView 可能不支持 `PROXY_OVERRIDE`，这种情况下明确拒绝网页代理，只能记录原生核心验证结果，不能算作完整网页路由通过。

扩展协议矩阵提供 raw、TLS、REALITY 各一对自有 A/B 出口，另设不可信CA测试监听器。TLS检查受信任证书、错误主机名和未知CA；REALITY检查正确/错误公钥及short ID。临时证书和密钥每轮在CI生成，REALITY目标也是自有TLS1.3服务，不借用公共网站。CI flavor 单独信任该测试CA；preview flavor 的同名接口为空实现，打包时检查预览资源和Dex没有测试CA或生成的信任类。

2026-10-30 的 [TLS/REALITY扩展CI](https://github.com/ForceMind/ChatGPT/actions/runs/41005311920) 六组完整通过。Android9/15均覆盖加密节点上的HTTPS网页、原生/网页上传、下载、身份拒绝与完整资源窗口。资源统一用HTTPS原站并保留同轮raw对照；本次TLS/REALITY代理空闲平均PSS约176.7–213.2 MiB，CPU约单核0.43%–0.60%，固定文件负载窗口平均CPU约3.27%–4.52%。这是不同新模拟器中的合成数据，不作为通用协议排名或真机/真实ChatGPT保证。见 [最新结果](../../docs/android-network-plan.md#tls--reality-完整链路与资源结果) / [完整计数与样本](../../docs/benchmarks/android-network-tls-reality-resources.json)。

`NETWORK_TEST_TRANSPORT=raw|tls|reality` 选择设备场景；`device-test.sh` 先跑路由断言，raw额外保留HTTP回归。资源命令增加 `--transport` 和 `--fixture`，三种场景统一测自有HTTPS原站，不能拿之前HTTP原站的raw报告当作匹配对照。所有TLS校验保持开启，POST不因结果不明而自动重发。

预览包与 `.ci` 测试包分开。只有测试包允许访问指定明文回显域名；预览包保持正常 TLS 校验。`network-application` 提供 APK、检查报告及摘要，`network-core` 提供代理库、完整模块版本和依赖许可。

## Android 9 与资源验证

扩展 CI 在全新的 Android 9 AOSP x86_64 模拟器中安装并选中固定的官方 AOSP WebView 124.0.6367.219，校验原始 Git blob、SHA-256、APK 签名、最低 SDK 和实际 provider。此历史构建仅作兼容性测试，不随本 App 分发，不更新用户设备，也不作为生产安全版本推荐。Android 9 和 Android 15 的这组运行均强制要求网页代理覆盖能力，不能以原生-only 分支替代完整网页/文件回归。

路由回归通过后，`scripts/measure-resources.py` 在同一台全新 CI 模拟器采集未启动应用、直连空闲、代理空闲、固定负载、代理后台及恢复直连六个阶段。每段预热 5 秒、测量至少 60 秒，默认每 10 秒采样。固定负载为 30 轮 20 字节原生落盘下载、128 KiB 上传及回显、20 字节网页下载；使用自有 VLESS raw/TCP、`security=none` 线路，不代表 TLS/REALITY 的 CPU 成本。

- 内存主指标是 App（包含 Go JNI 核心）与可明确归属的 WebView renderer 的 PSS 合计；RSS 相加可能重复计算共享页。
- CPU 使用内核实际 `AT_CLKTCK`，以进程 `utime+stime` 差值计算 core-seconds 和单核百分比；多核合计可超过 100%。系统诊断服务使用 shell 身份，root 仅用于隔离进程的内核计数读取。
- 报告包含原始进程归属、计数、provider、版本、窗口和文件字节；归属不明、计数不可读、进程重启或阶段缺失都会使资源验收失败，不补零或只报 App 进程。
- 数值包含 instrumentation 的影响，采样也会扰动运行；按顺序执行且不强制 GC。只比较同次系统/provider 内的阶段，不据此宣称真实 ChatGPT 占用、内存泄漏或真机性能。

设备 artifact 的 `resources/report.json`、`resources/report.md` 是汇总，旁边保留原始快照；`provider-upgrade/` 记录 Android 9 更新前后与来源。内核时钟字段定义见 [Linux auxvec.h](https://github.com/torvalds/linux/blob/master/include/uapi/linux/auxvec.h)。

2026-10-30 的扩展验证已在 [Actions 40932254243](https://github.com/ForceMind/ChatGPT/actions/runs/40932254243) 完整通过。Android9已在CI切换到AOSP WebView124，两组均覆盖网页/原生文件、上传、线路切换、草稿保留与显式直连。代理空闲平均PSS约208.5 / 178.8 MiB，CPU约0.68% / 0.40%（Android9 / Android15，单核口径）；这些是合成模拟器结果，不能当作真实ChatGPT或手机占用上限。详见 [结果与边界](../../docs/android-network-plan.md#webview-124-资源结果) 和 [处理后样本](../../docs/benchmarks/android-network-webview124-resources.json)。

真实节点、ChatGPT 登录、手机厂商系统、arm64 真机和正式 App 接入仍需后续验证。CI 签名不是生产签名，本包不承诺跨批次原地升级。

## 多标签、30 分钟后台与故障恢复专项

已获对应测试授权后，使用现有 `Android Recovery PoC` 入口，选择 `experiment=network-stability`、`build_only=false`。四组 Android 9 / 15 × TLS / REALITY 复用受限合成节点；跳过原路由和资源测量批次，每组有 60 分钟作业超时。当前运行与结果以 [开发记录](../../docs/android-network-plan.md#当前批次多标签与故障恢复稳定性) 为准。

- 三个测试支架 WebView 切换时保留文档、草稿和内存标记；服务端核对页面仅加载一次。
- Activity 连续停止至少 1800 秒，主机独立计时、周期检查进程 PID 和前台 Activity；期间不执行页面 JS、网络心跳，不添加唤醒锁或前台服务。
- 仅拒绝所选合成节点的两个 TCP 端口，确认 HTTPS 原站正常。网页与原生失败请求不能到达原站，恢复后由测试明确重连；失败 POST 不自动重发。
- 绕过 RouteManager 停止核心，确认本地监听消失、请求失败且不自行重启；显式重新连接后检查页面、上传与落盘下载。该注入不是 JNI 崩溃或系统杀进程测试。
- 证据包含主机/设备后台时长、前台检查、故障规则、原站请求计数和逐阶段结果，失败也保留诊断；不保存截图、网页内容或用户凭据。

这三个页面共享独立 CI 空间，不代表三个隔离账号。30 分钟测试有 instrumentation 进程存活，不保证 Doze、OEM 强制回收、真实 ChatGPT 或用户手机中的保活结果。本专项没有重新测量内存和 CPU。

2026-10-30 的 [稳定性专项](https://github.com/ForceMind/ChatGPT/actions/runs/41068430363) 四组全部通过，每组实际停止态后台1800.001秒、主机30次后台检查、17项原站业务请求各一次。完整证据见 [长期报告](../../docs/benchmarks/android-network-stability.json)。发现外部核心停止后状态仍为`CONNECTED`：请求失败且不直连、手动重连能恢复，但尚无主动故障状态通知；该问题保留为后续工作，不宣称自动恢复已完成。正式App、main、生产签名和现有数据保持不动。
