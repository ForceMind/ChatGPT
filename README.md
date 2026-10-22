# ChatGPT Profiles

为不同 ChatGPT 账号保留独立空间，让网页使用、标签切换和文件整理集中在一个应用里。

[项目主页](https://chatgpt-profiles.pages.dev/) · [下载安装](#下载与安装) · [使用指南](#开始使用) · [版本发布](https://github.com/ForceMind/ChatGPT/releases) · [问题反馈](https://github.com/ForceMind/ChatGPT/issues)

![ChatGPT Profiles macOS 实际窗口](docs/images/macos-immersive.png)

ChatGPT Profiles 是一个非官方 ChatGPT 网页客户端。启动直接进入网页；顶部保留账号、标签和窗口操作，采用白色、浅灰与黑色控件，没有地址栏。各设备独立登录、独立保存数据，无需配置 OpenAI API Key。

## 核心体验

- **多账号空间**：每个账号使用独立的浏览器数据空间，分别保存 Cookie、登录状态和网页存储。
- **账号与标签管理**：按账号组织标签，支持切换、固定、排序、关闭及恢复；当前空间名称始终可见。
- **沉浸式网页**：保留 ChatGPT 原有页面与交互。Mac 普通窗口可从顶部空白区拖动，导航和刷新通过菜单或快捷键完成。
- **本机持久化**：重启后恢复账号与标签记录；设备间不共享登录，也不依赖自建同步服务。

账号名称、邮箱备注和色点用于区分空间，真实登录身份以 ChatGPT 网页显示为准。

## 下载与安装

| 平台 | 公开版本 | 下载 | 系统要求 |
| --- | --- | --- | --- |
| macOS · Apple Silicon | `3.0.0-alpha.5` | [DMG](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.5/ChatGPT-Profiles-3.0.0-alpha.5-mac-arm64.dmg) · [ZIP](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.5/ChatGPT-Profiles-3.0.0-alpha.5-mac-arm64.zip) | macOS 13+ |
| Windows · x64 | `3.0.0-alpha.2` | [EXE 安装包](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.2/ChatGPT-Profiles-3.0.0-alpha.2-windows-x64-setup.exe) · [免安装 ZIP](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.2/ChatGPT-Profiles-3.0.0-alpha.2-windows-x64.zip) | Windows 10 / 11 · 64 位 |
| Android · 手机 / 平板 | `3.0.0-alpha.1` | [APK](https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.1/ChatGPT-Profiles-3.0.0-alpha.1-android.apk) | Android 9+，系统 WebView 支持多档案 |

当前使用预发布渠道。Intel Mac、Linux、iPhone 和 iPad 暂无安装包。macOS alpha.5 已包含资产工作台与界面恢复；Windows 和 Android 继续使用各自标注的版本。

`codex/macos-assets-workbench` 功能分支的本地试用版本为 macOS Apple Silicon / Windows x64 alpha.6、Android alpha.8。Windows 工作台、恢复、安装和卸载保留数据已通过 CI；Android 已包含账号资产库、预览、图片 / 文档转换及 120 个 Skill，47 项单元和手机 11 项 / 平板 9 项模拟器离线检查通过。真实账号业务、Android 实际网页截图和个人数据升级仍待实机验证。以上开发进展未替换表中的公开安装包，详细证据见 [迭代记录](MACOS_MIGRATION_PLAN.md)，移动功能和限制见 [Android 说明](android/README.md)。

**macOS**：打开 DMG，将应用拖入“应用程序”。当前构建使用 ad-hoc 签名，尚未经过 Developer ID 签名和 Apple 公证。若首次启动被系统阻止，核对来源和摘要后，按 [Apple 的操作说明](https://support.apple.com/zh-cn/102445)处理。

**Windows**：运行 EXE 安装，无需管理员权限；也可解压 ZIP 后运行 `ChatGPT Profiles.exe`。ZIP 是免安装版本，账号数据仍存放在当前用户的应用数据目录。当前未配置 Authenticode 签名，系统可能提示未知发布者。

**Android**：下载 APK，按系统提示允许当前来源安装。若提示 WebView 不支持多档案，请更新 Android System WebView / Chrome。应用不会在缺少隔离能力时退回共享账号空间。

每个 Release 附带 `SHA256SUMS.txt`。后续通过下载安装新包升级；当前没有自动更新服务。Android 升级需要保持相同应用签名。新版应用不自动导入其他客户端的登录数据。

## 开始使用

1. 启动应用，在默认“个人账号”空间中登录 ChatGPT。
2. 打开右上角账号菜单，添加空间并填写名称、可选邮箱备注。
3. 在新空间中登录另一个账号，核对网页身份后完成命名。
4. 通过账号菜单切换空间；点击 `+` 添加当前账号的标签。
5. 在账号管理中修改备注、调整顺序、清除本机登录或移除空间。

Mac 可右键标签，Android 可长按标签，管理固定、顺序和关闭操作。Android 返回键优先返回上一网页。清除本机账号数据不会删除 ChatGPT 云端对话。

## macOS 工作台迭代

工作台先在 macOS 交付；Windows、Android 后续跟进。以下能力已包含在 macOS alpha.5 公开预发布版中，真实账号验收边界见表格和兼容性说明。

| 能力 | 使用方式 | 当前状态 |
| --- | --- | --- |
| 资产库 | 按账号归档下载与导入文件；分类、搜索、收藏、标签、回收站和 Finder 定位 | `alpha.5` 公开预发布 |
| 文件预览 | 图片、HTML、PDF、Markdown、文本与 CSV；HTML 在独立沙箱预览 | `alpha.5` 公开预发布 |
| 格式转换 | 图片格式、HTML / Markdown 转 PDF、PDF 选页转图片、CSV 与 JSON 互转；保留原文件 | `alpha.5` 公开预发布 |
| 交给 GPT | 选择目标账号、文件和任务，创建对话并提交；不自动重发结果未知的任务 | `alpha.5` 公开预发布，真实网页附件发送待验收 |
| Skill | 120 个项目内置任务，附处理步骤、输出结构和质量检查 | `alpha.5` 公开预发布 |
| 智能体库 | 保留分类与任务入口，当前没有预置智能体 | 界面已实现 |
| 登录与链接记录 | 查看被拦截链接的站点和原因；记录仅保存在本机，过滤敏感参数 | `alpha.5` 公开预发布 |
| 标签保活与资源优化 | 已打开标签切换时保留页面；减少重复界面更新，工作台按需加载 | `alpha.5` 公开预发布 |
| 窗口与界面恢复 | 关窗后台保留、短期撤销关闭、每账号工作台状态；真正退出后加载并恢复加密草稿与阅读位置 | `alpha.5` 公开预发布 |
| 账号语言与时区 | 创建或编辑空间时选择；编辑从新标签起生效 | `alpha.5` 公开预发布 |
| 网页图片操作 | 右键复制图片、保存到资产库、重试当前图片、查看加载信息 | `alpha.5` 公开预发布 |

工作台的配置、支持格式和验证边界见[功能分支的桌面说明](https://github.com/ForceMind/ChatGPT/blob/codex/macos-assets-workbench/desktop/README.md)。

## 隔离与数据

桌面端通过持久浏览器会话分区隔离账号，Android 通过独立 WebView Profile 隔离。同一账号的标签和登录弹窗共用对应空间，其他账号使用自己的存储。

| 平台 | 安装版数据位置 |
| --- | --- |
| macOS | `~/Library/Application Support/ChatGPT Profiles/` |
| Windows · EXE / ZIP | `%APPDATA%\ChatGPT Profiles\` |
| Android | 应用私有目录 |

桌面开发版默认使用 `desktop/.local-data/`。桌面账号和标签记录位于 `state.json`，浏览器存储位于 `Partitions/profile-<UUID>/`。状态写入保留有效备份；主记录与备份均损坏时停止启动，避免静默丢失账号归属。

应用不收集账号密码，不将登录空间上传到自建服务。系统浏览器保存自己的登录状态，外部链接不会自动继承应用内账号。网页没有 Node 或本地管理接口权限；证书错误不会被忽略。账号隔离不改变 IP 或网络可用性。

卸载、清理数据和迁移设备前，请保存重要文件。Android 卸载会删除对应安装的本机空间；恢复标签记录不等于恢复退出前的网页任务或草稿。

## 快捷键

| 操作 | macOS | Windows |
| --- | --- | --- |
| 新建 / 关闭标签 | `⌘T` / `⌘W` | `Ctrl+T` / `Ctrl+W` |
| 恢复关闭标签 | `⌘⇧T` | `Ctrl+Shift+T` |
| 添加账号 | `⌘⇧N` | `Ctrl+Shift+N` |
| 打开账号菜单 | `⌘B` | `Ctrl+B` |
| 切换标签 | `⌃Tab` / `⌃⇧Tab` | `Ctrl+Tab` / `Ctrl+Shift+Tab` |
| 重新加载 | `⌘R` | `Ctrl+R` |
| 后退 / 前进 | `⌘[` / `⌘]` | `Ctrl+[` / `Ctrl+]` |
| 全屏切换 | `⌃⌘F` | `F11` |

Windows 按 `Alt` 临时显示系统菜单，原生窗口按钮用于最小化、最大化和关闭。

## 开发与构建

```text
desktop/     Electron、React、TypeScript 桌面客户端
android/     Java、AndroidX WebKit 移动客户端
site/        下载主页与使用说明
docs/        产品截图与交付资料
.github/     平台检查与打包流程
```

### 桌面端

需要 Node.js 与 npm。当前使用 Node.js 26.7.0、Electron 44.9.1。

```sh
cd desktop
npm ci
npm start
npm run check
npm run test:e2e
```

macOS ARM64 在 Mac 上执行 `npm run dist:mac`；Windows x64 在 Windows 上执行 `npm run dist:win`。安装包、构建信息和摘要写入 `desktop/release/`。各平台构建细节见[桌面说明](desktop/README.md)。

### Android

需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0。

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

设备测试需要支持多档案的 WebView。正式 APK 需配置本地签名身份；签名文件、密码和个人 SDK 路径不得提交。配置方式见[Android 说明](android/README.md)。

### 网站

`site/` 为无构建依赖的静态页面，安装包直接从 GitHub Release 下载。

```sh
python3 -m http.server 4178 --directory site
node --check site/app.js
```

Cloudflare Pages 使用 Direct Upload，Git 推送不会自动更新线上网站。部署到现有项目时，应使用排除 `._*`、`.DS_Store` 等本机文件的干净目录。

## 兼容性与反馈

自动化检查覆盖本地存储隔离、状态恢复及相应平台流程。实际 ChatGPT 页面、第三方身份提供商、WebView 版本和网络环境可能影响使用；本地测试通过不代表所有账号与登录方式都已验收。

- 两个真实账号的登录、切换和重启后登录保持仍待人工验收。
- macOS 的真实网页附件与任务发送验收尚未完成。
- Android 已完成模拟器验证，真机、真实登录、语音及上传下载仍需分别验证；首版暂不支持 `blob:` 下载。
- 通行密钥由操作系统、浏览器内核和登录页面共同决定，当前不承诺直接调用 Chrome 保存的通行密钥。
- iPhone / iPad、Intel Mac、公证及商店发行属于后续范围。

提交 Issue 时请提供平台、应用版本、复现步骤，以及 Android 的 WebView 版本。请先遮盖邮箱、对话内容、下载令牌和其他个人信息，不要上传密码、验证码、Cookie 或完整账号目录。

ChatGPT 和 OpenAI 的名称及服务属于各自权利人。本项目由独立开发者维护，与 OpenAI 无隶属关系，不附带 ChatGPT 订阅权益。
