# ChatGPT Profiles for Android

原生 Java + Android System WebView。当前开发版本 `3.0.0-alpha.7` 正在验证文档转换；此前 `3.0.0-alpha.6` 已生成同签名本地试用 APK，公开下载仍为 `3.0.0-alpha.1`。打开即进入网页，没有地址栏。界面延续 Mac 版的白色、浅灰和黑色，账号以名称、邮箱备注和小色点区分。手机与平板使用同一 APK，各设备分别登录。

## alpha.7 文档转换

当前源码已接入，尚待本批 Android 编译和设备验收，不代表 alpha.6 APK 已包含这些能力。在文件菜单选择“转换格式”，确认输出说明后生成原账号的新资产，保留原件、摘要与派生关系。

- CSV → JSON：保留字符串与前导零，处理引号、换行和 UTF-8 BOM；支持逗号、Tab、分号、竖线及自动识别。表头不能为空或重复，各行列数必须一致。
- JSON → CSV：严格 JSON、非空且字段一致的对象数组；只接受字符串、有限数值、布尔和 null。拒绝重复字段、嵌套对象/数组及尾随内容，数值字面量不经浮点改写；null 为空单元格。字符串与表头的公式前缀加单引号，输出含 UTF-8 BOM 与 CRLF。
- Markdown → HTML：支持标题、列表、代码块、表格、删除线、任务列表与自动链接。原始 HTML 标签作为文本显示，脚本和外部资源禁止加载；不上传内容。

CSV/JSON 输入上限 8 MiB、5 万行、20 万单元格、2048 列；Markdown 输入上限 2 MiB；输出上限 16 MiB。严格 UTF-8，不猜测或替换损坏编码。转换通过后台任务和有界临时文件执行，只有成功后才归档；原件在过程中移入回收站、被移除或账号不可用时停止发布。

## alpha.6 资产工作台开发

账号菜单新增“资产与下载文件”和“Skill 能力库”。alpha.6 的构建、19 项单元检查、手机 5 项与平板 3 项设备检查通过；不代表公开 alpha.1 已包含这些能力，也不代表真实账号业务验收通过。

- HTTPS 下载可保存到发起账号的资产库，也可继续使用系统文件选择器另存为；切换账号不改变下载归属，清除该账号登录会取消其资产下载。
- 系统文档选择器导入本机文件，保存独立副本；单文件上限 256 MiB。支持搜索、分类、收藏、重命名、导出和可恢复回收站；彻底删除有单独确认。
- 图像、PDF 分页、UTF-8 文本 / Markdown / JSON / CSV、静态 HTML / SVG 预览。HTML 不执行脚本或访问外部资源，没有网页到本地的管理桥接。Office 等未支持格式可导出查看。
- 位图转换支持 PNG / JPEG / WebP，可选择长边尺寸和质量，不放大原图；JPEG 透明区域使用白底，动图取第一帧。输出独立副本，保留原件、账号归属和派生关系；手机与平板已验证实际编码签名、解码尺寸、源文件摘要不变及账号归属。
- 120 个项目 Skill 随包离线提供，按清单校验 SHA-256；支持检索及复制包含输入、步骤、输出、检查与示例的任务指令。当前没有预置智能体。
- 清除本机登录保留资产；移除账号前需先导出并清空该账号资产，避免文件失去入口。原始导入文件不修改。
- alpha.6 不包含文档 / 数据转换；alpha.7 正在验证上面的三种转换。HTML/PDF/SVG 导出、自动带附件提交、聊天草稿 / 阅读位置恢复、每账号语言和时区仍待迁入；这些能力与桌面端分别验证，不用“复制指令”冒充已经上传或发送。

资产保存在应用私有目录的 `assets/<账号 UUID>/`，文件按资产 UUID 保存，索引有有效备份；副本写入和摘要校验完成后才标记就绪。损坏索引保留原件并尝试有效备份，缺少索引时不会用空库覆盖已有文件。应用进程中断的复制任务显示中断状态。

## 设备要求

- Android 9 / API 28 或更高。
- WebView 提供商必须支持 AndroidX `MULTI_PROFILE`。此能力在运行时检查，仅有较新的 Android 系统版本不等于一定支持；请保持 Android System WebView / Chrome 更新。
- 不支持时展示更新提示，阻止进入共享浏览器空间。
- 需要设备网络可以访问 ChatGPT。本应用不提供代理或网络绕过服务。

## 使用

1. 首次启动建立“个人账号”空间，直接打开 ChatGPT。
2. 点击右上角账号名称，选择“添加账号”，填写名称与可选邮箱备注，再在新空间内手动登录。
3. 从“切换账号”选择账号；每个账号拥有自己的标签组，当前名称始终显示在顶部。
4. 点击 `+` 新建标签；长按标签可固定、排序或关闭；“恢复关闭的标签页”最多保留最近十项。关闭最后一页会建立首页标签。
5. “管理当前账号”支持编辑、排序、清除本机登录和移除。清除立即换成全新空间，旧档案下次启动清理；其他账号不受影响。
6. 返回键优先在网页内返回；没有历史时将应用放到后台。刷新、前进、首页位于账号菜单。

网页文件选择使用系统文档选择器，不申请整个存储空间权限；alpha.6 的 HTTPS 下载可归档资产库或另存为，使用发起账号的 Cookie，跨站重定向重新选择该账号对应域的 Cookie。`blob:` 下载暂不支持。下载在当前进程内运行，退出进程会取消未完成下载；资产任务在下次打开时显示中断，系统另存为会尝试移除不完整文件。

