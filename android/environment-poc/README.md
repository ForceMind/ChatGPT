# Profiles Environment PoC

独立 Android 环境实验。包名为 `com.forcemind.chatgpt.environmentpoc`，使用自己的私有目录与调试签名，不导入正式 App 的代码、Cookie、账号、密钥或资产。安装它不会更新正式 App。它只打开 loopback 测量网页，不提供真实账号登录。

源码 `4febb00` 的[完整任务](https://github.com/ForceMind/ChatGPT/actions/runs/39896542731) 已通过，测量与覆盖边界见[可复核报告](../../docs/benchmarks/android-environment-poc.json)。8 标签 24 个上下文通过 330 秒持续检查；资源代价仍需评估，正式 App 内核未替换。arm64 调试 APK 已归档至 `../release/environment-poc-4febb00/`，签名与校验和验证通过，真机和真实登录尚未验收。

## 实验边界

- 固定 GeckoView `158.0.20261021001945`，账号 A / B 各有一个 App 进程、Runtime 和持久 profile。
- A 请求 `zh-CN / Asia/Shanghai`，B 请求 `en-US / America/New_York`。使用 Runtime locale、进程 `TZ` 与进程内 Java 默认值，不修改 Android 系统设置。
- 远程调试关闭，不注入修改 `navigator`、`Intl` 或 `Date` 的补丁。测量主文档、跨来源 iframe、Dedicated Worker、新页及用户手势弹窗，检查网络语言、冬夏偏移、本地时间构造、Cookie / localStorage 和重建 / 切换。
- 1 / 4 / 8 标签分别为 A1、A2+B2、A4+B4，使用同样的 240 条模拟消息和 6 张 1024×768 图片。PSS 汇总 App 命名空间中的父进程与子进程；CPU 为 `top` 两秒增量样本的单核百分比，排除首个累计样本。8 标签另做 330 秒上下文持续检查。
- locale 按语言、文字和地区核对，`zh-CN` 与最大化后的 `zh-Hans-CN` 为同一预期环境；navigator 与网络头仍须请求 `zh-CN`。第一方页面必须保存自己的 Cookie；跨站 iframe 的 `SameSite=Lax` Cookie 按浏览器策略被阻止时单独记录，任何上下文出现另一账号的 Cookie / localStorage 都判失败。参见 [ECMA-402 Locale maximize](https://tc39.es/ecma402/#sec-intl.locale.prototype.maximize) 和 [HTTP SameSite 创建规则](https://httpwg.org/http-extensions/draft-ietf-httpbis-rfc6265bis.html#samesite-attribute)。
- 模拟器、离线素材与探针本身均影响数字。这些数字不能直接代表真机、真实 ChatGPT 或系统内存不足时的保证；实验通过也不等于正式迁移完成。

## 构建与运行

这是独立 Gradle 根工程，正式 `android/settings.gradle` 不包含它。使用 JDK 17、Android SDK Platform **37.0**（安装包名为 `platforms;android-37.0`）、AGP 9.1.1、固定校验和的 Gradle 9.3.1：

```sh
cd android/environment-poc
./gradlew --no-daemon assembleDebug lintDebug
```

调试 APK 输出到 `build/outputs/apk/debug/`，分别包含 arm64-v8a 和 x86_64。图标沿用项目源码中的白色 ChatGPT 图形与黑色背景；图形来源为 OpenAI 自有仓库 [openai-realtime-agents](https://github.com/openai/openai-realtime-agents/blob/main/public/openai-logomark.svg)，权属 OpenAI，本项目并非官方应用。

完整自动验收通过已有 Android 工作流启动：

```sh
gh workflow run android.yml --ref dev \
  -f standalone_environment_poc=true -f device_only=true
```

工作流使用新 Android 15 x86_64 模拟器，开启 `adb reverse`，由 `../scripts/environment-poc.py` 提供 loopback 素材并采集真实结果。脚本拒绝物理设备、非 CI 环境和已有 PoC 空间，避免覆盖已有实验数据。APK 离开这个夹具服务后不具备可浏览的真实网页。

下载 `android-environment-poc` 任务附件查看 `evidence/report.json`、原始事件、HTTP 请求、进程 / 内存 / CPU 输出和截图。`isolation.passed`、`resourcesComplete`、`keepAlivePassed` 与系统设置不变分别记录；不忽略失败、不把单个页面通过当作完整验收。最终结果与开发进度统一写入仓库根目录 `MACOS_MIGRATION_PLAN.md`。

## 三账号分组实验

独立三账号的完整模拟器运行见 [40503073397](https://github.com/ForceMind/ChatGPT/actions/runs/40503073397)：A / C 同环境但使用各自存储空间，B 使用不同语言与时区；四页、弹窗和 330 秒持续检查已在合成素材下运行。进程强停后的检查由脚本重新打开页面，只验证浏览器存储，不能证明对话、草稿或阅读位置自动恢复。清除 C 后 A / B 是否在确认清空后继续活动，另用定向复验检查；完整限制和复验状态记录于根目录 `MACOS_MIGRATION_PLAN.md`。

实测资源、SessionStore 日志、Android 9 和真实设备 / ChatGPT 的验证边界应在制定正式迁移方案时重新评估；这个实验不修改已安装应用，也不是正式版本。

补充的[定向复验](https://github.com/ForceMind/ChatGPT/actions/runs/40505986900) 在观察到 C 的主页面和 iframe 存储清空后，确认 A / B 的主页面、iframe、Worker 仍以原实例继续上报。两轮分别使用新的模拟器；可核对的指标与限制见 [实验汇总](../../docs/benchmarks/android-grouped-environment-poc.json)。

## 页面恢复与 Android 9：第一阶段

本阶段的合成网页测量已完成，**当前原生恢复接入尚不满足正式迁移条件**。Android 15 的[完整测量](https://github.com/ForceMind/ChatGPT/actions/runs/40516494405)与 Android 9 的[定向复验](https://github.com/ForceMind/ChatGPT/actions/runs/40524577766)使用不同源码，均保留真实失败门槛；它们不是同一 HEAD 的全量通过。指标、工件摘要与每轮诊断来源见[恢复证据](../../docs/benchmarks/android-recovery-environment-poc.json)。

| 测量项 | 两组模拟器中的观察 |
| --- | --- |
| 正常关闭 / 真实进程强停后恢复 | 四页标准 textarea 草稿、阅读位置、历史及账号存储可恢复 |
| 动态 contenteditable 草稿 | 未恢复 |
| 网页已有标准草稿 | 被原生表单恢复覆盖，缺少冲突保护 |
| 离线对话内容 | 未观察到回显；重新连接后可打开页面 |
| 损坏 / 跨账号加密记录 | 拒绝读取并保留原文件 |
| 自动发送 | 未发生 |

以 minSdk 28 构建的独立 APK 已在 API 28 安装并执行恢复测量，固定 Gecko 158 AAR 清单最低版本为 API 26；这不代表完成所有 Android 9 机型验收。两组设备都是 x86_64 模拟器，真实 ChatGPT、arm64 真机、32 位设备和厂商系统尚未覆盖。实验使用硬件键盘配置和 Android 输入接口；部分触摸滑动没有验证成功，阅读位置由公开 PanZoom API 产生时会明确记录，不能把它算作滑动通过。

Android 9 出现过强停后由 Activity 启动创建新进程的情况。测试记录该失败项，仅在旧进程已退出、出现新 PID 时最多再次强停一次，以继续独立用例；进程表必须连续一秒没有存活实验进程。该问题仍需稳定性排查。Android 15 仍记录到关闭附近的 SessionStore 空窗口错误，也没有标为已修复。

会话记录使用独立 Android Keystore AES-GCM 和按账号 / 标签绑定的数据，准备阶段正常保存，之后冻结基准供重复恢复观察。**冻结回放不能证明生产控制器会持续保存最新界面**，也不能保留进程结束后的 JavaScript 堆或网络请求。正式迁移前需要接入应用自己的动态草稿、冲突保护和只读离线快照，并完成旧系统生命周期与真实服务验收。

构建与设备测量均在 GitHub Actions。完整矩阵或仅 Android 9 可分别执行：

```sh
gh workflow run android.yml --ref dev \
  -f recovery_environment_poc=true -f device_only=true \
  -f 'recovery_poc_apis=[28,35]'
```

将最后一个参数设为 `[28]` 可只复验 Android 9。任务保留小型原始事件、请求、进程表、报告和画面，不发布正式安装包、不导入现有登录或恢复数据。测量完整执行但原生能力不足时，任务仍失败并在报告中给出具体门槛。

## 应用恢复控制器：当前进度

第二阶段已完成：同一独立 App 源树在 API 28 / 35 各通过 17 项（原 14 项恢复加真实触摸、Home 前后台与 Activity 重建），并完成三种 unsigned arm64 release 体积对照。第三阶段的非调试优化 release 也已通过两端各 17 项，见文末。准确来源与限制分别记录，原始失败保留在统一证据中。

新增的 `Managed*Activity` 使用独立浏览器 profile、独立加密目录和受限内置页面桥接，不读取正式 App 的账号、登录或恢复文件。`ManagedPageController` 保存按账号 / 标签 / URL 绑定的文本、动态草稿、阅读位置和历史；恢复不调用会覆盖现有表单的整包原生状态恢复。离线显示只读原生文本，遇到不同草稿时保留两份并等待选择。

[第一阶段三账号四页完整矩阵](https://github.com/ForceMind/ChatGPT/actions/runs/40631441716)在同一源码 `04644b1` 上通过 Android 9 / API 28 和 Android 15 / API 35 的编译、lint 与各 14 项合成用例。覆盖正常退出、继续编辑后的最新草稿、强停断网重连、只读离线内容、历史 URL 隔离、未处理冲突跨重启保留、两种冲突选择与备份查看、原子写恢复、损坏 / 跨账号记录拒绝，以及三账号的语言、时区、Cookie / localStorage 隔离。多行中文 / emoji、真实 DOM 与仅接受可信输入事件的编辑器模型均已核对；发送和截图为零。本轮未观察到异常进程重启，之前失败与异常记录仍保留。

所有已核对的有效来源、摘要、完成范围和失败见[应用恢复证据](../../docs/benchmarks/android-managed-recovery-poc.json)。本实验仍只访问合成 loopback 网页，真实 ChatGPT、真机、正式 UI / 生命周期集成仍未验收；包体积只完成下方 unsigned release 文件测量。新版工作约定下，新增浏览器诊断须明确授权；管理恢复驱动默认不截图，`--capture-ui` 仅在具体截图任务获准后使用。

[两页输入诊断](https://github.com/ForceMind/ChatGPT/actions/runs/40628520740)此前已验证切换保留原文档，并定位到未初始化输入缓存可能返回提交成功的问题。完整矩阵进一步覆盖错误页重连：控制器现同时等待当前导航的新原生 IME 焦点回调、完整文本与有效选区，提交后仍等真实页面 / 模型确认，不重复提交已接受的写入；桥接使用有上限的握手恢复。**正式迁移门槛仍未通过**：本阶段只验证独立合成页面，真实 ChatGPT、真机 / OEM、正式 UI 与生命周期集成、发布体积尚待后续阶段。第一阶段的 PanZoom 阅读样本未证明触摸滚动；该轮 `No editable parent` 警告有 API 28 一次、API 35 十次，未声称修好该警告。

已获本轮明确授权的完整矩阵入口如下，默认不截图：

```sh
gh workflow run android.yml --ref dev \
  -f managed_recovery_poc=true -f device_only=true \
  -f managed_recovery_core=false -f managed_recovery_input_diagnostic=false \
  -f 'recovery_poc_apis=[28,35]'
```

以下入口只运行该最小诊断；浏览器交互须先取得当前任务的明确授权：

```sh
gh workflow run android.yml --ref dev \
  -f managed_recovery_poc=true -f device_only=true \
  -f managed_recovery_input_diagnostic=true -f 'recovery_poc_apis=[28,35]'
```

## 发布包与触摸 / 生命周期：第二阶段

[体积与 Android 9 定向复验](https://github.com/ForceMind/ChatGPT/actions/runs/40651548074)对应 `ba61f19`；[Android 15 的通过任务](https://github.com/ForceMind/ChatGPT/actions/runs/40649194866/job/122685034834)对应 `2e42ff5`。两轮独立 App 源树完全一致，后续只修正测量解析器与 CI 输入工具，因此复用 API 35 的有效结果；不合称同一提交的全量通过。来源、摘要、全部首轮失败和警告分布见[第二阶段报告](../../docs/benchmarks/android-phase2-package-touch-lifecycle.json)。

固定 GeckoView `158.0.20261021001945`、AGP `9.1.1`、minSdk 28，按[Android 官方配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)对照同一 arm64 unsigned release：

| 配置 | APK 文件大小 | 相对基线减少 |
| --- | ---: | ---: |
| 原始 release | 196.075 MiB | — |
| R8 代码压缩 | 185.168 MiB | 5.5625% |
| R8 + 资源压缩 | 185.078 MiB | 5.6087% |

共减少 11,531,479 字节，约 10.997 MiB。13 份原生库仍为 176,500,344 字节，占优化包约 90.95%；三种配置的原生库和恢复桥接内容摘要一致，内核内容保持不变。资源压缩相对仅 R8 额外减少 94,896 字节。APK 文件大小不等于商店压缩下载、安装占用、内存或 CPU；这些 unsigned release 没有安装或运行验收，设备回归仍使用独立 debug 包。

两套系统各 17 项通过，原 14 项恢复断言保持：增加同一文档的可信触摸上 / 下滑、真实 Home 前后台、Activity 重建；四页草稿与文档身份保持。Home 按键发起到重新打开前的观测窗口为 API 28 约 7.025 秒、API 35 约 5.205 秒，包含系统切换开销，并在确认失焦后至少等待 5 秒；不代表长时间后台、Doze 或 OEM 真机保证。

Android 9 的 stock `input swipe` 为每个移动事件重置 downTime，固定 Gecko 会正确丢弃该无效后续流；首轮失败日志与 [AOSP 原实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r61/cmds/input/src/com/android/commands/input/Input.java)一致。仅 CI 的旧系统改用经 Android InputManager 注入的同一 downTime / FINGER / TOUCHSCREEN 事件流，33 个事件回执及独立网页的可信触摸、实际滚动均已核对。该工具拒绝非 API 28、非 shell、非模拟器，不进入 App。API 35 沿用 stock adb；触摸门槛不使用 PanZoom / JS 来通过，既有阅读恢复另行使用原 1400px 样本。

完整设备日志连续采集并按阶段定位：API 28 / 35 的 `No editable parent` 分别为 4 / 9 条，出现在初始加载、重开、重连和冲突恢复附近；触摸、Home 和 Activity 重建阶段未记录该警告。两端 `win is null` / `FATAL EXCEPTION` 为零，发送与截图为零。时间邻近不是因果，未宣称根因已证实或警告已修复，正式迁移门槛仍为 false。

## 非调试优化 release：第三阶段

[同一源码两系统运行验收](https://github.com/ForceMind/ChatGPT/actions/runs/40682073691)对应 `210e6d5`。API 28 / 35 的 x86_64 APK 实际启用 R8 与资源压缩、保持 debuggable=false，经临时 CI 证书签名；签名前后非签名 ZIP 负载一致，设备 base.apk SHA 与签名包一致，并实测普通 run-as 因非调试标志拒绝。两端分别通过 17 项，含真实触摸、Home / Activity 重建、正常退出与强停断网重连、冲突跨重启 / URL 保留、两种选择 / 查看备份、原子写备份恢复、AEAD 完整性负例与原密文保留、账号环境 / 存储隔离和零发送。

release 不使用调试开关取证。仅在已验证的全新 qemu CI 模拟器中，用受限桥接以实验 App UID 访问白名单文件，保持写入文件的原 SELinux 上下文；数据读取使用无 PTY 的 shell-v2 通道，未知包 / 路径 / 操作 / 截图拒绝。浏览器 App 由标准 ActivityManager 启动，取证权限不作为生产沙箱安全保证。实际两次发现的是签名密码来源顺序与测试读协议问题；另修正实验观测 JSON 的原子发布、单次快照判断，不缓存旧数据或放宽恢复断言。

边界检查共 20 项（警告解析 4、取证桥接 11、签名 5），编译 / lint / 临时签名 / 设备运行全部在 Actions。两端发送、截图、win-is-null、FATAL 和意外进程重启均为零；No editable parent 各 9 条，仍按阶段记录，未宣称根因或警告修复。正常 App UID 分别为 10086 / 10209，临时密钥与密码文件由签名 finally 清理且不进入工件。

完整原始来源、失败历史、包身份与各项边界见[优化 release 运行证据](../../docs/benchmarks/android-optimized-release-runtime.json)。这是 x86_64、短后台、合成网页的独立实验结果，不代表 arm64 / 真机、真实 ChatGPT / 登录 / 通行密钥、长时间后台或正式迁移通过；第二阶段 arm64 文件大小不是本轮 x86_64 安装 / 内存 / CPU 数据。

独立手动工作流默认仅构建，真正运行需先取得具体交互授权，再显式关闭 build_only：

```sh
gh workflow run android-recovery-poc.yml --ref dev \
  -f runtime=optimized-release -f managed=true -f build_only=false \
  -f core_only=false -f input_diagnostic=false -f 'apis=[28,35]'
```

## 输入警告对照与长后台：第四阶段

[输入警告对照](https://github.com/ForceMind/ChatGPT/actions/runs/40857125677)对应 `51bef31`，API 28 / 35 两端通过。固定版本官方源码的 `hasEditableParent()` 在 `mEditableParent` 为空时输出 `No editable parent` 并返回 false，多个原生回调会使用此检查；源码 JAR 摘要、行号和直接触发条件见[对照证据](../../docs/benchmarks/android-input-warning-controls.json)。

两页合成对照覆盖无输入打开、空白导航、仅聚焦、原生提交、保留切换、Activity 重建和完整退出恢复。两端在首次打开、尚未聚焦或输入时均已有 1 条警告；余下发生于完整退出后的恢复，总数分别为 2 / 3。原生提交、切换与 Activity 重建阶段本轮没有新增警告，实际 DOM / 编辑器模型及保存位置核对通过；没有发送或截图。该结果说明提交输入不是必要触发条件，具体原生调用方和完整生命周期原因仍未确定，不能据此认定所有场景无害。对照负载只有两页，不能与第三阶段四页的 9 条直接比较为优化收益。

本轮长后台测量使用同一独立 App 源码，非调试优化 release、临时 CI 签名、三个账号四页；Home、熄屏、强制 Doze 分别真实等待 10 分钟，再检查原文档、草稿、阅读位置和宿主 PID。CPU 从进程累计计数及实际系统计时单位计算，按一个逻辑核为 100% 汇总；PSS 按当前归属实验包的进程求和。采样期间存在合成页面报告、恢复记账、ADB / PSS 观察开销，结果不等同完全无取证的后台状态、真实 ChatGPT、arm64 或 OEM 真机。Android 9 缺少 getconf 时，CI 单独编译 shell 工具调用 Os.sysconf 获取实际值；工具不进入 APK，拒绝个人设备或其他系统版本。

### 实测结果与复用范围

[Android 15 成功子任务](https://github.com/ForceMind/ChatGPT/actions/runs/40858390744/job/123395098545)在 `68f1e24` 完成一轮连续三段后台测量及原 17 项恢复回归，共 20 项通过。同轮 Android 9 的 getconf 前置失败保留，因此整个原工作流是红色，不能把它写成整轮成功。

Android 9 在 `3660564` 的 [20 分钟 Home / 熄屏结果](https://github.com/ForceMind/ChatGPT/actions/runs/40859647518)中，两段各约 601 秒的文档、草稿、位置、宿主进程保持检查通过，随后停在 Doze 未生效的前置，不能算整轮验收通过。`47926a0` 的 [Doze 定向复验](https://github.com/ForceMind/ChatGPT/actions/runs/40864267169)完成 10 分钟 Doze 及全部原 17 项恢复回归，共 18 项通过。这里复用已通过的 Home / 熄屏证据，不合称 Android 9 单轮连续 30 分钟通过。各轮 App 源码、构建脚本及依赖配置与 `210e6d5` 完全一致，变化仅在 CI 工具与测量条件。

| 系统 | 阶段（各 10 分钟） | PSS 首次 → 最后采样 / 峰值（MiB） | CPU 加权均值 / 窗口峰值 |
| --- | --- | ---: | ---: |
| Android 9 | Home 后台 | 729.9 → 796.9 / 796.9 | 11.00% / 12.47% |
| Android 9 | 熄屏 | 806.3 → 834.0 / 834.0 | 10.88% / 11.60% |
| Android 9 | 强制 Doze | 726.7 → 800.5 / 800.5 | 10.78% / 11.83% |
| Android 15 | Home 后台 | 661.0 → 677.7 / 677.7 | 2.23% / 2.88% |
| Android 15 | 熄屏 | 684.2 → 709.6 / 709.6 | 2.21% / 2.70% |
| Android 15 | 强制 Doze | 653.6 → 821.1 / 821.1 | 1.30% / 1.62% |

CPU 以一个逻辑核为 100%，两套模拟器实际均为 2 核、100Hz；均值按设备时间加权，峰值是约 30 秒窗口的最大值。PSS 约每 60 秒采样并汇总实验包的所有关联进程，是采样峰值。固定工作量包含 3 个账号、4 页、2 组语言 / 时区 Runtime，以及每 500ms 的合成 HTTP 观测和恢复记账；不是正式 System WebView 客户端或真实 ChatGPT 的性能结论。

Android 9 原镜像 `enabled deep` 为 0。CI 显式启用深度闲置后，等待控制器报告 `screen=false` / `charging=false`，再按 [Android 官方测试方法](https://developer.android.com/training/monitoring-device-state/doze-standby)强制进入；全部 21 个 Doze 采样点为 `IDLE` 且屏幕休眠。没有添加 App 电池白名单。Android 15 的 Doze 期间，HTTP 观测曾停顿约 600 秒，返回后四页仍是原文档；本批保活口径是恢复后不重载，不能承诺后台 JavaScript 无限持续运行。

两套系统均保存零发送、零截图、`win is null` / `FATAL EXCEPTION` 为零；原 `No editable parent` 在完整负载仍各有 7 条，不宣称警告已修复。部分阶段 PSS 随固定工作量增长，不能直接判定泄漏；下一步需要堆 / 原生分配与周期快照、消息 / 网络排队分析。尚未做资源优化，也没有接入正式内核、迁移登录数据或完成 arm64 / OEM 真机 / 真实 ChatGPT 验收。

来源、每个采样点、原失败、系统状态、包身份、完整摘要与跨轮复用关系集中在[长后台与资源证据](../../docs/benchmarks/android-long-background-resources.json)。当前阶段完成，`formalMigrationReady=false`。

已有具体交互授权后，可按原测量入口复验：

```sh
gh workflow run android-recovery-poc.yml --ref dev \
  -f runtime=optimized-release -f managed=true -f build_only=false \
  -f core_only=false -f input_diagnostic=false -f experiment=long-background -f 'apis=[28,35]'
```

`experiment=input-warning` 为两页输入对照；`experiment=doze-probe` 为四页 Doze 定向段与原恢复回归。手动工作流默认仅构建，运行前仍需对应测试范围的明确授权。

## CPU与内存定位优化：第五阶段

本阶段仅限独立PoC，正式客户端、main、公开版本、个人登录与生产签名材料保持。固定三账号四页和两组Runtime，所有编译、纯逻辑检查与浏览器运行在GitHub Actions；不截图、不访问真实ChatGPT。

新增计数把问题分开：闲置Home/Doze窗口未新增恢复capture或页面记录写入，因此不支持“闲置持续加密写盘是主要CPU来源”的解释。原HTTP取证每500ms产生完整观测，未限定并发；Android15原观测组单页最多收到362个未完成fetch promise的回执。新的观测传输只保留一个在途请求、一个待发标志，超时可取消并在有新状态时发送当前内容。该项属于取证修复，不能算作App性能收益，也不能证明全部PSS增长都来自它。

PoC自身的优化独立比较：固定长度小写hex替代逐字节Formatter，元数据摘要只在文本变化时重算，稳定ready阶段去除完全相同的快照传输。恢复中的相同快照可能仍是进度信号，不能简单丢弃；原生auth/focus/scroll期间保留完整消息，明确ready后才允许合并。checkpoint、握手和退出保存强制获取当前状态，最终流式正文由网页与原生保存内容分别核对。

早期不区分状态的去重候选在前进恢复失败，未采纳；其性能结果不用于交付。另修正了CI安装/ADB连接前置、幂等原生滚动准备，以及取证输入准备中点击前误要求已有焦点的问题；原功能、同一文档、真实DOM/编辑器模型和加密负例门槛保留。完整失败来源与最终采纳关系见[资源定位报告](../../docs/benchmarks/android-resource-tuning.json)。

短对照使用90秒明确标注的正文流式变化+每轮8条重复通知、Home/Doze各180秒，再执行原17项恢复回归。App-only两臂均使用相同bounded观测，唯一调优因素为baseline/optimized；代码、依赖、ABI、R8配置保持，仅显式调优开关不同。Android9两臂来自同轮40875985935，Android15控制组40881524563与优化子任务40875985935的App模块输入一致，后续差异仅测试准备。

| 系统 / 重复通知压力段 | 控制CPU均值 | 优化CPU均值 | 本次相对下降 |
| --- | ---: | ---: | ---: |
| Android9 | 51.54% | 16.99% | 67.04% |
| Android15 | 26.93% | 19.76% | 26.61% |

单核为100%，是单次合成压力负载，不是所有场景的降幅。Android9该段capture6435→716，真实pageWrite715→716，最终正文与恢复通过；页记录没有靠停写减少。Android15普通Home后台CPU3.6654→4.0064%，未显示一致改善。压力段PSS采样峰值下降，但部分回落后端点与Doze内存更高；不能宣称内存泄漏已修复或所有阶段更省。

[优化配置长后台](https://github.com/ForceMind/ChatGPT/actions/runs/40881517638)已在同一源码`0e09473`完成两端单轮三段各10分钟及全部原17项恢复回归，每端20项通过。四页文档、草稿、位置、宿主PID保持，无新文档请求；非调试标志、临时CI签名、安装SHA和App UID均核对。App模块与短对照`0f54a67`完全一致，后续只修正测试输入准备。

| 优化长后台 / PSS采样峰值MiB / CPU均值（单核100%） | Home | 熄屏 | 强制Doze |
| --- | ---: | ---: | ---: |
| Android9 | 800.8 / 11.12% | 839.1 / 11.10% | 845.0 / 11.07% |
| Android15 | 672.3 / 2.72% | 708.3 / 2.47% | 662.9 / 0.72% |

Android9仍存在后台PSS增长，空闲CPU没有短压力段同样的降幅；Android15本轮Doze末次PSS659.6MiB，但Home/熄屏仍增长。两端全部长测采样中观测请求并发均不超过1，不能据此宣称所有PSS增长或泄漏已解决，也不能把不同观测方式的历史长测当成严格百分比基线。此批收益是受控压力路径减少重复处理与临时分配、观测积压受限，并保留完整恢复功能；不是正式客户端或所有负载的性能承诺。实际手机、arm64/OEM、真实ChatGPT、过夜与内存压力验收不在当前证据范围；正式迁移门槛仍为false。

已有本项具体交互授权后，对照入口为：

```sh
gh workflow run android-recovery-poc.yml --ref dev \
  -f runtime=optimized-release -f managed=true -f build_only=false \
  -f core_only=false -f input_diagnostic=false -f experiment=resource-profile \
  -f resource_tuning=baseline -f 'resource_cases=["baseline","bounded","optimized"]' -f 'apis=[28,35]'
```

三臂依次为旧观测+旧算法、bounded观测+旧算法、bounded观测+优化算法。长后台使用`experiment=long-background -f resource_tuning=optimized`；手动工作流仍默认仅构建，设备交互须对应批准范围。调优开关只属于独立PoC，未接入正式APK。

## 空闲观测周期校准：第六阶段

[四组同源码对照](https://github.com/ForceMind/ChatGPT/actions/runs/40891733293)对应`4ca8321`，API28/35 × 500ms/5000ms全部成功，各20项（原17恢复+3个180秒资源段）。App的src、build.gradle、settings.gradle与第五阶段`0a268f9`相同；同系统两臂未签名APK和R8映射摘要逐字节相同，临时CI证书不同引起的签名差异按设备安装SHA分别核对。没有App行为或正式客户端改动。

仅周期HTTP载荷采用0.5秒或5秒，底层500ms计时派发仍在，真实输入/点击/滚动和待发最新状态即时发送。资源段开始前需四页回执确认周期，返回后先恢复500ms并确认，再执行功能检查；没有降低输入、checkpoint、账号/文档栅栏、模型一致性或实际Doze要求。此轮是测量开销校准，不能重复算作App优化。

| 系统 | 阶段（180秒） | 500ms载荷CPU均值 | 5000ms载荷CPU均值 | 500ms PSS首/末次采样MiB | 5000ms PSS首/末次采样MiB |
| --- | --- | ---: | ---: | ---: | ---: |
| Android9 | Home后台 | 11.43% | 2.30% | 714.8 / 752.9 | 712.5 / 715.5 |
| Android9 | 熄屏 | 10.61% | 2.18% | 760.9 / 768.6 | 712.5 / 701.6 |
| Android9 | 强制Doze | 10.45% | 2.06% | 746.8 / 772.6 | 704.4 / 702.1 |
| Android15 | Home后台 | 2.56% | 0.51% | 657.9 / 639.9 | 673.2 / 616.3 |
| Android15 | 熄屏 | 2.80% | 0.53% | 651.6 / 638.5 | 619.9 / 603.8 |
| Android15 | 强制Doze | 0.79% | 0.51% | 627.7 / 631.6 | 603.1 / 608.7 |

CPU按单核100%、设备时间加权。是单次匹配模拟器实验；不能将降低取证负载后的值称为正式App的真实空闲占用或无取证基线。PSS仍约每60秒观察，包含取样扰动；周期请求计数在Doze阻断时可能陈旧，不能当成精确网络吞吐。

12个资源窗口均无新增恢复capture或页面记录写入，原文档、草稿、1400px位置与宿主PID保持，无新文档请求与自动发送。改变周期载荷明显改变本次CPU数据，因此此前高频HTTP结果不能直接外推App空闲成本；5秒载荷并不停止真实事件采样。

Android9高频组的PSS增长主要位于内容进程`Private Other`：Home总PSS增加38.132MiB，其中该类别增加38.008MiB；Java/Native Heap汇总没有相应增长。低频组同段总PSS增加3.040MiB，熄屏/Doze则回落。该粗分类仍不能确定具体对象、JS堆、匿名映射、缓存或泄漏，后续若需进一步归因应检查内容进程映射/堆生命周期，而不是先改恢复写盘。Graphics桶报告0也不在本报告中被解释为相关进程没有图形成本。

原包身份、全部采样点、每进程PSS类别和覆盖、Native/Java堆计数、实际周期与反例门槛集中于[空闲周期证据](../../docs/benchmarks/android-idle-observer-cadence.json)。无强制GC、内存裁剪、App电池白名单、关闭恢复或真实账号；formalMigrationReady仍为false。

已有当前具体交互授权后，可复验：

```sh
gh workflow run android-recovery-poc.yml --ref dev \
  -f runtime=optimized-release -f managed=true -f build_only=false \
  -f core_only=false -f input_diagnostic=false -f experiment=idle-profile \
  -f resource_tuning=optimized -f 'observer_cadences_ms=[500,5000]' -f 'apis=[28,35]'
```

此入口仅属于独立CI实验，不配置正式App的标签休眠或采样策略，也不迁移现有登录。后续真机/arm64、真实ChatGPT、过夜与内存压力仍未验证。

## 有限映射归因：第七阶段

[API28匹配映射实验](https://github.com/ForceMind/ChatGPT/actions/runs/40904089731)对应`a1495de`。App未改；只在原PSS采样点读取已验证实验进程的smaps元数据，不读取内存内容，RSS/PSS覆盖不足必须失败。额外读映射有扰动，不能与上一轮未读smaps的绝对值作严格效果比较。

5000ms组通过原20项。500ms组记录了Home保持与映射，随后读取`/proc/4964/smaps`超过25秒，普通ADB证据读取也超时，managed-report未完成；高频组没有通过完整门槛，不能称为一次全量通过，也不能将这次工具/ADB故障解释为已证实App泄漏或崩溃。

高频Home映射增长主要位于三个内容进程的`rw-p [anon:jemalloc]`，PSS分别增加20476、10404、10040KiB；低频Home最大对应增量2200KiB，后续熄屏/Doze较小。这里仅陈述内核报告的映射标签与计数，不能区分分配器保留、缓存、对象存活或泄漏。没有强制GC或内存裁剪。

该次为明确限定的有限归因，不扩展重跑矩阵。完整ZIP摘要、低频功能门槛、高频不通过原因与各进程最大映射增减见[映射证据](../../docs/benchmarks/android-memory-mapping-attribution.json)；原始映射元数据保留在对应CI工件。正式迁移仍未开始，个人登录和签名不操作。下一阶段准备按[应用内网络首版](../../docs/android-network-plan.md)推进独立候选，当前网络功能尚未集成。
