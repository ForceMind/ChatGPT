# ChatGPT Studio

**沉浸式多账号 ChatGPT 工作台。**

在独立账号空间中使用 ChatGPT，管理标签与会话，把下载、预览、格式转换和文件处理集中在一个应用里。启动直接进入网页，采用白灰黑界面，没有地址栏；各设备独立登录，无需配置 OpenAI API Key。

[项目主页](https://chatgpt.xincreates.com/) · [下载安装](#下载与安装) · [使用指南](#开始使用) · [路线图](#路线图与待完成事项) · [问题反馈](https://github.com/ForceMind/ChatGPT/issues)

> 项目已从 ChatGPT Profiles 更名为 **ChatGPT Studio**。当前三端公开预发布版为 **3.0.0-alpha.16**；下表提供对应的安装包。历史文件名、应用标识和数据目录保留，用于兼容既有登录、加密记录与更新流程。

![macOS 公开版实际窗口（更名前）](docs/images/macos-immersive.png)

## 产品能力

- **账号空间**：独立 Cookie、网站存储和登录状态；账号名称、邮箱备注与色点帮助区分空间。
- **标签管理**：按账号切换、新建、固定、排序、关闭与恢复；已打开的页面保留实例，不主动冻结或丢弃标签。
- **资产工作台**：按账号归档下载与导入文件，支持搜索、分类、收藏、标签、派生关系和可恢复回收站。
- **预览与转换**：查看图片、PDF、HTML、Markdown、文本与 CSV；转换生成新副本，保留原文件。
- **任务流**：选择文件、目标账号与 Skill，通过网页发起处理任务；保留进度、失败位置及结果不确定状态，不自动重复发送。
- **能力库**：内置 120 个项目任务 Skill，包含步骤、交付结构与质量检查。智能体库入口已保留，当前没有预置角色。
- **本地恢复**：保存账号、标签、草稿、阅读位置与工作台状态；macOS 另提供加密的只读对话快照及确认更新。

ChatGPT Studio 是独立维护的非官方客户端，不附带 ChatGPT 订阅权益。模型和网页功能由当前登录账号及 ChatGPT 服务决定；浏览器空间隔离不等于网络代理或完整指纹隔离。

## 下载与安装

### 公开预发布版

| 平台 | 版本 | 下载 | 要求 |
| --- | --- | --- | --- |
| macOS · Apple Silicon | 3.0.0-alpha.16 | [DMG](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.16/ChatGPT-Profiles-3.0.0-alpha.16-mac-arm64.dmg) · [ZIP](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.16/ChatGPT-Profiles-3.0.0-alpha.16-mac-arm64.zip) | macOS 13+ |
| Windows · x64 | 3.0.0-alpha.16 | [EXE](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.16/ChatGPT-Profiles-3.0.0-alpha.16-windows-x64-setup.exe) · [免安装 ZIP](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.16/ChatGPT-Profiles-3.0.0-alpha.16-windows-x64.zip) | Windows 10 / 11 · 64 位 |
| Android · 手机 / 平板 | 3.0.0-alpha.16 | [APK](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.16/ChatGPT-Profiles-3.0.0-alpha.16-android.apk) | Android 9+，WebView 支持多档案 |

**macOS**：打开 DMG，将应用拖入“应用程序”。当前为 ad-hoc 签名，尚未经过 Apple 公证；首次启动被系统阻止时，核对来源和摘要后参阅 [Apple 安装说明](https://support.apple.com/zh-cn/102445)。

**Windows**：运行 EXE 安装，或解压 ZIP 后启动应用。程序文件名保留为 ChatGPT Profiles.exe；免安装版同样使用当前用户的数据目录。当前未配置 Authenticode 签名。

**Android**：按系统提示允许该下载来源安装；升级时沿用同一应用签名。WebView 不支持多档案时应先更新系统 WebView / Chrome，应用不会退回共享登录空间。

[官网](https://chatgpt.xincreates.com/)提供原始 GitHub 下载和可选国内加速入口。第三方加速不可用时可改用原始链接；下载后可与对应 Release 的 SHA256SUMS.txt 核对。

### 版本与升级

macOS alpha.14 的七阶段任务流、富文本写入改进、资产双击 / 右键菜单及 Android alpha.15 图标适配，均已并入 alpha.16 公开包。[此前 CI 记录](https://github.com/ForceMind/ChatGPT/actions/runs/39963836108)作为历史验证资料保留。上方下载及 Mac 更新清单已同步到本版本。

Mac 从 alpha.12 起支持启动与每天上海时间 12:00 检查更新，确认后后台下载、校验完整 ZIP，可选择立即、下次退出或应用空闲时安装。原生程序更新需要重启，暂不支持增量更新；Windows / Android 当前手动升级。

## 开始使用

1. 打开应用，在默认“个人账号”空间中登录 ChatGPT。
2. 从账号菜单添加空间，填写名称、可选邮箱备注；桌面端还可选择语言和时区。
3. 在新空间中登录对应账号，核对网页身份后，通过账号菜单切换。
4. 点击加号建立当前账号的标签，使用“资产”和“能力库”整理文件与任务。
5. 将文件交给 GPT 前核对目标账号；跨账号使用文件需要明确确认。

账号备注不会替代网页登录身份。任务显示“已发送”表示网页已出现本次用户消息，不代表模型已完成处理；应用中断或发送结果未知时，请先检查目标对话。

macOS 关闭窗口后保留后台页面。真正退出后，应用重新加载网页，再恢复已保存的草稿和阅读位置；网页已有草稿时保留网页版本。附件上传和模型生成不会跨进程继续。

## 平台支持

| 能力 | macOS | Windows | Android |
| --- | --- | --- | --- |
| 多账号、独立存储、标签管理 | 支持 | 支持 | 支持 |
| 账号资产库与本地预览 | 支持 | 支持 | 支持，HTML / SVG 静态预览 |
| 图片、文档与数据转换 | 支持 | 支持 | 支持，范围见移动端说明 |
| Skill 与带文件任务 | 支持 | 支持 | 支持，实站兼容仍待验收 |
| 草稿与阅读位置恢复 | 支持 | 支持 | 支持 |
| 加密对话快照、离线阅读 | 已公开 | 后续 | 后续 |
| 确认下载与退出 / 空闲安装 | 已公开 | 后续 | 后续 |
| 每账号语言 / 时区选择 | 支持 | 支持 | 独立 PoC，尚未迁入普通 APK |

图片支持 PNG / JPEG / WebP、SVG 转图片；文档支持 HTML / Markdown 导出 PDF、PDF 选页转图片；数据支持 CSV 与 JSON 互转。具体选项和格式差异见[桌面说明](desktop/README.md)与[Android 说明](android/README.md)。

网页图片可右键复制原始像素、保存到资产库、重试加载并查看加载信息。实际出图速度仍取决于网站、网络与图片服务。现有系统 / Chrome 通行密钥选择器尚未接入；Touch ID 恢复保护需要 Developer ID 签名，当前临时签名包不能启用。

## 数据与升级兼容

| 平台 | 当前数据位置 |
| --- | --- |
| macOS | ~/Library/Application Support/ChatGPT Profiles/ |
| Windows · EXE / ZIP | %APPDATA%\ChatGPT Profiles\ |
| Android | 原应用私有目录，包名保持不变 |

更名不迁移或删除这些目录。内部应用名、macOS 更新包中的 ChatGPT Profiles.app、Windows 可执行文件名及既有加密身份保留为兼容标识；对外显示名称为 ChatGPT Studio。常规升级无需卸载或清理账号数据。

账号与标签位于 state.json，浏览器数据位于 Partitions/profile-UUID，资产、任务和恢复记录保存在同一数据根目录。备份时应在应用真正退出后复制整个目录；Android 卸载会删除对应的本机空间。

网页使用沙箱和上下文隔离，不具有 Node 或本地管理接口权限。HTML 预览在独立受限环境中运行；对话草稿与恢复记录通过系统加密保护。应用不收集账号密码，也不将登录空间同步到自建服务。账号清除不会删除 ChatGPT 云端对话。

## 验证与资源占用

已归档的 alpha.14 macOS 独立验收包含 **63 项单元检查、21 项功能流程通过**；2 项 Windows 专属检查跳过。3 个账号、8 个长对话 / 图片标签隐藏约 331 秒后，页面实例、草稿及后台活动保持。

| 模拟负载 · 8 标签 | 工作集合计 | 空闲 CPU 合计 |
| --- | --- | --- |
| 轻量页面 | 约 1.08 GiB | 约 2.70% |
| 长对话与图片 | 约 1.47 GiB | 约 1.72% |

数据来自同一台 CI 主机上的本地模拟页面，工作集包含共享页；不能视为实际 ChatGPT 的占用上限或统计性性能保证。alpha.16 更名后已通过 23 项 macOS 适用流程、13 项 Windows 适用流程与 Android 的 63 项单元检查；各平台专属跳过、实际包名 / 显示名、安装及更新兼容范围见[候选验证索引](docs/releases/3.0.0-alpha.16-branding-ci.json)。历史资源数据分别记录。

[逐项回归结果](docs/benchmarks/macos-alpha14-regression-20261024.json) · [原始资源报告](docs/benchmarks/macos-alpha14-resources-20261024.json) · [完整开发与交付记录](MACOS_MIGRATION_PLAN.md)

## 开发与交付

~~~text
desktop/   Electron + React + TypeScript 桌面客户端
android/   Java + AndroidX WebKit 移动客户端
site/      静态下载主页与使用说明
docs/      截图、验证与发布索引
.github/   平台验证和打包工作流
~~~

桌面端使用 Node.js 26.7.0；Android 使用 JDK 17、SDK Platform 36、Build Tools 35.0.0。开发测试使用回环网页和独立临时数据，不依赖个人账号。

~~~sh
cd desktop
npm ci
npm start
npm run check
npm run test:e2e
~~~

~~~sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
~~~

安装包由 **GitHub Actions** 构建。macos-validation.yml 执行独立回归与资源对照；macos-release.yml、windows.yml 和 android.yml 分别负责平台验证 / 打包。Android 发布签名材料保留在受保护的本地配置中，不提交仓库。

网站是无构建依赖的静态页面，Cloudflare Pages 采用 Direct Upload；Git 推送不会自动部署官网。源码、CI 候选、公开 Release 和线上版本分别记录，不覆盖历史包与校验摘要。

## 路线图与待完成事项

| 优先级 / 阶段 | 工作 | 当前状态 |
| --- | --- | --- |
| 待人工验收 | Mac 真实账号的附件上传、首条消息与一次发送 | 用户稍后验收；隔离回归已通过 |
| 待人工验收 | Android 实际首页、真实账号 / 登录、语音与文件流程 | 首页采集受人工验证限制，官网已有原生资产 / Skill 图 |
| 已发布 | ChatGPT Studio alpha.16 三端包与官网品牌 | 安装包已公开；官网与 Mac 清单本批同步 |
| 平台迭代 | Windows 跟进后续桌面能力、Android 离线快照与更完整恢复 | 后续批次 |
| 环境隔离 | Android 每账号语言 / 时区从独立 PoC 迁入日常客户端 | 路线验证完成，尚未替换普通内核 |
| 系统集成 | 系统 / Chrome 通行密钥、真实 Touch ID 恢复与稳定签名 | 通行密钥未接入；Touch ID 受证书与实机验收条件限制 |
| 能力内容 | 智能体库的自有预置角色与内容管理 | 入口已有，当前预置角色数为 0 |
| 移动网络 | 应用内代理与 VLESS 配置 | 方案阶段，未交付，不提供免费节点 |
| 新平台 | iPhone / iPad 后续开发；Intel Mac、Linux 与商店发行 | 未纳入当前迭代，暂无对应安装包 |
| 更新能力 | 差量下载与更细的更新体验 | 未实现；完整原生程序更新仍需重启 |

已完成的历史工作与每批验证边界保留在开发记录中；本表列的是当前缺口，不代表已开始全部后续开发。

## 反馈与贡献

请通过 [GitHub Issues](https://github.com/ForceMind/ChatGPT/issues) 提供平台、应用 / WebView 版本、复现步骤、预期与实际结果。截图和日志请先遮盖个人信息，不要提交密码、验证码、Cookie、下载令牌或完整账号目录。

修改前请阅读仓库内的工作约定，并在对应平台运行相关检查。功能与文档应明确区分真实集成、隔离夹具、模拟器和实机证据。

ChatGPT 与 OpenAI 的名称及服务属于各自权利人。本项目与 OpenAI 无隶属关系。
