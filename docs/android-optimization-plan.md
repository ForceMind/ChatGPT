# Android 常规客户端：第一批优化对照

2026-11-09：用户批准先给具体方案，再在 Actions 制作候选与对比验证；保留登录、恢复数据及标签保活，不改正式客户端。用户另已明确批准 Android9/15 的合成账号/热标签/导航/恢复、网络/文件/Skill及固定窗口 CPU/PSS 对照；不截图、不访问真实 ChatGPT/节点、不重复30分钟后台长测、不安装个人设备。

## 目标与基线

基线为正常 Release 2026.11.9 中的 Android 2026.11.08-1923/code3000035，SHA-256 `177ef874c6c904a653aecb3ffc61a76ef45ebc8e69cb80c6addbdb9e74806259`。该 APK 230.72MiB，原生库86.8%、DEX6.9%，常规 release 未开启 R8。此前 PoC 的缩包/CPU结论不作本批结果。

第一批用相同产品源码、依赖、Go核心、ABI、日期版本和工具链生成三个候选：baseline（原设置）、r8（仅 Java 字节码优化）、resources（R8＋资源压缩）。比较当前重新构建的 baseline，而非直接把两次环境不同的 APK 差异当作优化收益；已发布 APK 作为独立来源锚点，不改签、不替换。

## 实现范围与保护

- 新增专用 `android-regular-optimization.yml`，复用现有完整常规客户端的24个job结构和全部22个功能job，不删断言、预算、升级与失败路径。原工作流保持不变。
- 仅 Actions 临时检出目录用脚本覆盖构建开关、JNI keep规则和专用工作流证据身份。保留覆盖前后文件哈希与具体差异，明确是带实验 overlay 的构建，不能冒称未修改源码的正式包。
- 不修改仓库 `android/app/`、核心版本、证书、AppID、数据格式或后台策略。原签文件不上传，临时 CI key只在 runner；CI同签升级用于合成数据，不冒充原生产证书覆盖升级。
- 产物为未签名审阅 APK、CI临时签名测试输入、mapping/usage/seeds、来源和大小报告，按用途明确标记，不提供为手机正式版覆盖安装。可安装原签版本需后续单独交付，本批不操作原私钥。
- R8先保留 Go JNI导出及其生成桥；依赖自带 consumer rules保留。遇到反射/脚本桥错误读原始异常、mapping及缺失符号后增加有依据的精确规则，不用全包keep掩盖问题，不使用ignorewarnings绕过缺类。
- 三候选使用同一源提交、版本字符串和核心构建输入；每份 APK记录大小、SHA、压缩组成、证书用途、ABI和16KiB对齐，使用同样功能夹具。除实验差异外的构建输入变化应中止对照。

## 验收与衡量

1. 构建、原单元/lint、APK结构/权限/标识、无CI信任的审阅包检查，保留首个错误和原产物。R8失败不能直接算优化无效或跳过。
2. 原功能矩阵：Android9/API28与Android15/API35合成隔离、已有登录/密文/资产的CI同签升级、热标签实例不变、导航/草稿保护/恢复、RAW/TLS/REALITY网页上传下载、本地导出与Skill/资产任务。真实证书和真实服务不在本批。
3. 资源对照另用同样候选、同样系统镜像/内存/GPU/页面数及预热时长。拟固定1/4标签，空闲与合成流式各预热30秒、采样120秒，记录App及网页子进程CPU/PSS、采样失败/缺失、原始样本、页面实例和心跳；每模式两窗口。既有30分钟后台结果不重复运行。未执行前不写成已获性能结果。
4. CPU采用一核100%口径，PSS以MiB报告；分OS/场景/窗口给原值和相对差异，不把模拟器值当手机最低配置。仅在同一APK来源、设备配置和负载可比时作对照；先保留异常值，不以删样本或放宽断言变绿。
5. 包体给实际节省bytes/%，无预设节省承诺；功能不退化、数据保留及保活断言为硬门槛。资源无稳定收益时如实报告，不为缩包强行更换浏览器内核或停后台页。

## 原生核心后续评估

`main/distro/all` 与31.96MiB网络SO是下一变量；当前配置从代码看使用HTTP入站、VLESS出站及RAW/WS/TLS/REALITY。需要列出注册器、DNS/传输/安全依赖后，才能在单独实验中尝试最小注册；本批前三候选不同时改Go核心，不删除功能，不简单压缩SO破坏加载和16KiB对齐。Gecko主库也不能由R8直接裁剪，先保留既有内核与账号兼容。

