# Profiles Environment PoC

独立 Android 环境实验。包名为 `com.forcemind.chatgpt.environmentpoc`，使用自己的私有目录与调试签名，不导入正式 App 的代码、Cookie、账号、密钥或资产。安装它不会更新正式 App。它只打开 loopback 测量网页，不提供真实账号登录。

## 实验边界

- 固定 GeckoView `158.0.20261021001945`，账号 A / B 各有一个 App 进程、Runtime 和持久 profile。
- A 请求 `zh-CN / Asia/Shanghai`，B 请求 `en-US / America/New_York`。使用 Runtime locale、进程 `TZ` 与进程内 Java 默认值，不修改 Android 系统设置。
- 远程调试关闭，不注入修改 `navigator`、`Intl` 或 `Date` 的补丁。测量主文档、跨来源 iframe、Dedicated Worker、新页及用户手势弹窗，检查网络语言、冬夏偏移、本地时间构造、Cookie / localStorage 和重建 / 切换。
- 1 / 4 / 8 标签使用同样的 240 条模拟消息和 6 张 1024×768 图片。PSS 汇总 App 命名空间中的父进程与子进程；CPU 为 `top` 两秒增量样本的单核百分比，排除首个累计样本。8 标签另做 330 秒上下文持续检查。
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
