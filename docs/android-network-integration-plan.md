# Android 应用内网络候选与验收

**候选和三批合成验收报告已交付（2026-11-04）。最新整轮 CI 未全绿：Android 15 前台复测仍有采样失败，原因未确定；有效性能证据来自此前完整通过的同 APK 测量，未将失败复测计为通过。** 仅 dev 候选与 Actions 验收，不合并 main、不公开发布、不更新个人手机或使用生产签名。

## 当前实施记录

基线 dev 为 `814dcb3`，受保护 main 为 `ffbbd91`。保留既有登录、Profile、账号与 storage ID、恢复和加密格式、保活策略。构建和浏览器 / 文件 / 设备测试仅在全新 CI 模拟器使用合成数据，无截图或真实 ChatGPT。允许定点扩展 `android.yml` 的核心构建前置并新增 `android-network-integration.yml`，其他工作流不改。

| 批次 | 交付与状态 |
|---|---|
| 1 · 最小接入 | 已通过：源码 `4412b98` / [41676289330](https://github.com/ForceMind/ChatGPT/actions/runs/41676289330) 的构建及 Android 9/15 六组 RAW/TLS/REALITY 完整通过。实际读取六组原始报告：72 个两出口文件、102 个精确请求，三 Profile、四页及触摸弹窗、Activity 重建、切线、核心故障和手动重试保留原页面/草稿，默认关闭未加载 Go。保存结果由 ActivityMonitor 返回，尚不算真实保存窗口验收。 |
| 2 · 数据与文件 | 已通过 `a6baf07` / [41690901023](https://github.com/ForceMind/ChatGPT/actions/runs/41690901023) 的六组协议 / 两端升级。覆盖：公开 alpha.22 旧新同临时签名升级；真实系统保存窗口；实际 4096 字节文档写入后失败；权限拒绝、既有文件保护、取消及迟到回调；真实进程结束后新网页恢复加密草稿、Cookie、所选线路及旧 Profile 延后清理；实际 HTTP/PAC 出口与临时角色恢复。 |
| 3 · 性能与候选 | 候选与性能报告已交付。Android 9 最新前台、两端完整后台通过；Android 15 使用此前同 APK 的完整对照数据，本轮两次复测失败保留为 CI 待办。工作流依赖 build、device、upgrade 全部通过后才启用资源任务：同一模拟器顺序比较公开 alpha.22、新版冷关闭、启用、启用后关闭；每种空闲与固定 600-chunk 离线 DOM 流各完整 60 秒，包含 App/JNI 与可归属 renderer 的 CPU/PSS。另做 Android 9/15 实际 Home 后 1800 秒与心跳记录。不是实际网络流或真实 ChatGPT 的成本。候选版本为 `3.0.0-alpha.24-candidate.1` / 3000024，校验 arm64 ELF/ZIP 的 16 KiB 对齐，生成未签名审阅包和 SHA，不创建标签/发布。 |

### 当前候选入口（审阅用途）

[Actions 41713812139](https://github.com/ForceMind/ChatGPT/actions/runs/41713812139) / 源码 `d0b8db4` 已完成构建、77 项单元测试（实际读取 16 份 JUnit XML，0 失败）、六组协议设备用例和两端合成同签名升级。候选 [arm64 未签名产物](https://github.com/ForceMind/ChatGPT/actions/runs/41713812139/artifacts/13268817906) 保存 14 天，仅供审阅，不能作为原签名覆盖安装包。

- 文件：`ChatGPT-Studio-Network-Candidate-d0b8db47-android-arm64-UNSIGNED.apk`
- 版本：`3.0.0-alpha.24-candidate.1` / `3000024`
- 大小：40,903,165 字节（约 39.0 MiB）；公开 alpha.22 为 7,376,521 字节（约 7.0 MiB），字面差约 32.0 MiB，不能把每一字节差值都归因于核心。
- SHA-256：`571e3fdc934e22f08dd511305a985c23368c35dbef19e63894576d04b381bfab`，与第二批通过时的正式 APK 字节相同。
- CI 确认无测试 CA / 生成信任类、测试管理权限或 Provider；仅 arm64，ELF 与 ZIP 16 KiB 对齐通过；未改变 R8 设置。
- 本批不含原签名升级、32 位设备、厂商系统、真实节点 / ChatGPT 或真人 UI 体验验收。没有操作个人设备、main 或公开站点。

最新整轮 [41713812139](https://github.com/ForceMind/ChatGPT/actions/runs/41713812139) 未全绿：第 1 次尝试在基线后 emulator 端口不可连接，第 2 次尝试的首个基线快照中 dumpsys 返回255，随后设备离线。运行器诊断包未能确定根因，不能断言 OOM 或产品故障。两次失败均保留。两端后台和 Android 9 前台完整通过；Android 15 复用此前完整同条件测量，依据为正式代码 / 构建配置 / 基础采样器 / 工作流无差异、APK 字节相同、WebView 同为124.0.6367.219。原签名、真机、真实节点与人工 UI 体验均未在本批验证。

### 前台性能证据

Android 9 取最新 `d0b8db4` / [41713812139](https://github.com/ForceMind/ChatGPT/actions/runs/41713812139) 的完整四状态测量；Android 15 取 `3512023` / [41695140228](https://github.com/ForceMind/ChatGPT/actions/runs/41695140228) 的完整测量，复用依据如上。每端为同一模拟器顺序比较；三个真实 Profile、四个离线合成页面，各空闲 / 600-chunk DOM 流窗口完整60秒。CPU以一核100%计，PSS含App/JNI与可明确归属的renderer。x86_64 CI测试构建对照旧debug，不是arm64真机或实际ChatGPT网络成本。

| Android | 状态 | 空闲 CPU | 空闲 PSS MiB | DOM 流 CPU | DOM 流 PSS MiB |
|---|---|---:|---:|---:|---:|
| 9 | alpha.22 基线 | 0.96% | 222.5 | 24.21% | 272.5 |
| 9 | 冷关闭 | 0.91% | 229.5 | 24.02% | 275.0 |
| 9 | 启用 | 1.29% | 259.4 | 23.95% | 308.8 |
| 9 | 启用后关闭 | 0.93% | 259.8 | 24.10% | 310.2 |
| 15 | alpha.22 基线 | 1.16% | 129.8 | 15.86% | 189.5 |
| 15 | 冷关闭 | 1.22% | 183.3 | 16.44% | 227.0 |
| 15 | 启用 | 1.37% | 210.3 | 16.48% | 232.3 |
| 15 | 启用后关闭 | 0.99% | 214.7 | 16.52% | 260.8 |

启用相对冷关闭的空闲 PSS 多约30 / 27MiB；关闭已启用核心不会卸载Go运行时。冷关闭相对旧版也有约7 / 54MiB差值，原因未确定，不能宣称零成本。Android15参考基线的系统CPU约171%，存在明显启动服务负载；加上顺序、缓存、JIT与仪器影响，不把这些单轮值当作启动或CPU改善的因果证明。各轮原值、四页就绪时间、来源和失败记录见[JSON](benchmarks/android-network-integration.json)。

### 最新完整后台结果

源码 `d0b8db4` / [41713812139 第 1 次尝试](https://github.com/ForceMind/ChatGPT/actions/runs/41713812139/attempts/1)。实际读取两份完整报告及各自 `OK (1 test)`：真实 Home、四页继续运行、返回同一受管 Activity / 原文档 / 草稿、服务停止和场景销毁全部通过。Android 9 的短前置也实际取得原任务的 RESUMED / 焦点。

| Android | 实际停止态时长 | CPU（一核100%） | 平均 PSS | 观测 |
|---|---:|---:|---:|---|
| 9 | 1801 秒 | 0.64% | 257.4 MiB | 60 次四页心跳推进；全部观测均为停止态 / 原服务运行 |
| 15 | 1805 秒 | 0.33% | 128.9 MiB | 60 次四页心跳推进；全部观测均为停止态 / 原服务运行 |

这是核心空闲、三 Profile / 四离线页面、每30秒一次 JS / 生命周期观测的模拟器证据。仪器与观测可能影响 CPU 和执行，不代表无人观测的厂商真机耗电保证；TLS / REALITY 路由另有功能用例，本表没有测它们的大流量加密成本。四页就绪时间记录在 JSON，含 Profile / DOM 初始化，不当作点击到首帧耗时。

## 验证边界与遗留项

本批交付正式代码候选及合成验收报告，不进行公开发布或个人设备升级。原证书升级、arm64 / 厂商真机、真实节点 / ChatGPT、32位设备、语音 / WebRTC路由另需验证。当前CI遗留为Android15前台采样稳定性；若继续诊断，应先保留首个命令错误和guest/host日志，避免cleanup覆盖它，不能放宽完整计数断言或补零。已有完整有效测量继续保留，不为这项问题重复已通过的30分钟后台场景。

## 实施与失败迭代记录

第二批 `8bfef1d` / Actions `41683113485` 构建通过，但整体未通过。已读 Android 15/TLS 的实际文件报告：真实 DocumentsUI、32 个精确网页/文件请求、4 个真实 gate 操作、4096 字节后发布失败清理、HTTP S/PAC P 和临时角色恢复通过。后续冷进程用例在启动前拒绝继续，因为测试写成 `process-restart-probe.json`，实际 debug 应用读取 `process-restart-fixture.json`；没有放行真实网页。升级测试 seed 成功，新进程验证通过 `getProfile` 直接取 Cookie 触发 WebView 124 空指针；改为先断言原名称确实存在，再加载同一 Profile 并逐字校验原 Cookie，不允许默认/共享/空 Profile 兜底。两处测试初始化已修，待重验，尚不算升级或冷恢复完成。

修正源码 `834ab25` / `41685551483` 的 Android 15 三组完整通过，包含真实冷恢复；Android 9 的真实保存未在自有 Provider 产生文件，测试现明确通过 DocumentsUI 选择 owned 根目录后再保存，并记录仅 CI 可用的返回 authority/flags/错误类型。升级检查在旧版合成 Profile 未持久登记处停止，现用真实 WebView + 内联离线文档建立旧档案，并校验持久 localStorage；仍在加载目标前要求原名称存在，不创建空档案兜底。另从新代码发现 AssetDownloads 独立 close 未取消其在途下载，已补自身任务追踪与取消，增加真实 prefix 后关闭、保留另一任务、关闭后新请求不到站的回归；该修正和新增第五个 gate 尚待 CI。

`88960f2` / `41688528906` 的 Android 9 三组完整文件/冷恢复流程与 Android 15 RAW/TLS、升级通过；Android 9 升级仍在持久 Profile 名称存在性检查停止，Android 15 REALITY 在一次真实触摸弹窗创建处失败。升级种子改为附着在既有离线 UploadProbeActivity 的真实 WebView，并先验证旧版自身冷启动再替换新版，保留原名称和 Cookie/localStorage/密文逐字校验。弹窗仍只注入一次真实触摸，增加窗口/输入和 VisualState 就绪、elementFromPoint 命中前置，不增加点击重试或改用 JS 点击。以上待后续实际验收。

第二轮源码 `1f3f6b8` / 专用 Actions `41673683898` 的构建与包隔离通过；设备首个差异为核心停止后 fetch 收到本地 503 而非网络错误。实际读取 Android 9 / TLS 报告：默认关闭未加载 Go，12 个两类文件任务及 17 个请求已执行，失败探针未到原站，但整体失败，重试段尚未验收。从代码看新拦截器的 503 使 fetch 正常取得响应；改为交回已阻断的 WebSettings 网络路径，保留原拒绝断言和原站零到达检查，待重验。

已确认启动规则：新安装默认系统网络；用户启用后记住所选线路，冷启动先恢复线路再放行网页，失败不静默直连。配置解密失败保留原件，可明确选择本次使用系统网络。清除登录保留资产，不照搬 PoC 清账号时删除资产的行为。首批只开放已验证 RAW/TCP none、TLS、REALITY，其他协议不混入。

网络会话的初始化进一步放在账号记录成功读取之后；数据读取致命失败时关闭会话，避免错误界面仍启动核心。未改变任何旧数据或保活策略。新候选只在 Actions 产生，公开 Android 下载仍保持 alpha.22；原签名与个人设备升级在本批之外。

源码 `a6baf07` / `41690901023` 的第二批 build、六组设备与两端升级全部通过。实际读取八份证据：真实保存、5 类在途 gate、权限/发布失败/已有文件保护、HTTP/PAC 与角色恢复、冷进程实际 DOM 草稿恢复通过；旧版自身冷启动和 3000022→3000024 同临时签名替换后，原档案、Cookie/localStorage、加密恢复与资产保持。第三批后台资源任务在窗口开始前因“启用必须已加载 JNI”检查失败；现检查仅搜索 maps 的库名，未覆盖直接映射 APK 的表示。已读原 JUnit，不缩短 1800 秒或跳过启用/关闭条件，改为同时检查 APK 的确切 JNI 数据偏移、可执行映射与实际线路初始化状态，失败时保留 maps/状态并打印原始 JUnit，待重新测量。

后台计时进一步以真实 Home 后 Lifecycle.CREATED、既有后台服务运行确认作为前置，再发布采样 ready；完整 1800 秒窗口期间记录四页心跳与停止态，回前台前断言实际停止态保持至少 1800000 ms。被该修正替代的 `d8de2ec` / `41694058105` 专用运行已请求取消以免重复长测，日志保留；未改变正式保活策略或缩短窗口。

`3512023` / `41695140228` 的前台两端四状态、各两窗口全部通过，已读取原始 CPU/PSS。后台采样确实完成完整窗口（已读 Android 15 为 31 样本、1804.9 秒），但 JUnit 整体在返回前台后的 ActivityScenario.close 抛出状态为空的 NPE，不能记为完整通过。资源用例改用公开 Instrumentation 启动和同任务 Intent 返回前台，显式核对实际服务停止与 Activity 销毁，保留原始主异常和全部页面/草稿/1800 秒断言，不忽略 NPE。收尾始终拉回原始 resource-result/mapping/心跳用于失败定位；产品代码与保活策略未改，待重新跑完整验收。

`584e500` 的直接 Instrumentation 启动在一组 Android 15 中未取得页面，已恢复此前通过的 ActivityScenario 启动。查阅 AndroidX 原始实现后，resumeActivity 仅关闭测试辅助 Activity，不能独自撤销真实 Home；现用仅 networkCi 声明的正常 REORDER_TASKS 权限及公开 ActivityManager.moveTaskToFront 恢复原任务，不发不同启动 Intent、不创建新实例或使用隐藏 API。长测前先做一次短 Home/原任务返回前置来验证适配器，不计为长测；正式保持仍严格1800秒。收尾先实际停止既有服务再关闭同一受管场景，并验证实际销毁，所有业务断言保留。

`9ad96dd` 的 Android 9 原任务返回前置未满足生命周期/窗口焦点组合，尚未开始长测；现仅追加该前置的 taskId、生命周期、焦点、系统可访问窗口包名及合成界面文字证据，不放宽条件。Android 15 前台资源在 adb root 重启 adbd 时连接关闭，未进入采样；仅当等待设备后实际 UID 已为0才重跑原 preflight，其他错误照常失败，绝不以缺少 renderer/计数补零继续。

`d4f8af6` 的诊断增补在 CI 编译时报局部变量 `state` 与既有工作区变量重名，未进入设备测试；现仅将诊断变量改名 `lifecycleState`，不改变条件或产品逻辑，交回 Actions 编译与原矩阵。

`9ad96dd` / [41706366042](https://github.com/ForceMind/ChatGPT/actions/runs/41706366042) 的 Android 15 完整后台 JUnit 已通过（实际读取 `OK (1 test)` 和原始报告）：采样 1801.6 秒、实际停止态 1803.3 秒，60 份四页心跳均为 CREATED / 服务运行；返回同一受管 Activity、原文档和草稿、服务停止及场景销毁均通过。合成空闲 CPU 0.317%（一核100%），平均 PSS 140.1 MiB。Android 9 本轮仍待完成，不能据此扩大为两端通过。

`93620e8` 的 Android 9 短前置原始诊断为 taskId 3 / CREATED / windowFocus=false / 活动窗口 Launcher。核对 [AOSP Android 9 ActivityManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java) 与 [PhoneWindowManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java)：普通 Home 调用 stopAppSwitches，保护期5秒，期间 moveTaskToFront 直接返回且不会排队。现仅在短 Home 前置确认已停止后等待5500ms，再调用原任务返回；全部RESUMED/焦点/同一Activity、1800秒及收尾断言保留，不改变产品行为。该源代码解释与现象相符，修正尚待CI确认。

## 原接入方案（历史基线）

以下保留原设计基线；其中待批准及跨启动待确认表述以本段用户确认范围为准。状态与后续验证集中在本文更新，不把编译、合成与原签名升级证据互相替代。

独立候选源码 `254685aa9c0cf0b41d2fd22a2a75a9a0e5e81fe4` 的 [Actions 41636997369](https://github.com/ForceMind/ChatGPT/actions/runs/41636997369) 已回读十个必要网络作业通过。Android 9 / 15 的六个协议组合、八个路由套件各四项测试通过；96 个在途操作各处理一次，16 个关闭后请求未到原站。失败残片、任务释放、未知上传结果及页面保留均有实际证据。见 [在途故障记录](benchmarks/android-network-transfer-faults.json) 和 [历史能力与限制](android-network-plan.md)。上述基线是单空间证据；新增源码 `6df1bdd` 的 [Actions 41660146017](https://github.com/ForceMind/ChatGPT/actions/runs/41660146017) 已补齐三 Profile、四页与弹窗、两类文件同路由、系统 HTTP / PAC、账号清理及 Activity 重建的独立合成验收。见 [联合证据](benchmarks/android-network-joint-profiles.json)。正式文件选择器、外部 URI 权限 / 发布失败、真实服务与生产签名仍须正式适配后另验。

## 首批建议交付

沿用正式客户端的 System WebView、Profile、BrowserMemory 和现有账号空间，只增加应用全局一条内置线路的选择、停用、状态和文件路由。保持白灰黑与原生弹层，不加地址栏。账号 Cookie、localStorage、页面和弹窗归属继续独立，但所有账号共享所选线路；不承诺每账号独立 IP。

默认关闭内置线路，保持现有系统网络。启用后，网页和原生文件请求都走应用持有的 loopback HTTP 入口；停用后恢复系统网络设置。已有登录、恢复数据、账号 / storage ID、包名、签名和磁盘路径不迁移、不清理，不导入独立包的配置或浏览器数据。本批不混入 Gecko、语言、时区或账号迁移。

系统 VPN、免费节点 / WARP、订阅、WebRTC / UDP 路由保证不在首批。网页语音是否全部经过所选线路仍是另行验收项。

## 路由与账号边界

- `ProxyController` 是进程范围的覆盖，作用于该应用的 WebViews；设置覆盖会忽略系统 HTTP 代理，设置或清除后都要等待 listener 才开始新页面请求。这支持首批采用全局线路，不支持据此声称每个 Profile 各走一条线路。[AndroidX ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController)
- `Profile.getCookieManager()` 返回该 Profile 的 CookieManager，API 自 AndroidX WebKit 1.9 提供，调用前需动态检查 `MULTI_PROFILE`；不能把独立包的全局 `CookieManager.getInstance()` 复制进正式文件流程。[AndroidX Profile.getCookieManager](https://developer.android.com/reference/androidx/webkit/Profile#getCookieManager())

建议只有 `MULTI_PROFILE` 与 `PROXY_OVERRIDE` 同时可用时才开放内置线路；缺少能力时保留正式客户端现有行为，提示具体缺失项，不把账号降为共享空间。首批限定现有浏览器进程，不新增浏览器进程。

“使用系统网络”与 PoC 的 `addDirect()` 不同：正式停用应清除应用代理覆盖，例如等待 `clearProxyOverride` 回调后才放行新请求；原生连接恢复普通 `URL.openConnection()` 的系统路由语义，不使用 `Proxy.NO_PROXY` 强制绕过系统代理。系统 HTTP 代理 / PAC 的实际一致性必须专门验证，不能仅凭 API 文档判定已经通过。

## 拟涉及文件

下表均是**待批准的写入范围建议**，本次没有修改这些正式文件。Java 目录为 `android/app/src/main/java/com/forcemind/chatgpt/profiles/`。

| 文件 / 位置 | 当前基础与最小接入点 |
|---|---|
| `MainActivity.java` | `makeWebView/configureWebView` 已按 `WorkspaceState.profile(storageId)` 绑定 Profile，弹窗沿用所属账号；增加网络菜单、能力检查及当前 Activity 的状态观察，不重建已有 WebView。保留 `onStop` 按 Profile flush Cookie 的流程。 |
| `BrowserMemory.java` 与建议新增的进程级网络会话 | 核心归进程级组件持有，由 BrowserMemory 接入；不绑定账号或 Activity。旋转、切账号、detach 不停止核心；旧 Activity 的 observer 失效，新 Activity attach。正常后台不因网络功能冻结页面、关闭核心或新增保活服务。 |
| 建议新增的线路解析、配置与加密存储小组件 | 复用独立包已验证的严格 VLESS 支持矩阵、loopback 绑定、串行核心生命周期和状态失效保护，按正式项目命名适配；保留 Go 根上下文取消，避免 Stop 只关闭监听而遗留既有流。 |
| `AssetDownloads.java` | 现有 `start` 已捕获账号、账号 epoch 和发起 Profile 的 CookieManager；将每次连接创建接入同一线路快照，继续在每次重定向按目标地址读取同一 Profile 的 Cookie，并保持 `AssetLedger.importCopy(available)` 的发布校验。 |
| `DownloadHelper.java` | `choose/onResult` 已捕获发起 Profile 的 CookieManager，但 `Selection` 目前只有 URL、agent、cookies；补齐任务归属与代际，再接入第二个原生连接入口。等待系统文件选择器期间不能静默换账号或线路后继续执行。 |
| `AssetLedger.java` 与最小任务记录 | 沿用账号资产目录及导入事务；只补当前文件任务所需的取消 / 关闭 / 过期检查与一次终结，不改存储布局，不建设通用任务框架。 |
| `android/app/build.gradle`、必要的原生桥接 / 许可文件及合成测试 | 待批准后引入固定核心产物与最小构建配置，核对 ABI、R8、许可和正式包隔离；不带入 CI CA、控制端点、合成私钥或实验签名。 |

## 生命周期、切换与故障

建议进程级会话提供“系统网络、连接中、已连接、连接失败”状态。网页和两个原生文件入口共用同一线路代际；核心命令、状态检查继续串行执行，JNI 不阻塞 UI。已连接时的本地核心检查只证明核心存活，不证明网站可用；返回前台立即复核，休眠时不承诺实时执行。

切换前保留现有 WebViews、文档和草稿，不调用 reload，不重发请求。在途原生任务拒绝切换，或由用户明确取消并确认终结后再切；应用不能可靠判断所有网页消息流 / 上传是否结束，必须提示可能中断并取得用户确认。连接过渡期阻止新请求，等待代理回调后再放行；已有连接不保证立即迁移出口。

核心停止后，保留所选线路与代理覆盖，阻止主页面和弹窗的新网络请求，显示失败及手动恢复说明。不静默恢复系统网络，不自动重启、重连、刷新或重发。旧代检查和旧 Activity 的回调不能更新新会话。用户明确停用内置线路时，才按上述清除覆盖流程恢复系统网络。

若进程真正结束，沿用原工作区 / 草稿恢复流程，不承诺保留原内存中的网页实例；未完成上传只能保留待核对状态，不自动恢复发送队列。跨启动是否启用上次线路仍是待确认项，不能在方案中默认为已同意。

## 文件任务与结果不明

最小任务记录建议在发起时捕获 `accountId / storageId / taskId / routeGeneration / accountEpoch`，并绑定发起 Profile 的 CookieManager。完成、取消、关闭、账号清理与异常只能终结一次；迟到结果不得覆盖后续任务。账号切换本身不把任务转给当前账号；账号清理使其 epoch 失效。`DownloadHelper` 在文件选择返回时再次校验捕获的归属和线路代际，过期则明确结束，不能悄悄改用新账号或新出口。

资产导入继续在发布前检查任务可用性，失败残片不得计作成功。系统 `ACTION_CREATE_DOCUMENT` 的用户 URI 与应用私有临时文件不同：必须另验 URI 写入权、创建归属、关闭流与失败清理；只能处理本次任务拥有的不完整输出，不能照搬 PoC 的私有文件删除策略或删除既有用户文档。取消、关闭和核心停止的真实终结时限须在正式适配后重新验证，不能用常规读超时冒充主动终止。

保留独立包已补的 closed guard、同步请求跟踪和上传结果不明语义。请求体已尝试发送、服务端可能已处理但未收到完整 ACK 时，应记录类似 `UnknownAfterBody` 的结果并提示“可能已发送，请先检查原对话”，不能断言“肯定没发出去”。本地 `opId` 只用于记录和合成计数，不是第三方服务端幂等凭证；手动重连不能自动重发或新建对话，用户核对原对话后再决定是否手动重试。网页上传不能伪装成已被原生任务记录完整追踪。

## 数据保护与回退

线路配置使用正式应用私有目录中的**独立新文件和新 Keystore 别名**，不复用账号状态文件、恢复文件或独立包别名；凭据只加密保存，日志只含脱敏状态与任务阶段。停用保留配置。配置不可读时保留原文件，并要求用户明确选择恢复系统网络，不能把解密失败自动解释为用户已同意改路由。

回退入口是停用内置功能、等待清除覆盖并恢复两个原生入口的系统路由；默认关闭使新安装维持既有行为。代码回退不要求卸载、重新签名、清账号或移动 Profile。正式升级 / 降级的可行性另按原证书与版本规则验证，不在本文承诺。跨启动是否恢复上次启用状态仍待确认。

## 分批交付与验收门槛

| 批次 | 用户可审阅 / 操作的成果 | 必要验收与边界 |
|---|---|---|
| 已完成的独立包基础 | 在途文件故障修复候选、源码 SHA、Actions 与原站证据 | 既有协议 / 身份负例保留；真实下载 prefix 后停止 / 取消 / 关闭，残片不成功、清理归位、计数释放、回调至多一次；上传已处理一次但 ACK 挂起时客户端终结且不重发，显式恢复使用新操作。具体计数和失败迭代见上述证据；不据此认定多 Profile / 正式文件出口已通过。 |
| 已完成：独立包联合验收 | 三个实际隔离 Profile、至少四页及所属弹窗；与正式生命周期、两类文件出口相匹配的合成支架 | 同时检查 MFP 与代理覆盖；Cookie / localStorage / 弹窗归属隔离；网页与两类原生连接同线路，重定向 Cookie 不串账号；系统代理 / PAC 与停用回调一致；账号清理 epoch、取消、关闭和真实 Activity 重建后，原 WebView / 草稿不重载、旧回调失效。现有单空间网络矩阵不能替代此项。 |
| 待批准：正式客户端最小接入候选 | 默认关闭的网络入口、线路管理、状态与两类文件适配；明确的停用回退入口 | 按上表批准文件实施，GHA 构建与合成回归；原账号空间、恢复及资产契约不变，依赖许可、ABI / R8 和候选隔离通过。正式版本号、测试用户范围和交付方式由该批批准决定，不直接发布。 |
| 另行授权的验收 | arm64 实机 / 厂商环境、真实节点与 ChatGPT、正式原证书升级；如纳入语音则增加 WebRTC / UDP 专项 | 合成 x86_64 结果不替代这些证据；真实服务、设备操作和签名分别取得授权，生产签名私钥仍不上传 CI。 |

独立 arm64 APK 约 40.7 MB 只作为原生核心成本的量级参考，不能直接加到现有约 7 MB 正式 APK 上作成包结论。正式 AAR、R8、ABI 拆分、资源压缩与原证书升级均需获批后测量和审核，沿用第三方各自许可。

联合多 Profile 的独立合成验收已通过；正式客户端最小接入尚未批准。其余待确认项为：是否保存跨启动启用状态；正式候选的版本、分发与签名验收安排；真实设备 / 服务和语音网络是否纳入下一批。以上均不视为本次实施或上线授权。

## Android 15 前台采样专项（2026-11-04，进行中）

用户已批准继续修复采样失败。首次只修取证：主异常进入 cleanup 前保存 first-command-failure.json 和 report.error，保留 guest logcat、主机内核 / 内存 / 进程记录。通过现有专用工作流的 foreground_diagnostic 手动入口复用 d0b8db4 的构建和合成节点，只有 API35 前台四状态，原60秒窗口、600chunk及完整进程计数断言不变；不重编译正式客户端、不重复后台长测。采样源码与安装包源码分别记录，未将记录改进当作根因修复。

专项首轮 `3759a62` / Actions `41786067631` 的四状态八个前台窗口完整通过；实际读取 comparison.complete=true，guest/host日志未检出OOM或崩溃特征，仍不认定根因已修。下一轮唯一控制变量为关闭持续adb logcat连接，保留结束时日志、主机记录及首错；增加未进入采样循环时的安装/卸载等终端异常原始stdout/stderr记录。仍只跑API35前台，不追加后台。

### 本轮采样专项结论（尚未完成原故障根因修复）

- 已证实的日志问题：旧失败产物的 command-failure.json 被 cleanup 的缺失文件读取覆盖。从代码看，现于 cleanup 前保存 first-command-failure.json / report.error，安装和卸载等阶段的终端异常另存 first-terminal-error.json（含stdout/stderr），不吞异常、不补零。
- [41786067631](https://github.com/ForceMind/ChatGPT/actions/runs/41786067631)（持续logcat）及[41787762471](https://github.com/ForceMind/ChatGPT/actions/runs/41787762471)（关闭持续logcat）均完整通过四状态、各两窗口；实际读取两份 comparison.complete=true，原60秒、600chunk和完整进程计数断言保留。未重复后台长测。结果与源码分开记录于JSON。
- 两轮均未复现原失败。首轮guest/host日志未检出OOM或崩溃特征，不能据此排除过去的故障；第二轮未产生first-error文件是成功运行的预期，不代表错误分支已实际验过。仍没有带完整首错的失败样本，不能声称原间歇故障已定位或根治。
- 后续定位入口为现有工作流foreground_diagnostic=true，复用既有合成APK；保留两种日志连接条件。没有新失败证据时不继续盲目重跑，也不以重跑通过代替根因证明。个人数据、main与公开发布均未操作。

采样专项继续：从代码发现cleanup抛错会掩盖主异常并跳过report.json写入。已将首错不可覆盖、主/cleanup双错误记录和原始二进制stdout/stderr独立保存加入采样证据函数，新增三项纯Python合成故障用例（主错+cleanup双错、仅cleanup失败、安装卸载输出完整保存）。只在Actions运行证据契约与语法检查，不启动模拟器；原Android15间歇故障仍未复现。

证据契约验证 `065bb53` / [Actions41789679934](https://github.com/ForceMind/ChatGPT/actions/runs/41789679934) 已通过：实际读取3项unittest均ok、语法检查成功，其余模拟器/构建/后台任务均skipped。合成故障验证了首错不被二次错误覆盖、主异常保持传播、仅cleanup失败仍失败、report始终保留，以及安装卸载二进制输出逐字节还原。它证明错误留存修正，不证明原间歇性adb/dumpsys故障已根治；没有新失败样本时不再盲目重跑。

用户已于2026-11-04授权扩大到adb/模拟器原生复现。新专项先不启动App或浏览器：同一API35模拟器依次执行shell身份40次、root后以UID2000执行40次、并行长shell连接下40次原dumpsys命令；任何非零退出、超时或输出缺失均失败，完整原始输出、退出码及guest/host/模拟器内核日志保留。不运行后台长测，不改正式App。原层复现后再决定必要修复与原前台回归。

原生专项75ac674 / Actions41799173089通过。实际读取125条命令记录（含120次dumpsys与环境/root命令），三阶段均未复现；guest/host日志未检出致命特征。下一步只用既有旧基线APK运行原四页空闲/600chunk流窗口，每次原快照前增加三次相同dumpsys，以隔离页面运行下的Binder/adb负载；完整计数、窗口及进程断言不变。该结果显式diagnosticOnly，不作为CPU性能基准；不运行后台。

旧基线页面压力专项ff0ddb5 / Actions41799922323通过，额外dumpsys负载未触发故障。下一步保留该离线页面前置后，交叉执行5轮仅CI .debug.test包卸载重装与40次UID2000进程转储；验证实际时间重叠，任何非零/超时/缺失输出仍失败。该边界对应旧失败曾发生的uninstall步骤；不启动其他网页，不碰正式包及个人设备，不运行后台。镜像版本假设交叉核对出现反例：20261101.360.1和20261025.353.1各有通过与失败，模拟器同为37.2.13.0，不能归因为镜像版本，未切换版本。

并发专项调用修正：41801290280虽工作流成功，检查实际产物缺少package-churn/result.json、日志没有卸载脚本调用，不能作为并发证据。原因是脚本插入普通resources任务而非手动foreground_diagnostic；已恢复普通资源任务原配置、将调用挂入正确手动任务，并加实际结果/10个生命周期命令/40次转储/正重叠量的强制门槛，缺任一即失败。原始成功记录保留，但明确不计作并发通过。

### 扩展 adb / 模拟器专项实测结论（原故障仍未定位）

| 场景 | 已读取的实际证据 | 结果 |
|---|---|---|
| 原生shell、root后UID2000、并行长shell | [41799173089](https://github.com/ForceMind/ChatGPT/actions/runs/41799173089)，120次进程转储 / 共125条命令；完整字节输出与guest/host日志 | 全部退出0；未启动App/网页，未复现 |
| 旧基线4页与进程转储负载 | [41799922323](https://github.com/ForceMind/ChatGPT/actions/runs/41799922323)，实际60秒以上空闲和600chunk流窗口、42次额外进程转储 | 完整通过，diagnosticOnly，不当CPU基准 |
| 测试包生命周期并发 | [41802479660](https://github.com/ForceMind/ChatGPT/actions/runs/41802479660)，实际5轮卸载/重装、40次转储、53条命令全部退出0；独立重算40次均有时间重叠 | 实际并发结果门槛通过，未复现 |
| 首错与超时输出保留 | [41801297489](https://github.com/ForceMind/ChatGPT/actions/runs/41801297489)，4项合成故障测试及脚本语法检查 | 全部通过，无模拟器 |

41801290280仅基线窗口通过，因错误调用位置没有执行包并发，明确不计作并发证据；错误与修正均保留。镜像版本关联也存在通过/失败反例，不作根因结论。原生日志、模拟器内核控制台、逐命令完整输出/时间/退出码均已留在Actions；未截图、未访问真实ChatGPT，未动个人登录数据、main或公开发布，未重复后台长测。

本轮只证明这些具体复现场景未触发旧故障，以及确定的错误留存缺陷已修。**原Android15间歇采样失败尚未定位、也未验证根治。** 当前仍缺少新取证路径捕获的真实失败样本；没有新证据时不把相同成功场景不断重跑，也不凭猜测修改采样断言、切换环境版本或补零。

## 可独立安装测试版（2026-11-04）

用户授权真实节点由自己配置验收。已在Actions41832194012构建并签名独立正式逻辑变体，包名com.forcemind.chatgpt.profiles.networkpreview、名称ChatGPT Studio测试版，与正式版及旧独立PoC包名均不同；77项单测/0失败、lint与APK签名/隔离/16KiB校验通过。仅交付Actions产物，临时测试签名不保证后续覆盖升级，无个人设备或实站验证。详见[安装说明](android-network-standalone-preview.md)与[最终收据](releases/android-network-standalone-preview.1.json)。

## 独立测试版 GitHub 预发布（2026-11-05）

经用户明确授权，已将既有Actions41832194012原APK和完整ZIP发布为[独立Pre-release](https://github.com/ForceMind/ChatGPT/releases/tag/v3.0.0-alpha.24-candidate.1-standalone)，标签绑定实际构建源码aff127aecc0e1f71b497f90a0d1f115ece31dd88，不设Latest。四附件服务器SHA256与原产物一致，发布后匿名下载前缀和发布页均核验。未重建或改签，固定证书/新ActionsSecret仍未实施；未合并main、替换官网正式下载或更新清单。此前“仅Actions、未公开”的记载是当时状态，以本次[发布记录](releases/android-network-standalone-preview.1-publication.json)为后续状态。
