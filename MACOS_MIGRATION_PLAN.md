# macOS 首版迁移与验收记录

## 当前授权范围

- macOS 安装包先交付使用；用户于 2026-10-20 追加授权 Android 首版，Windows、Linux、iPhone/iPad 保持后续计划。
- 各设备完全独立使用，不建设云同步服务。
- 打开 App 直接进入 ChatGPT 网页；实现可靠的窗口拖动、明确的账号标识、独立持久浏览器空间、账号与标签管理、重启恢复。
- 当前单智能体执行。用户已授权更新本仓库、提交推送和发布版本；未授权商店上架或覆盖旧应用。

## 基线与恢复

- 开发起始 `main` / `origin/main` 基线：`df64b07661e645fc37fe9448346c3bf78bb8d15a`；追加发布工作前已 fetch 并核对一致。
- 新版目标版本 `3.0.0-alpha.1`。开发数据与安装版数据分离，隔离测试只使用临时数据与本地网页。

## 顺序、归属与验收

所有新增文件由主智能体负责。采用默认资源预算，不启动后台自动化。

1. [功能已实现，普通窗口拖动人工验收通过] 主进程、可信工具栏和沙箱网页视图可运行；最终单行界面已实际加载未登录 ChatGPT 网页，用户已确认用真实鼠标拖动顶部空白区可以正常移动普通窗口。
2. [本地集成验收通过] 账号档案、持久 session、标签与弹窗归属、同账号共享、跨账号隔离、重启恢复、单账号清理；真实账号登录待用户手动验收。
3. [已完成实现与回归] 用户已确认的白灰黑、无地址栏、单行顶部界面已迁入实际 App。右上账号菜单替代额外侧栏，标签右键及 macOS 菜单保留固定、恢复和导航操作。
4. [自动检查通过，真实账号登录待验] 最终代码类型检查、5/5 状态测试和两项 Electron 集成流程均通过；其中隔离流程包括多次重启后的首次账号切换，界面流程包括原生视图边界、菜单背景不落盘、900×700 / 1360×900 及全屏切换。普通窗口真实鼠标拖动已由用户确认通过；两个真实账号登录与切换由用户稍后手动验收。
5. [本地验收构建已生成] 最终 UI 的 ARM64 DMG/ZIP 已生成，ad-hoc 签名校验通过；7 个构建文件与包内对应文件逐字节一致，安装包 SHA-256 回读通过。人工项目通过前不标记整个首版验收完成。

首次可查看节点：macOS 客户端加载 ChatGPT，顶部可拖动，能建立两个独立账号空间并切换。最终本轮交付还包含上述管理功能与必要验证。

## 必须保持的不变量

- 一个账号 UUID 绑定一个持久 session；标签创建后不跨账号修改 session。
- 登录弹窗继承发起账号的 session；清理账号先关闭其视图及弹窗，仅清理该账号。
- 本地工具栏可使用有限 IPC；远程网页不注入 IPC / Node 能力，开启沙箱、上下文隔离和 webSecurity。
- 不保存账号密码；名称和邮箱为用户备注，默认不声称已自动核验网页身份。
- 状态文件原子写入并保留有效备份；损坏时恢复或明确报错，避免静默丢失账号档案。
- 标签休眠/关闭不被描述为回复一定完成；首版保留后台网页，不自动休眠正在使用的页面。

## 验证边界与停止条件

- 本地网页证明 Cookie、LocalStorage、IndexedDB、弹窗、跨账号清理、重启恢复和权限边界，不代表真实 ChatGPT 登录通过。
- 不向 ChatGPT 发送隐藏测试消息，不读取或复用已有应用的真实登录数据。
- 真实账号登录由用户手动完成；Google/Apple 等登录流程、语音及真实上传下载按实际证据记录。
- Mac 采用 ad-hoc 签名；Developer ID 签名和公证未完成。本轮按追加授权准备 GitHub 预发布分发。
- 遇到运行故障先保存状态并修复；必要外部条件缺失时完成其他独立工作，记录补验条件。

## 后续平台

1. Windows、Linux：在已验证的桌面结构上适配原生窗口与打包，分别实机验收。
2. iPhone/iPad：验证独立持久 WKWebView 数据存储和常用登录方式，确定最低系统要求。
3. Android：已纳入本轮追加授权，实施与证据见下方记录。
4. 各端延续本地账号及标签规则，各设备分别登录。