## 当前进度

正式候选选用单独 R8，`2026.11.09-1236 / 3000036`（`3a40aa5`）在 `42562845595` 的 24 项检查全部通过。非调试 arm64 与 x86 对照的 DEX 一致，两包在本机用原证书签名，非签名内容逐项一致。arm64 签后 228,463,145 bytes，SHA-256 `032482a06cd408bf32c58b9186e3c6bf4f80a98f877e985100a8d5f0c1231e58`。当前公开版 `2026.11.08-1923 / 3000035` 的 x86 对照重签后与原验收收据逐字节一致。

独立原签测试 APK 已在 `42565722911` 构建并本机签名。旧端用原有业务写入方法建立合成数据，新端通过持久化契约与独立 WebView 支持库核对，避开正式 R8 APK 已移除的内部符号。待 Android 9/15 原证书覆盖验收通过后再发布。未上传原私钥、未更新官网和正式 Release。

同源对照采用 `89944ec`、版本 `2026.11.09-2200`。R8 `42549921734` 在首轮 Android 9 旧 WebView 首次后退触发草稿保护取消，复跑该任务通过，最终 24 项通过。未压缩基线 `42552082989` 首轮通过相同导航任务，复跑其 Android 9 账号恢复和 Android 15 Gecko RAW 后最终 24 项通过。现有结果不足以将首次后退取消归因于 R8。资源压缩候选 `42554252302` 首轮 Android 15 Gecko TLS 未进入 CONNECTED，WebView 工作台的渲染失败恢复用例未就绪，复跑两项后最终 24 项通过。三组均保留首轮日志。包体比较 `42557781353` 和固定窗口 CPU/PSS `42557785291` 已启动。原证书覆盖安装和正式发布仍未完成。

包体比较 `42557781353` 已通过。基线 241,899,411 bytes，R8 228,431,150 bytes（减少 5.568%），R8 加资源压缩 228,336,506 bytes（减少 5.607%）。三组原生库逐项摘要相同。CPU/PSS 首轮六组通过，Android 9 四标签流式组在 adb root 阶段断开，Android 15 单标签空闲组在首个 Gecko 页面观察超时。两组以相同输入复跑，失败阶段不作为采样结果。

CPU/PSS 复跑后八组通过。汇总 `42562185770` 校验了同机、同系统镜像和工作量的 48 个采样窗口，24 组配置全部可比。每格为一核 CPU 百分比 / 平均 PSS MiB。

| 系统 / 标签 / 负载 | 基线 | R8 | R8 加资源压缩 |
| --- | ---: | ---: | ---: |
| Android 9 / 1 / 空闲 | 12.97 / 445.81 | 13.13 / 410.49 | 13.04 / 411.49 |
| Android 9 / 1 / 流式 | 25.46 / 434.75 | 25.88 / 461.83 | 26.25 / 459.41 |
| Android 9 / 4 / 空闲 | 79.28 / 741.47 | 79.18 / 733.66 | 75.26 / 735.56 |
| Android 9 / 4 / 流式 | 61.83 / 773.63 | 60.85 / 787.10 | 58.80 / 765.74 |
| Android 15 / 1 / 空闲 | 8.22 / 426.88 | 8.25 / 444.86 | 8.27 / 447.38 |
| Android 15 / 1 / 流式 | 19.37 / 446.62 | 19.74 / 432.05 | 19.52 / 440.00 |
| Android 15 / 4 / 空闲 | 39.08 / 784.37 | 39.01 / 766.83 | 39.27 / 742.95 |
| Android 15 / 4 / 流式 | 58.05 / 814.28 | 56.80 / 835.21 | 58.44 / 792.19 |

实验支持缩包，未见各负载一致降低 CPU 或 PSS。R8 单独节省约 12.84 MiB，资源压缩再节省约 92 KiB。正式版下一门槛是使用高于当前公开包的 versionCode，验证原证书覆盖升级及正式配置启动。当前结果来自临时证书合成 CI 包，原证书、正式 App 与官网下载未修改。

