# ChatGPT Studio for Android

项目已更名为 **ChatGPT Studio**，当前公开 APK 为 alpha.16，历史版本保留，应用包名和签名身份保留。

原生 Java + Android System WebView。当前公开预发布版为 `3.0.0-alpha.16`，在此前工作台、转换、附件任务和加密草稿 / 阅读位置恢复基础上统一新图标与发布版本；APK 沿用既有固定签名，历史安装包保留。打开即进入网页，没有地址栏，延续白色、浅灰和黑色控件。手机与平板使用同一 APK，各设备分别登录。

下载见[GitHub Release](https://github.com/ForceMind/ChatGPT/releases/tag/v3.0.0-alpha.16)。普通 APK 的业务内核与数据目录保持，未加入 Gecko；每账号浏览器语言和时区尚未迁入。[独立环境 PoC](environment-poc/README.md)只验证研发路线，不是普通客户端下载版本。完整页面快照和离线阅读属于下一版计划。

## 下一版标签管理预览

交互提案位于 [ui-preview](ui-preview/)。在仓库根目录执行 `node android/ui-preview/serve.mjs`，打开 `http://127.0.0.1:4187/`；使用明确的示例账号和对话，不连接真实 ChatGPT。手机采用底部标签数量入口和两列卡片，平板提供三列总览；支持账号筛选、搜索、固定、重命名、关闭与恢复。此为待确认的交互预览，尚未替换当前原生界面。

持续后台保活、Activity 重建和逐 Android / WebView 版本验证已列入开发计划。当前公开 APK 的页面复用与草稿恢复不等于锁屏后仍持续运行，设备测试与真机 / 实站验收分别记录。

## alpha.10 页面恢复

- 切换账号和已打开标签复用现有 WebView；最近关闭的页面在内存中保留最多 5 个、最长 10 分钟，撤销关闭时可继续原页面。超过保留范围后重新加载网页，并尝试恢复最近保存的状态。
- 前台当前页每 4 秒检查草稿及阅读位置变化，切换和生命周期回调补快照，后台不轮询恢复记录。快照按账号、登录空间、标签及稳定网页地址绑定，使用独立本机密钥加密；不保存登录表单、验证码、页面 HTML 或临时聊天内容。
- 网页已有不同草稿、用户已操作、正在生成或最后一条用户消息与本地草稿相同时，不覆盖或重新填回。账号菜单的“本地恢复草稿”可取回冲突版本。恢复不会点击发送；长账号名称也不会再挤掉顶部任务状态。清除本机登录会同时清除该空间的页面恢复草稿，保留资产库。

草稿单份最多 10 万字符，可另保留一份冲突版本。Android 结束进程后仍须重新加载网页；最近一个检查间隔或未完成保存的瞬间内容可能丢失，网页附件和模型生成不能通过快照继续。已在隔离模拟器补验保存快照后真正 force-stop 并以新进程恢复草稿和位置，个人数据升级仍未验证；不承诺退出后保留完整网页运行进程。

[构建检查](https://github.com/ForceMind/ChatGPT/actions/runs/39799780237) 与[设备任务](https://github.com/ForceMind/ChatGPT/actions/runs/39799892017) 对应 `60576b6`：63 项单元通过，Lint 无错误 / 32 条警告；device job 在 Android 15 x86_64 模拟器上通过手机 3 项、平板 3 项定向流程，均无失败或跳过。实际验证 Activity 重建、textarea / contenteditable、阅读锚点、网页草稿冲突、同文已发送、账号切换 / 撤销关闭复用原 WebView，以及两个附件的实际摘要和一次发送。手机和平板截图已查看，均为明确的离线素材；真实 ChatGPT、真机和个人数据升级仍待实机验收。每账号浏览器语言与时区尚未迁入。

补充[实际进程重启检查](https://github.com/ForceMind/ChatGPT/actions/runs/39810750548)使用 debug 夹具源码 `072e220`，正式产品代码与 alpha.10 的 `60576b6` 无差异：旧 PID 确认退出，新 PID 启动；下次启动夹具没有草稿原文，新空页面仍取回已保存文本与阅读位置，账号 / 登录空间 / 标签标识保持。原始记录见 `docs/benchmarks/android-process-restart.json`，截图已查看。该检查仍是离线模拟器，不替代真实 ChatGPT 或未保存瞬间内容的验收。

## alpha.9 交给 GPT

资产工具栏、单文件菜单及 Skill 详情均可打开原生确认窗口。选择附件、目标账号、Skill 或补充要求及输出语言后，点击“新建对话并发送”，在目标账号新标签页走正常网页上传和发送流程。跨账号带文件须明确勾选；最多 20 个文件、单文件 100 MiB、总计 200 MiB。HTML、SVG 和代码使用文本附件名称，字节与原件保持一致。

顶部可查看任务、停止处理，账号菜单提供“网页处理任务”记录和目标对话入口。应用离开前台、账号空间或目标标签改变时停止自动处理；网页可能留下附件或草稿，请手动检查。发送结果未知、关闭或重启后均不会自动重发。提示词使用本机密钥加密保存，上传副本只供当前任务读取，结束后撤销并清理；原件保留。发送成功状态只表示网页显示了用户消息，模型结果需在目标对话查看。

[构建检查](https://github.com/ForceMind/ChatGPT/actions/runs/39791874548) 对应 `3e85900`，57 项单元通过，Lint 无错误 / 32 条警告；测试 APK 也纳入编译。[定向设备检查](https://github.com/ForceMind/ChatGPT/actions/runs/39791957832) 在同一源码上分别通过手机 1 项、平板 1 项完整流程，验证跨账号确认、实际文件 SHA-256、目标账号、一次发送、原件/归属与授权撤销，截图已查看。使用明确标记的离线网页，不调用 ChatGPT 服务；不是完整 13 / 11 项重跑，也不代表真实 ChatGPT、真机或个人数据升级验收。

## alpha.8 视觉导出

已包含在 alpha.8 本地试用包中，alpha.7 APK 不包含本节能力。从文件菜单选择“转换格式”，生成原账号的新副本，保留原件及派生关系。

- HTML → 整页 PNG / PDF，Markdown → PDF；PDF 按静态屏幕图像分页为 A4，文字不可选择、链接不可交互，打印专用 CSS 尚未应用。原 HTML / Markdown 保留，脚本与外部资源不加载。
- SVG → PNG / JPEG / WebP，保留形状和比例，PNG / WebP 保留透明度，JPEG 使用白底；JPEG / WebP 默认质量为 90。
- PDF 选页 → PNG / JPEG：输入 `1,3-5` 这样的页码，保持指定顺序，单次最多 20 个不同页面；每页为独立副本。失败或取消时保留已成功归档的页，并显示实际错误。

页面渲染输入上限 2 MiB，宽度可选 640 / 1280 / 2048 / 4096 像素；图片上限 1600 万像素、高度 16384 像素；页面 PDF 最多 100 页，结果上限 64 MiB。页面转换采用独立临时进程与独立 WebView 目录，完成后释放；原件和账号资产索引由主进程核对后归档。该进程不接收浏览器登录会话，不更改主浏览标签的整页绘制设置。

PDF 使用有界条带逐段、无损压缩写出，不同时保留整份文档的位图；SVG 仅在固定可信图片包装页读取实际几何尺寸，源 SVG 保持不可交互的图片模式。已验证实际 PDF 首尾页和条带边界、SVG 像素和透明度，不用文件存在或扩展名代替内容验收。

## alpha.7 文档转换

已包含在 alpha.7 本地试用包中，alpha.6 APK 不包含这些能力。在文件菜单选择“转换格式”，确认输出说明后生成原账号的新资产，保留原件、摘要与派生关系。

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
- alpha.7 增加上面的三种文档转换，alpha.8 提供 HTML/PDF/SVG 导出，alpha.9 增加网页附件任务，alpha.10 增加草稿 / 阅读位置恢复；每账号语言和时区仍待迁入。这些能力与桌面端分别验证，不用“复制指令”冒充已经上传或发送。

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

alpha.8 的 [构建检查](https://github.com/ForceMind/ChatGPT/actions/runs/39776251937) 与 [离线设备检查](https://github.com/ForceMind/ChatGPT/actions/runs/39776331986) 均对应产品源码 `1471e88`：47 项单元，Lint 无错误 / 22 条警告，手机 11 项 / 平板 9 项全部通过、无跳过。设备为 Android 15 x86_64 模拟器的手机和平板尺寸，验证原生菜单、实际文件内容、账号归属、原件不变、长图顶底、PDF 首尾与条带边界、SVG 比例/透明度、选页顺序、失败清理与重试，以及转换进程退出；实际输出和截图已查看。

原生账号创建、切换和进程重启后的当前账号恢复曾单独通过；实际 ChatGPT 网页截图阶段出现过模拟器渲染错误与 ADB 离线。离线工作台测试不覆盖真实登录、语音、附件发送及官网主界面截图，这些仍待实机验收；此前证据保留在根目录迭代记录。

本地试用安装包位于 `release/ChatGPT-Profiles-3.0.0-alpha.8-android.apk`，相邻的 `SHA256SUMS-android-alpha.8.txt` 与 `verification-android-alpha.8.json` 记录校验和证据边界。已核对 ZIP、包名/版本、非调试标记、非导出的独立转换 Activity、120 Skill 摘要和许可证，测试 Provider 未进入 APK。签名证书与公开 alpha.1 及本地 alpha.6 / alpha.7 一致，版本号递增；实际用户登录与数据覆盖升级仍待实机验收。公开 Release 与官网暂未更新为 alpha.8。

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