## 当前证据

- `npm run check`：类型检查、5/5 状态测试、生产界面构建通过。后续源码修正后仍须完成最终对应检查。
- 原目录 74 个文件 SHA-256 复核无变化。
- 官方 npm 查询选定 Electron `44.9.1`；GitHub 下载缓慢后改用公共镜像取得同一运行时，SHA-256 与官方 npm 包内置摘要 `ff597552c9c7b0b16c39a7fb93a346ee7148afbd372ebcf0c18415482c49fbea` 完全匹配后才执行。
- `npm audit --omit=dev`：0 个已报告漏洞；全部依赖审计中 8 个 moderate 告警来自打包工具的间接依赖，详见 `desktop/README.md`。
- 安装包采用临时目录干净打包，避开当前磁盘 AppleDouble 元数据被误读为 ASAR 的问题；构建成功不代表真实账号登录通过。

## UI 确认与实施

- 用户指出早期灰绿色配色未经过 UI 对齐，已明确弃用。该配色由主智能体自行选择，非用户指定，也非参考仓库设计。
- 用户已选择白灰黑风格、无地址栏并确认具体预览；当前应用和安装包已迁入确认稿，不再等待重复的 UI 批准。
- 功能侧最新证据：2/2 本地 Electron 集成验收通过，覆盖存储隔离、同账号共享、登录弹窗、重启、单账号清理、账号/标签交互、全屏及代表性尺寸；最终类型检查通过。
- 用户已选择：贴近 ChatGPT 的白色/浅灰外观、黑色控件、账号仅以小色点区分；明确不要地址栏，保持打开即网页的沉浸体验。
- 独立预览：`desktop/design/macos-light-preview.html`。用户已回复“按这份预览实现”，授权将单行窗口栏、无地址栏、白灰黑配色和右上账号菜单迁入应用；本轮继续实施及对应验收，无需再次确认。
- 当前 ARM64 安装包已更新为确认后的 UI，索引见 `desktop/release/build-info.json`，实际 App 路径和 SHA-256 均可从该文件回读。
- 本机构建已通过实际窗口截图确认能加载 `chatgpt.com` 未登录页面，未执行登录或发送消息；最终 UI 的普通窗口拖动已由用户手动验收通过。
- 确认稿截图：`desktop/design/macos-light-main.jpg`、`desktop/design/macos-light-accounts.jpg`。最终真实 App 截图：`desktop/release/macos-immersive.jpg`、`desktop/release/macos-accounts.jpg`；截图时为未登录真实 ChatGPT 网页，无模拟聊天、无测试消息。
- 历史自动化记录：原视觉构建通过 CUA 对顶部空白区执行两次原生坐标拖动，持久窗口边界仍为 `{x:55,y:33,width:1360,height:900}`，当时未能确认实际移动。该记录不作为通过证据；最终普通窗口拖动以用户后续手动确认结果为准。

## 最终构建与剩余人工验收（2026-10-20）

- DMG：`desktop/release/ChatGPT Profiles-3.0.0-alpha.1-arm64.dmg`，SHA-256 `0aea9004fe54ffad90a91d44907f7c5b9a6fd25c473152bd90c4ddd26b4f57ca`。
- ZIP：`desktop/release/ChatGPT Profiles-3.0.0-alpha.1-arm64.zip`，SHA-256 `ae2fcc0f5d48193424b3f2656508c8943bc2bfef76df3b778d5225016012741b`。
- 菜单切换修正：先完成目标账号的激活，再关闭本地菜单；连续重启首次切换回归通过。原生视图背景仅在内存中截取给可信工具栏，持久状态中不保存 `overlayImage`。
- 拖动诊断：临时窗口实测 `isMovable=true`、`isMaximized=false`、`isFullScreen=false`；Retina 坐标已用本地网页鼠标事件校准，CSS / Electron 调试标记确认顶部区域为 `drag`，但自动拖动未产生位置变化。`hiddenInset` 与无框窗口 A/B 均未获得通过证据；无框实验已回退，不把它描述为修复。
- 系统原生标题栏对照在 CUA 读取阶段连续超时，未取得可比较结果；因此不能据此确认是 App 故障或工具限制。实际退出状态另以进程查询核对，未依赖 UI 工具缓存。
- 用户人工验收反馈（2026-10-20）：当前新 App 的普通窗口顶部空白区“可以正常拖动”，此项通过。两个真实 ChatGPT 账号登录与切换的反馈为“稍后手动验收”，保持待验证；本地会话隔离测试不替代真实登录验收。
- Mac 阶段结束时，旧源码 74 个文件 SHA-256 复核无变化，未覆盖旧 App；当时尚未提交或推送。之后的 Android 与发布工作按下方追加授权推进。