复验 `42547212114`（`fd2e51e`）已通过 Android 15 完整账号与恢复验收、双系统 Gecko 本地导出，以及 Android 15 Gecko 附件与 Skill。两个导航任务的旧 WebView 测试入口均因反射找不到 `BuildConfig` 失败，下一次复验仅为测试包保留 `ENVIRONMENT_TEST`。Android 9 的 Gecko 附件任务触发未归属的 native crash 门槛，原日志记录的进程标签为 `cmd`。Android 9 WebView TLS 未生成应用验收报告。两项继续保留在完整回归。

复验 `42544883808`（`a3749e9`）在 Android 15 通过旧版种子写入、旧版冷读、新版验证、新版冷读四段。Gecko 已进入会话初始化，原 `DebugConfig` 崩溃未在该日志出现，但打开首个标签时 `EnvironmentBridgeService` 因缺少反射方法 `CIBuild.installLocalExportProbe(File)` 退出。Android 15 WebView RAW 的首个网络用例通过，后续文件安全用例因缺少 `CIBuild.installHandoffProbe(File)` 失败。两个方法的精确保留规则纳入下一次 R8 复验。恢复、网络和资源对照仍是发布门槛。

本批结论：不采用两组优化更新正式客户端。三包的原字节对照、完整SHA及14个原生库逐项校验已完成（42533750158）；baseline完整24job通过，R8和资源压缩未通过完整功能门禁。最新R8复验42531762889也失败；合格配对CPU/PSS没有形成。保留原App、登录、密文和标签保活策略，不修改main/官网/手机或使用生产密钥。以下保留全过程和原失败。

首轮实验源码 `0250e2b` 已推 dev；共用上海日期版本 `2026.11.09-0252`，baseline `42512156694`、r8 `42512160093`、resources `42512163781`。三组正在构建与完整回归，尚无通过或优化收益结论。另准备独立比较器，严格核对源/版本、原生库字节和完整功能结论；CPU/PSS 仍待专门测量，不混入静态缩包结果。

首轮 R8 `42512160093` 与 resources `42512163781` 的人用/CI APK编译成功；build在网络CI包的DEX具名标记断言失败：`gecko-network-package.py:43`要求`EnvironmentNetworkFilesProvider`与`GeneratedNetworkTrust`。代码确认Trust通过Class.forName/getField("PEM")读取；仅为这两个CI专用类补精确保留规则，保留原DEX检查，不使用ignorewarnings。尚不能据此宣称网络运行通过。baseline `42512156694` 的功能矩阵继续，不重复构建。复验允许单独的harness提交，但比较器须核对相同产品基线、版本核心字段、构建覆盖前hash及原生库逐项SHA；保留各自真实harness/sourceSHA，不伪造GITHUB_SHA或删除来源字段。

资源采样另起专用工作流，只有来源候选完整功能成功才允许采样，复用原APK不重建。每系统串行四个独立合成用例（1/4页×空闲/DOM流式），各预热30秒、两个120秒窗口；代理关闭，网络路由由原功能门禁独立验收，不据此宣称网络核心运行时性能改善。窗口内实例、草稿、心跳及流式计数必须持续，汇总App与归属明确的网页进程，缺失或进程替换直接失败保留raw，不以App单进程或补零估算。各独立资源用例之间只清理实际SHA匹配的CI测试APK/合成空间；升级路径中不清理，正式数据不在模拟器。保留系统/夹具观测开销与实际流式工作量，以可比性决定是否能给性能结论。

已实际读取首轮R8 mapping：GeneratedNetworkTrust被改名为dx，Provider保留原名；artifact13629483368原证据保留。baseline42512156694完整24job成功；精确保留后r8复验42513553896、resources复验42513557648仍在运行。baseline原APK已进入资源运行42513822767（Android9/15），未重构/重签/重建；CPU/PSS结果仍待实际运行。

资源首轮42513822767在两系统启动模拟器前被既有脚本明确允许列表拒绝，尚未运行App或进入采样；原错误“Only the approved ... script may run.”保留。不移除启动器限制，只在资源工作流临时检出中增加用户已批准的唯一新脚本路径，并提前创建诊断回执，避免后续无产物掩盖首错。原生产/旧启动器源码不改。

新证据：resources复验42513557648的accept28在旧版seed前因AndroidJUnitRunner/FileTestStorage引用混淆类nt而崩溃，原公开alpha.22未含该类。不能把新版优化后的测试APK直接用于旧未优化App。现构建未混淆的旧侧测试APK，在旧版seed/冷读后，仅替换目标App并将测试APK更新为与新App映射匹配的版本；四阶段、原始SHA、同签/UID/记录保留断言不变，不重建旧App、不清理升级数据。不同侧测试APK均核对临时证书、SHA和明确用途。