麦克风与摄像头需要网页来源确认和 Android 系统授权；切换账号/标签会取消未完成的权限和文件选择请求。网页没有 JavaScript 到本地代码的桥接接口，禁止文件 URL、明文流量和忽略证书错误。

第三方身份提供商可能限制嵌入式 WebView 登录。不会伪造浏览器身份或承诺所有登录方式可用；应以真实设备和账号验收为准。系统浏览器的登录属于系统浏览器，不能自动带回此应用的独立档案。

## 本地数据

发布版包名：`com.forcemind.chatgpt.profiles`；调试版加 `.debug`，数据分别存储。

- `files/workspace.json`：账号备注、当前账号、标签和待删除档案。
- `files/workspace.backup.json`：前一个有效状态；主文件损坏时保留损坏原件并尝试恢复，均损坏则停止启动。
- `files/revoked-profiles.json`：清理过的浏览器档案标识；备份恢复时也不能重新启用这些档案。
- 每个账号的 `profile-<UUID>` 由 WebView 自身管理，包含 Cookie 和网页存储；账号清除时更换 UUID。登录弹窗绑定发起账号的同一档案。

系统备份已禁用。不收集密码、不建设云同步。卸载应用会移除其本机空间；清除本机空间不删除 ChatGPT 云端对话。

## 开发与验证

需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0；项目固定 Gradle 8.13 / AGP 8.13.2 / AndroidX WebKit 1.17.1。配置 `ANDROID_HOME`，或创建不提交的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
# 已连接支持多档案的设备或模拟器后：
./gradlew connectedDebugAndroidTest
```

调试 APK 位于 `app/build/outputs/apk/debug/`。单元测试验证账号/标签状态、清除后的档案轮换和导航边界。设备测试使用拦截的本地 fixture（明确标记非 ChatGPT）验证 Cookie、LocalStorage、IndexedDB、CacheStorage 隔离，以及清除记录在损坏恢复时的保护；不会发送模型消息或使用真实凭据。

GitHub Actions 默认运行检查、调试与未签名 release 构建。维护者手动运行 `Android` 工作流，可以另外启用模拟器验收；已完成构建时可选择仅运行设备检查。检查与设备测试结果分别保留，不把编译通过描述为真实登录验收。

alpha.6 产品源码 `a1b8d1a` 的 [构建检查](https://github.com/ForceMind/ChatGPT/actions/runs/39735716189) 通过，Lint 无错误、17 条非阻断警告。[设备检查](https://github.com/ForceMind/ChatGPT/actions/runs/39735751949) 中手机 5 项、平板 3 项及静态 HTML 正文/脚本未执行断言通过，截图已实际查看；随后主界面流程遇到模拟器渲染缓冲错误与 ADB 离线，总作业超时，不能把总作业标为通过。[独立启动补查](https://github.com/ForceMind/ChatGPT/actions/runs/39760503818) 的原生账号创建、切换及进程重启后恢复当前账号通过；之后实际网页截图阶段再次渲染错误 / ADB closed，总作业失败。真实登录、语音、附件发送和可用于官网的网页截图仍待实机验收。

本地试用安装包位于 `release/ChatGPT-Profiles-3.0.0-alpha.6-android.apk`，相邻的 `SHA256SUMS-android-alpha.6.txt` 与 `verification-android-alpha.6.json` 记录校验和证据边界。签名证书与公开 alpha.1 一致，版本号递增；实际用户登录与数据覆盖升级仍待实机验收。公开 Release 与官网暂未更新为 alpha.6。

## 签名构建

发布 APK 使用固定签名身份，以便之后覆盖升级。签名材料不进入 Git；本机 `keystore.properties` 结构：

```properties
storeFile=.signing/android-release.p12
storePassword=YOUR_LOCAL_SECRET
keyAlias=chatgpt-profiles
keyPassword=YOUR_LOCAL_SECRET
```

```sh
./gradlew assembleRelease
```

维护者应离线备份密钥和密码；丢失密钥后无法为已安装版本提供同签名更新。签名在本机完成，密钥不上传到 GitHub。CI 使用显式的 `-PunsignedRelease` 生成未签名构建；它必须经过本机 `zipalign` / `apksigner` 签名与校验才能作为安装包发布。

下载 CI 未签名包后，也可设置 `JAVA_HOME` 和 `ANDROID_HOME`（或直接设置 `APKSIGNER_JAR`、`AAPT2_BIN`），运行：

```sh
python3 scripts/sign-apk.py /path/to/app-release-unsigned.apk
```

脚本核对 APK 的实际包名、版本和 debug 标记，再检查 ZIP 对齐、使用本机固定身份签名并验证，默认输出到 `android/release/`。拒绝覆盖已有文件，可用 `APK_OUTPUT_DIR` 指定独立输出目录。Gradle 生成的包已有对齐；未对齐的输入会明确停止，需先使用 SDK 的 `zipalign` 处理。

## 参考

- [Android WebView 多档案绑定](https://developer.android.com/reference/androidx/webkit/WebViewCompat#setProfile(android.webkit.WebView,java.lang.String))
- [ProfileStore 的创建与删除限制](https://developer.android.com/reference/androidx/webkit/ProfileStore)
- [AndroidX WebKit 发布记录](https://developer.android.com/jetpack/androidx/releases/webkit)
- [AGP 8.13 工具兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

当前交付证据与待验项统一见根目录 [MACOS_MIGRATION_PLAN.md](../MACOS_MIGRATION_PLAN.md)。
