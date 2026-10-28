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
gh workflow run android.yml --ref codex/macos-assets-workbench \
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
gh workflow run android.yml --ref codex/macos-assets-workbench \
  -f recovery_environment_poc=true -f device_only=true \
  -f 'recovery_poc_apis=[28,35]'
```

将最后一个参数设为 `[28]` 可只复验 Android 9。任务保留小型原始事件、请求、进程表、报告和画面，不发布正式安装包、不导入现有登录或恢复数据。测量完整执行但原生能力不足时，任务仍失败并在报告中给出具体门槛。

## 应用恢复控制器：当前进度

第二阶段已完成：同一独立 App 源树在 API 28 / 35 各通过 17 项（原 14 项恢复加真实触摸、Home 前后台与 Activity 重建），并完成三种 unsigned arm64 release 体积对照。准确来源与限制见下方“发布包与触摸 / 生命周期”，原始失败保留在统一证据中。

新增的 `Managed*Activity` 使用独立浏览器 profile、独立加密目录和受限内置页面桥接，不读取正式 App 的账号、登录或恢复文件。`ManagedPageController` 保存按账号 / 标签 / URL 绑定的文本、动态草稿、阅读位置和历史；恢复不调用会覆盖现有表单的整包原生状态恢复。离线显示只读原生文本，遇到不同草稿时保留两份并等待选择。

[第一阶段三账号四页完整矩阵](https://github.com/ForceMind/ChatGPT/actions/runs/40631441716)在同一源码 `04644b1` 上通过 Android 9 / API 28 和 Android 15 / API 35 的编译、lint 与各 14 项合成用例。覆盖正常退出、继续编辑后的最新草稿、强停断网重连、只读离线内容、历史 URL 隔离、未处理冲突跨重启保留、两种冲突选择与备份查看、原子写恢复、损坏 / 跨账号记录拒绝，以及三账号的语言、时区、Cookie / localStorage 隔离。多行中文 / emoji、真实 DOM 与仅接受可信输入事件的编辑器模型均已核对；发送和截图为零。本轮未观察到异常进程重启，之前失败与异常记录仍保留。

所有已核对的有效来源、摘要、完成范围和失败见[应用恢复证据](../../docs/benchmarks/android-managed-recovery-poc.json)。本实验仍只访问合成 loopback 网页，真实 ChatGPT、真机、正式 UI / 生命周期集成仍未验收；包体积只完成下方 unsigned release 文件测量。新版工作约定下，新增浏览器诊断须明确授权；管理恢复驱动默认不截图，`--capture-ui` 仅在具体截图任务获准后使用。

[两页输入诊断](https://github.com/ForceMind/ChatGPT/actions/runs/40628520740)此前已验证切换保留原文档，并定位到未初始化输入缓存可能返回提交成功的问题。完整矩阵进一步覆盖错误页重连：控制器现同时等待当前导航的新原生 IME 焦点回调、完整文本与有效选区，提交后仍等真实页面 / 模型确认，不重复提交已接受的写入；桥接使用有上限的握手恢复。**正式迁移门槛仍未通过**：本阶段只验证独立合成页面，真实 ChatGPT、真机 / OEM、正式 UI 与生命周期集成、发布体积尚待后续阶段。第一阶段的 PanZoom 阅读样本未证明触摸滚动；该轮 `No editable parent` 警告有 API 28 一次、API 35 十次，未声称修好该警告。

已获本轮明确授权的完整矩阵入口如下，默认不截图：

```sh
gh workflow run android.yml --ref codex/macos-assets-workbench \
  -f managed_recovery_poc=true -f device_only=true \
  -f managed_recovery_core=false -f managed_recovery_input_diagnostic=false \
  -f 'recovery_poc_apis=[28,35]'
```

以下入口只运行该最小诊断；浏览器交互须先取得当前任务的明确授权：

```sh
gh workflow run android.yml --ref codex/macos-assets-workbench \
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