资源首个1页空闲样本已完成两个120秒窗口，但跨用例重新建夹具后流式页未出现HTTP请求，整组失败。保留partial，不宣称测量完成；后续按原批准的四场景拆为独立新模拟器，并在每个OS/页数/负载组合的同一runner上顺序跑baseline/r8/resources，消除独立runner硬件差异。窗口数量/预热/预算和全部断言不变，只修正实验隔离与匹配方法；不把独立用例串行夹具问题宣称为产品回归。TLS35的首错为probe-owned-target约2046ms、coreRunning=true、originalGeneration=true、SocketException/reset；与既往现象相似但根因未知，未机械重试或宣称修复。

最终资源方法：8个OS/页数/负载组合，各自同一runner、三个版本各新模拟器；全部每版本每场景预热30秒、2×120秒不变。采集host/guest CPU资料、同一采样脚本SHA及可见前台页证明；初始草稿改为固定非空合成文本，记录真实工作量和完整归属进程。新用例不再在同一App安装周期中更换NSS夹具CA；原串行失败保留，不将消除试验污染写成修复产品。

拆分测试后旧版seed和冷读已实际通过，新版verify前的AndroidJUnitRunner缺失androidx.tracing.Trace。准备仅ENVIRONMENT_TEST构建引用的精确tracing保留规则，不将CI专用依赖keep引入人用包。另对getSimpleName()识别Slot的反射契约执行只读元数据诊断42521215095；不初始化App、不打开页面、无截图，结果未出前不凭猜测加大范围keep。静态构建日志盘点已落在benchmarks/android-optimization-preliminary-build-sizes-20261109.json，最终字节和功能仍待Actions核验，不宣称采用或CPU收益。

只读诊断42521215095已通过并读到真实结果：Slot0/1的完整类名存在，但simpleName分别为EnvironmentBrowserActivity$Slot0/1，enclosing=null；Trace类不存在。结合slot()对简名Slot前缀的依赖，已确认R8嵌套身份契约被破坏。仅在实验规则保留EnvironmentBrowserActivity外层和Slot子类身份/构造及InnerClasses/EnclosingMethod，不保留整个业务包；CI专用Trace库通过environmentTest条件单独保留，人用包不添加该CI规则。新增构建内只读元数据smoke，先断言实际Slot简名/外层及Trace存在，再启动22个功能job，避免昂贵无效矩阵。原App源码未改。

新证据：8576版本Slot/Trace实际APK smoke均通过，但后续AndroidJUnitRunner因kotlin.jvm.internal.Lambda缺失而在测试前崩溃。CI与主APK共享运行时依赖，R8可移除App不用而测试运行器使用的类。改为从真实DEX的类继承/接口和方法/字段引用计算精确共享测试运行时边界，以同源码未压缩CI APK的实际类型集作来源；仅CI规则保留这些明确的第三方类，不保留整个业务包，缺失业务类型直接失败要求人工审查，最多三轮显式收敛且保留每轮清单，不使用ignorewarnings。人用规则不扩张。增加构建内运行器启动smoke（不启动Activity/页面、不充作功能通过），避免再次启动昂贵而无法执行测试的矩阵。旧/新侧测试分离、四阶段数据断言及22个功能job不减。

静态共享门禁42524467760按设计提前拦截了16个测试直接依赖的应用符号，没有启动完整矩阵。已读现有WorkbenchTest/NetworkIntegrationTest/EnvironmentUpgradeSeedTest/BrowserChromeNavigationTest，确认它们直接调用转换、文件/能力目录、网络配置值及CI探针/时序类型。这些类型在优化后可内联/枚举拆箱，保留旧测试入口需显式CI契约。仅将报出的16个精确类型列入带理由白名单，保留CI API并允许方法优化；其他未审查业务符号缺失仍失败。人用规则不引用这份CI清单，不能把CI包运行等同人用/原签APK验收；后续正式迁入必须单独验收人用包。没有全包keep或删除任何功能断言。

