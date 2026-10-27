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
