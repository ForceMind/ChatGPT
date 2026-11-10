# 三端更新体验计划

第一批 Mac / Windows 更新体验已正式发布为 2026.11.10，第二批 Android 继续按下文推进。沿用白灰黑、无地址栏和现有沉浸布局。Windows 嵌套目录与更新下载仍待实机验收，可信发布者签名的前置条件见 [Windows 计划](windows-installation-plan.md)。

## 现有代码与缺口

- 桌面共用 `desktop/src/renderer/UpdateStatus.tsx`，已有顶部状态按钮和账号菜单内的更新卡片。
- `desktop/src/shared/updates.ts` 仅传百分比、下载阶段和提示，尚无已下载 / 总字节及独立失败阶段。
- Mac 和 ZIP 下载函数根据清单中的完整文件大小计算百分比，缺少 Content-Length 时仍可计算。Windows EXE 的底层事件已有 transferred / total，当前只向 UI 传百分比。
- Windows 检查失败的 catch 丢弃原因，统一提示无法检查；安装、下载及校验失败也存在笼统文案。仅凭“更新暂未完成”无法定位故障。
- Android 两种页面引擎当前没有应用内更新下载入口，官网尚无 Android 专用更新清单。

## 第一批 Mac 与 Windows

用户可见结果为现有顶部按钮始终清楚显示下载百分比，点开现有应用更新卡片查看完整流程。

1. 使用检查、下载、校验、准备安装 / 可安装的阶段名称。下载显示如 `37% · 48.6 / 131.4 MB`；连接阶段显示正在连接，校验与准备阶段明确显示当前动作。
2. 保留已下载进度及阶段。关闭菜单后任务继续，重新打开菜单直接显示当前任务。下载中隐藏其他菜单时，顶部状态仍可见。
3. 无更新时保留菜单里的检查更新按钮。失败指出检查、连接、下载、文件校验或启动安装中的具体阶段，并提供对应的重试动作。界面采用用户可理解的错误说明，详细信息不直接输出未经清理的异常栈、URL 查询参数或本机路径。
4. 将现有平台下载字节事件传入共用状态。按可见百分比或有界时间合并 UI 通知，避免逐数据块刷新和同步写盘。
5. 沿用既有完整应用安装行为。Mac 保留立即安装、下次退出及已批准的空闲安装条件；Windows EXE 保留退出安装，ZIP 保留另存后手动替换。启动 / 上海中午检查沿用现有规则。

直接涉及 shared/updates、update-download、update-manager、windows-update-manager、UpdateStatus 及其样式。安装器、账号数据结构、标签保活不属于 UI 改造范围。

## 第二批 Android

用户可从两种引擎的更多菜单打开同一个应用更新入口，查看当前版本、检查结果、下载百分比与文件大小，下载完成后确认安装。

1. 增加官网 Android 更新清单，记录包名、版本名、递增 versionCode、ABI、文件大小、SHA-256 和正式证书身份，客户端比较 versionCode。
2. 更新属于应用任务，独立于账号与资产任务。下载使用无账号 Cookie 的请求，暂存 APK 不归档到资产库。实现前核对现有 NetworkRoute / NetworkDownload，明确系统网络、应用内代理、线路变化及加速源的行为。
3. 提供普通下载、用户主动选择的国内加速、取消及失败重试。两种来源必须通过同样的大小、摘要、包名、版本和签名检查。部分下载文件不能进入安装步骤。
4. 后台下载需要 Android 自身的任务调度与通知机制。下一步实现时定点核对当前 SDK 和现有依赖，说明进程终止后的实际恢复行为；不能把应用内临时线程描述为系统保证的持续后台下载。
5. 校验完成后由用户点击安装，按系统权限状态进入允许该来源安装及系统安装确认。取消安装后保留可重试状态，下一次启动根据实际已安装 versionCode 判定成功。保留原包名和签名，覆盖安装使用现有数据。
6. 首批优先手动检查和可操作下载闭环。启动自动检查与上海中午后台调度的具体保证范围在 Android 实现前单列确认，桌面既有规则沿用。

Android 原生入口涉及 MainActivity、EnvironmentBrowserActivity 和共用更新组件，安装权限、文件 provider 及通知按所选系统下载实现定点加入。新增官网更新接口先在候选夹具使用，公开客户端与域名发布在候选验收后处理。