## Android 与首次发布（2026-10-20 追加授权）

- 交付：保留现有 Mac 安装包；新增 `android/` 原生客户端，延续白灰黑、无地址栏、打开即网页、多账号与标签管理。每台设备独立使用。更新根 README，向 `ForceMind/ChatGPT` 的 `main` 提交推送并发布 `v3.0.0-alpha.1` 预发布。
- 本轮不启动子智能体。旧源码与 Mac 应用代码不作范围外重构。
- Android 依赖系统 WebView 的 `MULTI_PROFILE` 能力，创建 WebView 后、任何导航前绑定档案；不支持时明确阻止使用共享空间。原生 Java + AndroidX WebKit，无服务端。
- 清理/移除账号采用新档案与待删除队列，下一进程启动时删除旧档案，避免 Android 不允许删除已加载档案的限制；不触碰其他账号。
- 验收：Android 编译、相关状态/导航测试、隔离集成与实际渲染（设备可用时）；发布前核对凭据排除、旧源码摘要、安装包摘要，推送后回读 commit/tag/release。真实账号登录与切换继续由用户稍后验收。
- 环境：远端与本地基线均为 `df64b07661e645fc37fe9448346c3bf78bb8d15a`，已 fetch。GitHub 仓库现有可见性为 public，保持不变。JDK/SDK 在临时目录准备，初始无连接 Android 设备。
- 已实现：原生账号/标签管理、独立 WebView Profile、同账号登录弹窗、重启恢复、清除后的档案轮换、撤销记录防止备份重新启用旧空间、文件选择、HTTPS 下载及摄像头/麦克风来源授权。Android 12+ 另显式排除云备份和设备迁移。
- Android 最终应用源码：`0fbfccd3b9e6d7579e65438b5807976d4fcf8d45`；[构建与检查](https://github.com/ForceMind/ChatGPT/actions/runs/39408218670) 通过，5 项状态/导航测试通过、Lint 无错误、调试与未签名 release 构建成功。后续 CI 目录修正不改变应用源码。
- APK 使用本机固定身份签名，校验通过（v3，最低 Android 9）；证书 SHA-256：`8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19`。当前 APK SHA-256：`fb7943017f42e7d915c00ce93b57fda6d194fad6cda295b257529da73d165ab2`。
- 自动审批拒绝把私钥及密码写入 GitHub Secrets，理由为发布授权不覆盖签名凭据外传。已采用安全替代：CI 仅生成未签名 APK，本机完成签名；未上传私钥或设置对应 Secrets。密钥与密码位于被 Git 排除的 `android/.signing/` 和 `android/keystore.properties`，应由维护者离线备份。
- 本机大附件上传曾发生 HTTP 408；发布草稿已建立。Mac 改由 Apple Silicon runner 构建与上传相同源码，发布前核对包内前端摘要。两种 Mac 构建使用相同应用版本，安装包文件摘要会因重新打包而不同，以 Release 的 `SHA256SUMS.txt` 为分发依据。
- 模拟器启动曾因 AVD 目录不一致失败，已修正明确路径、设备定义检查、启动时限与日志。测试框架卸载调试 App 后导致截图失败，已在截图前重新安装。
- [Android 设备流程](https://github.com/ForceMind/ChatGPT/actions/runs/39409542052) 通过：2 项隔离/损坏恢复测试，原生账号新增、切换、重启恢复，1080×2400 手机与 1600×2560 平板尺寸截图。环境为 Android 15 / API 35、WebView `124.0.6367.219`，不等于真机验收。
- 查看截图后发现验收脚本未在键盘弹出后更新邮箱输入坐标，邮箱被输入账号名称；这是验收脚本错误。已改为读取当前控件位置，并严格检查名称和邮箱；仅界面流程重新验收，复用已通过的隔离结果。应用源码与签名 APK 未变。
- [修正后的原生界面验收](https://github.com/ForceMind/ChatGPT/actions/runs/39410439551) 通过：严格校验 `Work` 名称、独立的 `work@example.test` 邮箱备注、账号切换与重启恢复；手机、账号菜单和平板截图保留于 `docs/images/android-*.png`。界面和隔离验证使用调试包；发布 APK 为同应用源码的 release 构建，经本机签名校验。真机与真实登录继续待验。
- Android 截图中部分页面处于 Cloudflare 校验，另一空间出现未登录 ChatGPT 界面；不将这些截图当作真实登录通过。真实账号、第三方登录、语音与实际文件上传下载继续待验。
- [Mac 云端打包](https://github.com/ForceMind/ChatGPT/actions/runs/39408599689) 通过：`codesign --verify --deep --strict`，包内 7 个前端文件与本机构建摘要一致。服务器端安装包摘要已回读匹配：DMG `45654422d523a1ccb465adbe02cbe18f0da323fda4bf7cdb9c99671b3cb70140`；ZIP `4819d4e4aba9c3c5e7130d1a5197801220e0a708584f8ef9e21c6f8ce3b24dd4`。
- 分发版本：[v3.0.0-alpha.1](https://github.com/ForceMind/ChatGPT/releases/tag/v3.0.0-alpha.1)，使用预发布渠道；附件包含 Mac DMG/ZIP、Android APK、`SHA256SUMS.txt`、`release-info.json` 与 `mac-release-info.json`。最终源码由同名 Git tag 固定；各包的构建源码和摘要由公开索引固定。仓库保持 public、`main` 更新，未覆盖旧应用，未实现其他平台或商店上架。

## Windows 首版（2026-10-20）

- [本轮已完成] Windows x64 `3.0.0-alpha.2` EXE/ZIP 已生成并取回 `desktop/release/windows/`，本机 SHA-256 与 CI 索引一致。完整 Windows CI、安装启动、正常退出、卸载保留数据与实际渲染验收通过；真实账号及客户端真机验证保留待验，未新增公开 Release。

- 用户已要求开始制作 Windows。基线 `main` / `origin/main` 为 `cadfc44f4e58851c3ca4bfea9c7ab8fa28134f09`，已 fetch 且工作区干净。沿用单智能体执行。
- 本轮交付 `3.0.0-alpha.2` Windows x64 试用 EXE/ZIP；已有 Mac/Android `alpha.1` Release 保留。本轮不改动原 Release，不处理商店上架、Windows ARM64 或证书申请。
- 涉及 `desktop/` 的平台窗口、原生菜单、快捷键、Windows 图标与打包入口，以及 Windows CI、README 和本记录。账号 schema/session 映射保持兼容，各设备不建设同步。
- Windows 顶部使用原生最小化/最大化/关闭按钮与拖动区，控件为标题栏原生区域留空；F11 全屏，Ctrl 系列快捷键，账号菜单补充导航。保持白灰黑、无地址栏、打开即网页。
- 验收：类型检查、既有状态测试；Windows Electron 存储隔离、弹窗与重启/清理流程，原生视图及标题栏安全区、代表性尺寸和窗口状态；实际渲染；EXE 安装/启动/卸载与保留账号目录。Mac 做必要回归，原始源码不变。
- Windows 构建由本仓库 CI 执行，按已有仓库同步授权提交推送本次文件；最终安装包和摘要作为试用成果交付，未授权的公开新 Release 不在本轮自动执行。未配置 Authenticode 证书，试用包会明确标记未签名。真实账号由用户手动验收。
- 本地 `alpha.2` 类型检查、5 项状态测试、构建通过。Mac 界面回归运行至原生全屏断言时未进入全屏；临时导出已发布 `cadfc44` 源码、同一 Electron 运行时的对照也在同一断言失败。未发现本次 Windows 改动引入的差异，未修改 Mac 全屏逻辑；本轮 Mac 原生全屏自动验证保持未通过，不用历史通过记录替代。
- Windows [运行验收](https://github.com/ForceMind/ChatGPT/actions/runs/39442759920) 的类型检查、5 项状态测试与 3 项 Electron 流程通过。覆盖 Cookie/LocalStorage/IndexedDB/CacheStorage/Service Worker 隔离、同账号共享与弹窗归属、多次重启、单账号清理、账号/标签管理、标题栏安全区、原生窗口状态及 900×700 / 1360×900 布局。
- 原生鼠标拖动记录：`WM_NCHITTEST=HTCAPTION(2)`，`GUI_INMOVESIZE` 已进入原生移动循环；指针输入后的真实窗口位移为 `80×45` 像素。测试只在初始化时放置窗口，实际位移由原生鼠标输入产生，没有用设置窗口坐标替代拖动结果。前两轮脚本从按下即计位移，包含系统拖动阈值，现已在原生移动循环建立后测量，原精确位移断言保留。
- EXE/ZIP 初次已经生成，随后包内核对因 ASAR 读取使用 `/` 路径失败；已修复为 Windows 原生相对路径，公开摘要键仍使用 `/`。该轮失败记录保留；最终安装与卸载结果见下方成功 CI。
- 最终 Windows [完整 CI](https://github.com/ForceMind/ChatGPT/actions/runs/39443940258) 通过，构建源码 `bf2baf9d2318f23a6c2fdc91a606aa1c5a413e02`：类型检查、5 项状态测试、3 项 Electron 流程、NSIS/ZIP、x64 PE、包内版本与全部构建文件逐字节核对。
- 安装验证在 NT `10.0.26100.0` 完成：EXE 静默安装、已安装程序启动、默认账号/标签创建、正常退出、卸载程序、保留同一账号状态均通过。实际 Authenticode 状态 `NotSigned`；PE 数字产品版本 `3.0.0.0`，应用包声明与运行时版本 `3.0.0-alpha.2`。截图 `docs/images/windows-installed.png` 展示真正已安装客户端加载未登录 ChatGPT，没有输入消息或登录。
- 截图与原生窗口证据另见 `docs/images/windows-native.png`、`docs/images/windows-accounts.png`；后两者使用明确标记的本地验收网页，不能冒充真实 ChatGPT 业务验证。
- 试用下载：[EXE Actions 产物](https://github.com/ForceMind/ChatGPT/actions/runs/39443940258/artifacts/12274469728)、[ZIP Actions 产物](https://github.com/ForceMind/ChatGPT/actions/runs/39443940258/artifacts/12275384158)。GitHub 产物需登录下载，保留至 2027-01-18；本轮未增加公开 Release 或更改既有 v3.0.0-alpha.1 附件。
- 未验：Windows 10/11 客户端真机、真实双账号登录、第三方登录与真实语音/文件流程。Mac 本轮原生全屏自动对照仍未通过，不能将 Windows 的全屏验证替代 Mac；已有 Mac 包未改变。
- Windows 产物摘要（CI 计算，随试用包提供）：
  - `ChatGPT-Profiles-3.0.0-alpha.2-windows-x64-setup.exe`：`2ac551b0298696494621f64e10d708f87b69ffa0aeb820b05edd00955a3c01e8`，112306244 bytes。
  - `ChatGPT-Profiles-3.0.0-alpha.2-windows-x64.zip`：`4f9408a4f9fe36871533d0a6e0c7c2bc74601f4add788a4c2b7be7b3c6b9820c`，154817621 bytes。

## Windows 公开发布与下载主页（2026-10-21 追加授权）

- 用户明确授权将 Windows EXE、免安装 ZIP 发布至 GitHub Release，并制作成熟数字朋克风格的下载说明主页、部署到 Cloudflare Pages。
- 基线 `main` / `origin/main` 均为 `1363a41b72fc815d92ba8dbf73182437bb69a8c8`，fetch 后工作区干净。沿用单智能体，提交推送本次负责文件。
- 发布 `v3.0.0-alpha.2` Windows 预发布版，复用成功验收运行 `39443940258` 的原包；本地重新核对两个包的字节数和 SHA-256 一致。Actions 内再次校验源码、验收结论和包摘要，先上传草稿，核对附件后公开。已有 `alpha.1` Mac/Android 版本保留。
- 主页放在 `site/`，包含产品说明、真实截图、平台版本与下载、使用步骤、安装与隔离边界。网站使用深色与克制霓虹；App 已确认的白灰黑 UI 保留。二进制直接从 GitHub Release 下载。
- 验收：Release 公开状态、附件大小/摘要及匿名下载入口；网站桌面/手机实际渲染、下载与说明交互；Pages 正式部署、线上内容与源文件核对、Git 远端回读。真实双账号登录继续由用户稍后人工验收。
- Windows [上传运行 39535798656](https://github.com/ForceMind/ChatGPT/actions/runs/39535798656) 成功：复用原始 EXE/ZIP，运行内再次核对成功构建源码、大小与 SHA-256，未重建或替换既有资产。GitHub Release ID `419094189`，附件共四个（EXE、ZIP、`windows-build-info.json`、`SHA256SUMS.txt`），服务器提供的两个二进制 digest 与本地及 CI 完全一致。
- [v3.0.0-alpha.2](https://github.com/ForceMind/ChatGPT/releases/tag/v3.0.0-alpha.2) 已于 `2026-10-21T03:05:31Z` 公开，`draft=false`、`prerelease=true`，目标提交 `a8d984c7652ecc89361438970086d4a46d51a36c`。两个下载 URL 的匿名 HEAD 均为 200，大小分别为 112306244 / 154817621 bytes。原始构建索引仍含未分发 blockmap 记录，Release 的校验清单仅列两个实际下载包。
- 网站实际 Chromium 检查通过：1440×1000、1024×900、768×1024、390×844、360×800 均无横向溢出；三平台截图切换、键盘方向/Home 导航、FAQ 展开、SHA-256 剪贴板、下载区锚点和 Windows/Mac/Android/iPad 推荐逻辑通过；页面与资源无错误。修复了背景光晕与装饰轨道在窄屏超出容器的问题。截图为原始产品截图，Android 明确标记模拟器与测试备注。
- Cloudflare 现有登录有效。使用 Wrangler `4.151.3`，新建独立 Pages 项目 `chatgpt-profiles`、正式分支 `main`；未改动其他网站。CLI 创建默认尝试 Workers 委派后未找到静态目录，按 CLI 说明仅在项目创建时使用 `--force` 直接创建 Pages；后续部署不使用该标志。
- 正式站 **https://chatgpt-profiles.pages.dev/** 已上线。Cloudflare 部署 ID `4025f81e-c4d3-4722-8b06-7ba0e5f59d0f`，环境 `Production`、分支 `main`、网站源码提交 `0c044f7a3ecb8addb309a6e2a91c22bc6fc4240b`；部署专用地址 `https://4025f81e.chatgpt-profiles.pages.dev`。从该 Git 提交导出 11 个干净文件，未上传磁盘 AppleDouble 文件；10 个公开资源与 `_headers` 分别上传。
- 线上 Chromium 再次通过同一组五种尺寸与完整交互验收，控制台及页面资源无错误。通过正式域名回读的 10/10 个公开文件 SHA-256 与来源提交完全一致；CSP、禁止嵌入、MIME 与权限响应头生效，未知页面返回正确的自定义 404。命令行检查对 `404.html` 的 Pages 308 规范链接重定向显式跟随后通过，不修改站点路由或放宽断言。
- 网站五个安装包链接的匿名 HEAD 均为 200，字节数与 GitHub 附件一致。README、桌面说明已更新为公开 Windows Release 与主页入口。Pages 使用 Direct Upload，后续网站源文件更新仍须显式部署；本次后续文档提交不改变已经逐字节核对的网站文件。
- 本轮交付完成：Windows EXE/ZIP 公开预发布、成熟数字朋克主页、三平台下载与说明、Cloudflare Pages 正式部署。App 代码和既有 Mac/Android 二进制未改变。真实双账号、Windows 客户端真机及既有文档标注的业务边界继续待用户人工验收。

## macOS 资产工作台迭代（2026-10-21 批准）

- 用户确认先做 macOS，Windows / Android 后续。最终范围：按账号管理数字资产、安全预览、本地格式转换、一键带文件发起 ChatGPT 网页对话、120 个独立任务 Skill，以及全量 328 个智能体，232 个互联网候选优先展示。
- 基线 `main` / `origin/main`：`9b44b0fec06202e1e1f67a52ed3bd415ed3b09fa`，fetch 后工作区干净。工作分支 `codex/macos-assets-workbench`，单智能体执行。保留白灰黑、无地址栏、启动进入网页；网站的数字朋克风格不迁入 App。
- 当前迭代先提供 `desktop/ui-preview/` 可交互预览，覆盖网页/资产面板切换、按账号浏览、筛选搜索、文件详情、转换操作、Skill/智能体检索和带文件发起任务。示例文件与流程演示明确标识；不读取现有账号登录、不发出 ChatGPT 消息。先对齐界面，再将交互迁入真实 App。
- 后续沿同一目标依次接入：下载与本地索引/恢复 → 实际预览/转换与派生关系 → 两个内置库 → 真实网页上传与发送适配 → 相关隔离回归与 Mac 安装包。原件保留、账号归属固定、跨账号选择显式、失败可恢复；发送结果不确定时不重复发送。
- 本轮仅建设 macOS 相关能力和必要验证，不改动 Windows/Android 产物或正式网站，不提前公开新 Release。沿用现有仓库同步授权，按可交付批次提交推送；公开新版本另行安排。
- 当前可查看成果：`desktop/ui-preview/index.html` 可直接离线打开，或通过 `http://127.0.0.1:4186/` 查看；支持 `#assets`、`#skills`、`#agents`、`#chat`。资产三栏、全量能力目录、转换和发送弹窗已完成；导入可由用户选文件或拖入，操作仅保留在本页。
- 能力目录校验：120 个唯一 Skill 名称/ID，流程为预览草案；328 个唯一上游角色路径及摘要，232 个互联网候选始终在前，余下 96 个保留。上游路径与固定提交链接、MIT 作者和许可随预览保存；未运行上游脚本或改变全局 skills。
- 浏览器验收通过：1440×1000、1280×900、900×760 无横向溢出；文件分类/搜索/空状态/两种视图、收藏、重命名/标签、回收站恢复、批量选择、两个示例账号的资产归属、HTML 沙箱/源码、转换副本关系演示、库检索及顺序、跨账号确认与目标账号身份、主动导入及脚本/网络限制、关闭面板返回网页、离线文件入口。页面错误 0、外部请求 0。
- 预览检查修正：目标账号的新对话示意同步更新顶部身份；导入任务固定发起时账号，避免异步读取过程中切换账号改变归属；装饰文件仅为示例缩略图；转换结果示意及发送结束均明确标识未执行真实操作。自动检查初次命中隐藏页的同名入口而超时，改为按当前面板定位后通过，未修改产品行为来绕过检查。
- 渲染证据：`docs/images/macos-assets-ui-preview.png`、`macos-agents-ui-preview.png`、`macos-send-ui-preview.png`。预览代码未进入实际 `desktop/src/`，应用版本未升级，原生文件与真实网页流程尚未验收。当前节点等待 UI 布局意见后继续已批准的原生接入；主 Goal 保持进行中。
- `365e9ffbaf06d039d1e35bbe238c69f3274304ba` 已提交并推送预览。上一目标轮为有效进展：交付可操作界面并取得真实浏览器证据。UI 意见仍待反馈；继续推进不依赖布局的原生资产与能力资源，避免把等待界面意见当成全部工作受阻。
- 原生资产基础已新增 `shared/assets.ts`、`main/asset-store.ts`、`main/asset-downloads.ts`：账号/资产 UUID 固定路径、受控文件名、临时文件转正式文件、SHA-256、导入副本、同名不覆盖、来源对话去除查询参数、备份/损坏保留、重启中断、可逆回收站、派生关系、文件变更/缺失检查及下载暂停/恢复/取消。原始导入文件不修改，账号切换不改变正在进行的归属。
- 当前检查通过：`npm test` 22/22（原状态 5、资产文件 I/O 11、下载事件模拟 6），`npm run typecheck` 通过。覆盖跨账号拒绝、平行同名、原件保留、派生链、损坏恢复/缺索引、路径及符号链接边界、外部改写/丢失、取消/迟到事件与失败传播。模拟 DownloadItem 不作为真实 Electron/ChatGPT 下载验收；当前模块尚未接入 `main.ts` 与实际 UI，旧客户端行为保持。