R8共享语义运行时及真实运行器smoke已通过，但网络构建的新静态门禁报EncryptedPayloads内部类型。人工读取EncryptedPayloads/DevicePayloadKeys确认AES/GCM/NoPadding、HP01、handoff-v1/page-recovery-v1域、记录id/AAD及包名密钥别名均不依赖类名。仅将该测试可见返回/密文类型增加为精确CI契约，允许方法优化，不改算法、域、别名、记录或人用规则。人用优化后加密/原签升级仍要另验。未知其他业务符号仍失败；当前并未采用优化更新正式客户端。

84eedcf完整R8矩阵新首错：accept28的优化后WorkspaceState List字段类型/成员ABI改变（NoSuchField c:List），WorkbenchTest.setup的WorkspaceState.fresh被优化掉（NoSuchMethod fresh）。这是原测试直接链接私有模型API的边界，不能删数据保留或工作台断言。仅CI规则为WorkspaceState及Account/Tab保留字段形状和可调用方法入口，方法体仍允许优化；人用规则不加，保持真实性边界，不据此宣称人用包升级已验收。MainActivity等其他业务类不整体keep；出现未知接口仍保留失败审查。

完整矩阵实际新首错为DevicePayloadKeys.cipher方法被内联/删除，测试无法直接链接其旧ABI。CI契约改为从未优化测试DEX的实际方法/字段引用生成精确成员清单（来源仍限未优化主APK真实定义），不保留整个业务包，不把CI规则用于人用包。另人工读现有NetworkIntegrationTest反射调用，仅保留MainActivity的change/showCurrent/downloads/finishAccountChange及NetworkSession.jobs、HandoffRuntime.controls这些明确入口，不保留MainActivity全部成员。所有原始断言保持；此为测试可链接性边界，不等于生产优化包完全运行已验收。

## 本批验收结论：保留原版

[Actions 独立比较 42533750158](https://github.com/ForceMind/ChatGPT/actions/runs/42533750158) 实际读取三个 arm64 未签名审阅 APK。体积、SHA-256、同一冻结版本、共同产品源码及14个原生库逐项SHA通过；仅完整功能门禁因优化组失败而按设计退出非零，报告已上传。最新 R8 审阅包与表中 R8 原始字节相同。详见[原样比较 JSON](benchmarks/android-optimization-comparison-20261109.json)和[最终结果回执](benchmarks/android-optimization-outcome-20261109.json)。

| 实验 | APK bytes | MiB | 相对 baseline | 功能状态 |
| --- | ---: | ---: | ---: | --- |
| baseline | 241899411 | 230.69 | — | 42512156694：24 job 通过 |
| R8 | 228414766 | 217.83 | -5.574% | 42531762889：构建和两系统 WebView 工作台通过；20功能job失败、交付job跳过 |
| R8 + 资源压缩 | 228320122 | 217.74 | -5.614% | 42521770270 构建完成，完整门禁失败；后续受同类测试契约限制的旧组已停止 |

缩包主要来自 DEX（15.93→3.071MiB）；额外资源压缩仅节省94,644 bytes，约92.4KiB。Go和Gecko原生库未减少，未实施裁剪。这里比较的是重新构建的同批未签名 baseline，不直接与发布签名包做差。审阅 APK 不作为手机覆盖安装包。

最新 Android15 运行的 seed/旧版冷读/新版验证/新版冷读四段 JUnit 实际通过；后续 `/c/a1` 页面观察超时。WebView 网络任务另见实际 `AbstractMethodError` 回调，Gecko handoff 在网络配置未达到 CONNECTED 时结束。没有将这些现象归为数据丢失、确定的 R8 业务回归或全部已修复。旧/新测试 ABI、共享依赖、Slot 元数据的首错和修正保留；CI 变体仍不等于人用原签包运行验收。

baseline Android15 单页空闲完成两个约120秒窗口：CPU 8.23% / 8.38%（一核100%，App与归属网页进程）；PSS 435.59 / 466.11MiB。其后流式用例加载失败，且优化组未过功能门禁，因此没有完整配对 CPU/PSS，不计算优化比例。此局部数据带合成观察开销，不代表真机或真实 ChatGPT。未重复30分钟后台长测、未截图、未访问真实账号/节点。

当前结束该轮候选资格判断：不扩大 CI keep 追求绿灯，不改正式客户端。若继续评估 R8，先单独给出减少私有 ABI 依赖的黑盒验收方案，再谈原签与人用包；本报告不授权启动额外浏览器场景或正式迁入。真正 CPU/PSS 热点定位与原生体积裁剪仍列后续，不能由 R8 的静态缩包推导运行收益。