## 拟定验收

所有构建与运行放在 GitHub Actions。桌面完整 npm run check 必须通过。合成交互仅使用自有页面与更新文件，不截图、不访问真实 ChatGPT 或个人账号，不安装个人设备。

- Mac / Windows 核对阶段、百分比 / 字节、菜单关闭重开、手动检查、下载断开重试、摘要不符拒绝安装、准备 / 启动安装失败，以及原有退出安装与数据保留回归。
- Android 9 / 15 核对两种引擎入口、检查 / 无新版 / 下载 / 校验 / 权限 / 系统安装确认、取消、损坏 APK、错误包名 / 证书 / 版本拒绝、进程重开状态与同签覆盖数据保留。
- 不以合成通过替代 Windows 实机、Android OEM、真实网络与签名信任验收。

## 当前状态

2026.11.10 已按确认范围正式发布，复用同源码已验收 CI 字节。桌面官网下载、自动更新清单及部署回读见 [正式发布记录](releases/2026.11.10-publication.json)。Android 的公开下载仍为 2026.11.09-1236。以下保留候选验收记录。

第一批已完成候选 `2026.11.10`，来源 `ed6f9e35c40be8c4d31cafabe56057dcc7b4a5e6`，Actions `42749099128` 两端均成功。Mac 完整检查 108 项单元全部通过、4 项适用更新 E2E 通过；Windows 108 项单元中 107 通过、1 项平台跳过，2 项适用更新 E2E 通过。跨平台测试按预期分别跳过其余平台专用用例。实际进度、检查失败重试、Mac 下载断流重试、菜单关闭重开、安装准备 / 退出 / 空闲安装以及 Windows EXE / ZIP 回归通过。两端报告无 PNG、录像或 trace 工件。

[Mac 候选](https://github.com/ForceMind/ChatGPT/actions/runs/42749099128/artifacts/13732523592) 含 Apple Silicon DMG / ZIP；[Windows 候选](https://github.com/ForceMind/ChatGPT/actions/runs/42749099128/artifacts/13733315059) 含 x64 EXE / ZIP。均需 GitHub 登录，工件有效期至 2026-11-24。Windows 使用 `ChatGPT-Studio-2026.11.10-windows-x64-setup.exe`，Mac 使用 Studio DMG / ZIP；Profiles ZIP 为兼容更新格式。完整摘要与来源见 [候选记录](releases/desktop-update-experience-2026.11.10-candidate.json)。

候选验收阶段保留既有公开版本，后续按上述正式发布记录切换。Mac 仍为临时签名且未公证，Windows 仍未配置 Authenticode。验证为 GitHub Actions 合成更新 / 安装场景，个人设备、真实网络和签名信任仍待实机验收。Android 应用内更新尚未实现。

以下保留中间验证记录。

用户确认继续第一批。Mac / Windows 已接入下载字节、明确阶段和可操作失败提示，保留现有安装与保活流程；待 Actions 完整检查和批准的合成交互验收。Mac 合成下载加入断流后重试，Windows 使用受控真实流式下载核对百分比 / 字节及菜单关闭重开。测试截图按本批范围关闭。Android 仍待第二批实现。

源码 `c02e068` 的 `42746875706` 中，Mac 完整检查、合成更新 / 安装回归及候选打包均通过。Windows 完整检查通过，但等待中途下载字节超时，未放宽断言；后续仅在同一 Windows 夹具中保留 SDK 原生进度 / 错误及当前状态，不重复已通过的 Mac 任务。两端候选交付目标尚未完成。

Windows 原生事件取证确认 4 KiB EXE 夹具下载期间没有 SDK 进度事件。改为带有效 PE overlay 的 4 MiB 可执行夹具后，`42748704538` 的原生进度、字节 / 百分比、菜单关闭重开、实际安装执行和原数据保留验收通过，原门槛及超时保留。本批交付版本提升为 `2026.11.10`，仅改 package / lock 的版本元数据，按同一最终源码生成 Mac 和 Windows 候选。

Android 下载与安装机制按官方文档核对。[DownloadManager](https://developer.android.com/reference/android/app/DownloadManager) · [安装来源权限](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) · [系统安装确认](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))
