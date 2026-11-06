# Android 独立环境客户端：文件与任务主线

## 当前开发：日期版本、本地导出与资产任务

第一阶段已交付 `2026.11.06-2024`，下载与实际结果见下方“最新交付”。当前推进第二阶段资产/Skill 自动任务，接线代码已落盘，尚待 Actions 完整验收。

用户已要求继续 Android 主线，alpha.22 原包 UI 单独回迁暂缓。当前顺序为：先补齐 Gecko 的 blob/data 本地文件导出，再接入新环境“资产 → GPT / Skill”完整流程；两步沿用原固定 Beta 包名、证书、账号空间、加密恢复与热标签，不合并 main、不公开发布、不部署官网或更新个人设备。

从下一次交付起，用户可见版本名使用上海时区 `YYYY.MM.DD-HHmm`，例如 `2026.11.06-1835`，APK 文件名采用同一版本。Android 内部 versionCode 从 3000031 递增至 3000032，继续由整数保证覆盖升级。一次完整构建只生成一份时间版本元数据，人用包和所有 CI 变体共用；复用原 APK 时保留其原日期，旧交付记录不改名。本轮仅接入 Android，其他平台在各自后续版本接入，当前公开下载与更新清单保持原状。

第一步交付可安装的日期版候选及本地导出证据：HTTP(S) 仍严格核对原线路票据；blob/data 使用单列的本地来源、账号及文档授权，消费浏览器原字节流，不冒充网络线路、不为导出放开任意本地页面导航。成功文件写入原账号资产库，失效、取消、关闭及迟到回调不能误归档或串号。

随后在同一基础完成资产/Skill 的附件与完整指令、一次发送、清晰进度以及取消/失败恢复，复用原工作台流程。用户已明确批准新浏览器验收范围：Actions Android 9/15 的 Blob 图片/HTML、data 文本、账号切换/关闭/取消、资产/Skill 一次发送及旧登录/恢复/热标签/同签升级。所有构建与测试仍在 Actions，只用合成数据，不截图、不访问真实 ChatGPT。

日期机制构建 `42208305365`（`0be651b3483400a1964c1d95685e82499e215427`）已成功：人用 ARM 与所有 CI 变体共用 `2026.11.06-1911` / `3000032`，包内版本、BuildConfig、文件名和 receipt 均经 Actions 检查。该轮只验证版本机制和打包，未运行浏览器，也未包含随后完成的本地导出代码，不能作为新导出功能验收。

第一阶段的起始接线采用原始 Gecko 外部响应流、独立来源授权及原账号资产库；CI 的 EOF 后暂停点只观察真实流程，不能提供文件或授权。交付门槛要求 Android 9/15 的在线/断网三格式原字节、归属、前台换账号、保存取消、关闭源标签以及原 HTTP 下载回归，连同旧登录/恢复/热标签和同签升级证据。后续失败与实际修正记录如下，新 Gecko 的资产/Skill 自动任务继续第二阶段接线与验收。

首轮完整验收 `42210340687` / `2ecb2a1045c841f39a711f7a344888b818ab538d` 使用日期 `2026.11.06-1927`：Actions 构建、lint、签名/隔离/包检查通过；实际 17 份 JUnit XML 合计 **85 测试、0 失败/错误/跳过**，16 项原网络夹具测试通过。两端 human 同签升级与 synthetic 完整登录/恢复/热标签回归均通过；六组网络通过五组，RAW35 在初始 `probe-owned-target` 的 `SocketException` 后返回失败，尚未进入页面。本地导出两端均实际点中首个 HTML 按钮，但未出现保存 Sheet；当前没有新导出成功证据，交付门禁已停止，没有生成对外候选。保留首错及日志，定点修正后继续原断言验收。

取证轮 `42212998959` / `ed3518ece3ddc2436cc42fa8c0b77094ed0f53dd` 的两端日志均为真实 click 激活、eligible/source/owned/route/gesture 全部 true，随后进入本地 `onLoadRequest`，没有 external-response，末态 export events 为空。对照现有 DENY 分支，已确定本地下载在原始流回调前被导航规则拒绝；不能只放开所有 blob/data 来让 HTML 进入页面。接下来接通单独的本地保存渠道，保持普通本地导航拒绝和原字节/归属/取消断言。本轮完整构建和六组网络通过，RAW35 初次探测失败没有复现，仍保留原错；human 两端及 synthetic35 通过。synthetic28 冷恢复重新启动时 `am start` 退出 255，随后模拟器 TCP5554 拒绝连接，退出原因尚未查明，不记为恢复通过，也未改变断言。

定点修复已改为原文档本地保存通道，待 Actions 运行：只接管同一 Window、真实激活的 `anchor[download]` 本地意图；窄化的 Gecko `exportFunction` click 包装覆盖 detached 锚，普通 click 调用原方法。原文档的保留 `content.fetch` 只读捕获的 blob/data，确认保存后才读取正文；每次最多 16 KiB，Native 实际消费后 ACK，再沿原 InputStream/PFD/主进程 Ledger 校验 EOF、大小与所属。普通 blob/data 顶层导航继续拒绝；没有改 HTTP 原方法、线路票据、数据格式或原验收断言。失败取证另增加 Android9 runner 内核与进程内存快照，用于定位前述模拟器断连。

第二阶段接线已写入，首轮构建通过，仍需运行验收：主进程统一保留 HandoffStore/附件授权/一次发送落盘栅栏，Gecko 槽只持有绑定具体账号、标签和文档的短期执行能力；进度、停止、历史及打开原标签复用已有流程。下一交付内部序号 `3000033`，用户可见日期由该批 Actions 生成；同签升级基线改为刚交付的 `2026.11.06-2024` / `3000032` 原包，Beta.7 记录原样保留。已批准的两端五项任务验收为成功、上传中取消、上传失败、明确新任务恢复、发送 ACK 丢失后结果待确认且重开不重发。只有完整指令、真实 multipart 字节与归属、实际主进程阶段、发送栅栏及原登录/恢复/热标签同时通过才交付。

第二阶段首轮 `42221239120` / `f923b90913dcb57e434e9c3acd3fb9de6f04003d` 构建与包检查、两端本地导出及六组网络通过，human35 和 synthetic35 通过。任务两端都完成源资产下载、原生50字草稿、热页、双附件/Skill/目标B及跨账号拒绝/确认；首正常任务在 `uploading` 等待90秒后失败，未进入发送阶段。原产物没有最终 server/AX/Probe 回读，不能把刚 GET 后的早期 upload0 样本当最终状态；驱动已补失败后单次只读取证，保留首错，不重试输入或发送。

固定 Gecko158 源码确认一处协议缺口：`HTMLInputElement.cpp:7359–7399` 会先查询 MIME 的扩展列表，未知随机 MIME 没有扩展时跳过，故不能依赖它出现在 FilePrompt；扩展名分支与 `AppendRawFilter(token)` 则原样传递点号 token。两端原日志已出现 `Prompts handleEvent file`。最小修正改为合法随机 `.studio-UUID`，Java 仍精确校验该 token、MULTIPLE、Session 与原文档；不匹配立即明确终止。Native 中文白名单失败原因进入原任务记录，fenced 失败仍为 uncertain。新增诊断仅双测试旗标开启，记录 chooser/copy校验/confirm/附件计数，不输出正文、URI 或 nonce。实际上传/发送仍待下一轮原五场景验收。

同轮 synthetic28 在 cold 的 `am force-stop` 退出255，随后 QEMU TCP5554拒绝连接；runner ps 已无模拟器，内核没有 OOM、QEMU或segfault条目，退出原因未知。human28 在启动的第二次 `adb getprop sys.boot_completed` 挂住至40分钟取消，Python升级用例根本未开始，不能记为数据保留通过。下一轮 Android9 改用自管启动器：沿原 API28/default/pixel2/2CPU/3072MiB/swiftshader_indirect，保留真实 PID/退出码/原始stderr；每次启动期 adb 读取有界，仍运行原 human/synthetic 用例及全部原断言，不修改产品恢复或保活策略。

修正轮 `42228848160` / `fddfb693abbfb12f71920a5deff2ff161593c76a` 的 Android15 已通过真实双附件 FilePrompt、复制哈希及网页附件确认：一次 multipart 的 HTML 113字节、PNG 70字节正确；Native AX 的完整891字指令 SHA 等于任务 promptSha256。随后 `sending→uncertain` 仅270ms，server sendAttempts=0，未进入25秒 ACK 超时。AX 中按钮中心1057超出 Gecko canvas 的底1048，直接暴露发送几何/可见区域问题，尚未声称旧错误枚举已实测。最小修正沿已有15秒指令验证总期限收起键盘、滚动并用固定158矩阵读取真实可见点，连续两次稳定才写一次发送栅栏，栅栏后再次核对全文/附件/文档/点位，再 Native tap 一次；原上传 tap、location 注入与全文 SPA ACK 保留。

同轮 Android9 任务尚未创建：Spinner 首次点击后仍是原表单，旧驱动直接在表单滚动寻找B，缺少真实 ListView popup 门。驱动已限定为原45秒内等实际 popup 后才在该列表定位/滚动，保留各一次点击和原账号/Skill/跨账号断言。另两份 Android9 回归因新增启动器漏装宿主 `libpulse.so.0`，在模拟器版本读取时退出127，App未启动；真实stderr已保存，启动器已定点补 `libpulse0`，不改应用或恢复断言。以上修正仍待下一轮完整验收。

`42232590081` / `dfa3c4aa19a6312062141e955d33d6908af9da47` 的 Android15 首例已真实发送一次：server sendAttempts=1、891字整文 SHA 匹配、网页完整 user ACK、主进程 submitted 均通过。之后驱动错误地把仅在“更多”里的任务入口当作常显按钮，尚未执行其余四项；已改为读实际可点击状态 banner，重开任务先走真实“更多→查看网页任务”入口。该修正不降低正文、次数、状态、fence、staging 或热页门槛。

同轮 Android9 上传通过后，原唯一 commitText 返回成功，但 DOM input/send均0，15秒后失败。原“末态源HandoffDialog”推断已撤回：0400 dump虽exit0却明确报告 cannot get idle state，0401 cat的是旧XML。新驱动每次先删除专用目标XML并确认不存在，只有本次 dump 明确成功才解析新XML；失败记录快照不可用，在原期限内只读等待，绝不使用缓存或增加点击。固定158 `GeckoEditable.getEditable():1354–1362` 在非IC线程返回null，Android9 BaseInputConnection 的空editable路径仍返回true；因此唯一 commit 改为始终向 connection.getHandler 排队（null才UI默认），跨Gecko切换屏障，沿原5秒就绪截止并回UI复核身份与全文。该线程分支及修复效果仍待运行证据。

该轮 build、两端本地导出、五组网络及 human/synthetic 两端完整升级恢复均通过；Android15 TLS初始探测仍在进入网页前失败。已保留跨 RAW/TLS/REALITY 的 HTTPS SocketInputStream/Conscrypt handshake 栈及原站/节点日志，尚无可证网络根因；下一轮仅追加闭门耗时、coreRunning、原代次与安全错误枚举，不重试探测、不改目标/超时/路由断言。不会以首例成功代替两端五场景交付。

`42236811448` / `462326cacba8ada115060c77d507304c5aa6aa74` 两端首例均已实证：真实双附件原字节、一次发送、891字整文 SHA、完整用户 ACK 与主进程 submitted 全部通过。Android15 随后因 `am start` 异步返回而在工作区仍为B时开始A标题切换，失败后工作区已A；驱动已沿原60秒单次open期限等待真实 workspace A/原tab及新鲜A header，才执行原两次标题切换。Android9 虽首例真实提交，但15秒状态读取持续得不到 idle；新鲜度保护正确拒绝旧AX，没有以缓存制造成功。

本批 CI 必要调整只影响测试：新增独立 `UiSnapshot` shell helper，每次新进程/新 UiAutomation connection 直接读取当前活动窗口的真实 AccessibilityNodeInfo，不 waitForIdle、不截图、不触摸；严格校验允许包、仿真器/API/uid/请求、时间、XML SHA与节点数，失败明确不可用。helper 仅在 Actions 用 SDK36 编译和推送临时 jar，不入 APK；原 App 保活及全部业务断言不变。主页面状态读真实 banner，历史重开仍走“更多”入口。

同轮六组网络、两端本地导出、human两端与synthetic35通过；synthetic28的模拟器现已实证自身 SIGSEGV：`emulatorExit=139`，版本37.2.13，应用 `am start` 随后255，原始stderr/内核/进程证据已保留。当前 `swiftshader_indirect` 已被[Android官方图形文档](https://developer.android.com/studio/run/emulator-acceleration#accel-graphics)标为弃用，下一轮保留 API28/default/pixel2/2CPU/3072MiB、原用例与断言，改用受支持的 `swiftshader` 软件后端并关闭 Vulkan；这是假设性宿主兼容修正，是否消除139仍待原恢复回归，未声称已定位具体崩溃栈。

`42241736133` / `8735ba5c4065f69515c51a3e235481ccd5541d80` 的新软件渲染下 human/synthetic 两端完整升级恢复及六组网络均通过，本地导出28通过；本地导出35尚未进入文件步骤，初始 HTTPS probe 2065ms遇reset，失败前 coreRunning=true、原代次未变。根因仍未知，下一轮仅在原合成端口记录TCP reset方向的包头摘要（不保存/打印包体），不改探测、超时或断言。

该轮 Android9 独立 AX helper 已实际运行，多次真树回执与首例一次发送/全文ACK、staging清理、源A双标题热切换均过；第二 `upload-hold` 任务停在ready无tab，新Slot进程5396只恢复旧提交页。源码与进程/事件证据确认：cold onCreate capture后，route listener首次false回放误清新pending，binder尚空，cancel未送主进程。最小修正只把首次握手等待与wasReady→false失联区分，保留pending到route/extension/resumed三门后一次claim；停止与真正失联收口不改。

同轮 Android15 在首个 AX helper 读取时失败，独立 app_process 的主 Looper 未准备，AccessibilityInteractionClient 回调创建Handler崩溃，无有效回执；App业务没有开始。helper现准备并运行主Looper，只读capture在工作线程，仍受同一watchdog/预算、包/仿真器/UID guard与零动作限制；异常明确回unavailable。真实完整五场景仍待下一轮，不以Android9首例或AX编译成功替代交付。

`42244864474` / `d8e7c9bc15efd3181b64d8756641ed04db031f12` 的两端首例均有一次真实发送、891字 SHA、完整ACK和submitted。helper两端也确有新进程真树回执，但状态等待15秒取到A/A0，而workspace仍是B/任务tab；activeWindow不等于已证明的输入焦点，不能据此改App窗口或按预期账号拼树。固定AOSP9/15的getWindows/isFocused/TYPE_APPLICATION/getRoot支持严格读取实际唯一input-focused允许包窗口，helper现同时保存active与focused事实；如真实focus是A就仍返回A。Handoff及本地导出两个实际消费者复用纯 `native_ax.py`，原read-only预算和业务断言保留。

同轮本地导出35已过在线/离线六格式及HTTP，在EOF暂停的前台B核对处遇原uiautomator idle等待超时；本次只更换AX来源，18秒操作截止、20秒EOF控制、9operation与字节/归属/取消不改。human两端及六组网络通过；synthetic35在SDK Emulator下载准备时报ZipFile unknown archive，App尚未运行；synthetic28仍以本轮实际结果为准。以上原始日志/产物保留，下一轮不把缺失focus或SDK准备失败记成业务通过。

## 最新交付：2026.11.06-2024 本地导出候选

第一阶段已完成，第二阶段的新 Gecko 资产/Skill 自动任务继续开发，不以本阶段代替完整主线交付。

- [下载日期版候选：APK、安装说明、SHA-256 与验收证据](https://github.com/ForceMind/ChatGPT/actions/runs/42217329731/artifacts/13503110551)
- [Actions 完整验收：42217329731，全部通过](https://github.com/ForceMind/ChatGPT/actions/runs/42217329731)
- 源码 `82e5015dd141630b3c9619ac61b27167d80f82ff`；版本 `2026.11.06-2024`，上海时区，内部序号 `3000032`。
- APK `ChatGPT-Studio-2026.11.06-2024-android-arm64.apk`，241,805,521 字节；SHA-256 `9a730594220d14c0a4a9abc540eba4c5d1343e1d0b393e5f5a3cb948784687d8`。
- 包名与固定 Beta 证书保持不变；通过 Beta.7 的同签覆盖升级。人用包非 debug、非 testOnly、16 KiB 对齐，无测试 CA、合成节点或 CI Probe。

Actions 实际 17 份 JUnit XML 合计 85 项测试，0 失败、错误、跳过，原 16 项网络夹具测试、lint 和全部打包检查通过。两端同签升级、登录/密文/恢复/热标签完整回归以及六组 RAW/TLS/REALITY 网页和文件同路由全部通过。

本地保存新增 API28/35 各 9 次操作：在线与核心停止后的离线 HTML、PNG、data 文本共六次原字节核对，前台切到 B 后文件仍归 A，保存 Sheet 取消、EOF 后关闭源标签均不发布；普通 HTTP 下载票据仍通过。每端实际保留 8 个 ready 资产（7 个本地导入和1个 HTTP 下载），取消不留下 `.bin/.part`。独立交付门禁复核 Native 双进程阶段、EOF/长度/哈希/归属及真实账本，全部退出 0。

已在线范围读取候选 ZIP：82 项、仅一个人用 APK，压缩包 111,701,706 字节；只读取 8,332 字节的目录、receipt、日期元数据和验收摘要，未下载大型 APK 到本机。该候选仅通过 Actions 分发，产物保留14天；主线仍在 dev，未合并 main、公开发布、部署或更新个人设备。所有浏览器验证限合成数据，没有截图、真实 ChatGPT、真实节点或 OEM 手机验收。新 Gecko 的资产/Skill 自动任务尚未交付。

## 上一交付基线：Beta.7 Gecko VLESS 同路由

本轮已完成固定签名 **3.0.0-alpha.24-beta.7**（versionCode `3000031`）候选，适用于 Android 9 及以上 arm64 设备。只交付 Actions 候选，不合并 main、不公开 Release、不部署官网、不更新个人设备。

- [下载候选压缩包：APK、安装说明、SHA-256 与完整验收 JSON](https://github.com/ForceMind/ChatGPT/actions/runs/42170066469/artifacts/13480599616)
- [最终交付门槛：42170066469，全部通过](https://github.com/ForceMind/ChatGPT/actions/runs/42170066469)
- [安装与数据保留说明](android-environment-client-install.md)

下载需要 GitHub 登录，Actions 产物保留 14 天。已在线范围读取候选 ZIP 的清单、receipt 和两份验收汇总，确认仅包含一个人用 APK；未将大型安装包下载到本机。

| 安装包身份 | 核对值 |
| --- | --- |
| 文件名 | `ChatGPT-Studio-3.0.0-alpha.24-beta.7-android-arm64.apk` |
| APK / 压缩包大小 | 241,769,977 / 111,670,748 字节 |
| APK SHA-256 | `361c4b7120a62f5da632fd4a8d86db937a94741043a80f84e9522eb2dd883173` |
| 固定 Beta 证书 SHA-256 | `bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0` |
| 包名 | `com.forcemind.chatgpt.profiles.networkbeta`，沿用原固定 Beta 身份 |
| 人用包约束 | 非 debug、非 testOnly、16 KiB 对齐；不含测试 CA 或合成节点 |

## 本轮实现

复用已有网络设置与 VLESS 核心，将 Gecko HTTP(S) 请求、真实系统选文件后的 multipart 上传，以及外部响应下载绑定到原线路实例、代次和所属账号。切线不能借用新线路完成旧传输；下载还须完成原字节流与请求回调，才写入所属账号资产库。

修复 Gecko 无法解析普通 DocumentsProvider URI 的路径：在有效选择租约内通过 ContentResolver 读取真实文件，以 64 KiB 缓冲暂存私有缓存，复核账号、页面与线路后交给浏览器。保留原始字节和安全文件名；取消与失败清理本任务临时文件，成功文件留到该标签关闭或下次本槽冷启动。该队列独立于恢复落盘，不更改登录空间、恢复格式或标签保活策略。

## 验收与来源

| 验收 | Android 9 / API28 | Android 15 / API35 |
| --- | --- | --- |
| VLESS RAW 网页、上传、下载经 A/B 线路 | 通过 | 通过 |
| VLESS TLS，含错误主机名与不可信 CA | 通过 | 通过 |
| VLESS REALITY，含错误公钥与 short ID | 通过 | 通过 |
| 固定 Beta.6 覆盖升级、旧 Cookie/存储/密文/资产保留 | 通过 | 通过 |
| 三账号四页隔离、热切换不重载、冷恢复、草稿冲突、原生导航 | 通过 | 通过 |

六组网络用例共有 **24 次完整文件传输**：每条 A/B 线路各上传 27,648 字节、下载 262,144 字节，核对实际出口、SHA-256、账号归属及一次操作计数。8 个身份负例、18 个核心停止后的阻断动作均无静默直连或恢复后重放。空闲切线只调用一次，传输中的切线仍立即拒绝；文档、草稿及 Cookie 保留。

- 应用及原测试 APK：构建 `42161704887`，源码 `1f32390332b2d510f561db241f47068a34568a1e`。实际 XML 为 **85 项单元测试，0 失败、0 错误、0 跳过**；16 项网络夹具测试及 lint、打包、签名检查通过。
- 六组网络和 API28 完整恢复：`42164399330` / `493464bc58e1ec2414fc25535fc9d856620d645c`。原证据不改写 sourceSHA；后续门槛核对源码差异、APK 字节和结果。
- API35 测试包重编及完整恢复：`42170066469` / `b3b2b40a8b668b317a965367acd0ba7fb0081c37`。仅测试同步修正；最终测试 APK SHA-256 为 `d73d6996381665cccfec4f59415dc9ffa0970e12badf5209be858b82567e8881`。
- 两端只读注册落盘屏障分别观测 942 / 2,075 ms；随后仍执行原退出后即时冷读与 Golden 哈希检查。安装包、网络及恢复汇总门槛全部退出 0。

## 候选边界

所有构建和运行验证均在 Actions 的全新模拟器中，只用合成数据，无截图、真实 ChatGPT、真实节点或个人设备操作。Gecko 运行矩阵使用单列的 x86_64 CI 包；人用 arm64 包独立检查与分发，尚不等于 arm64/OEM 真机、真实登录、通行密钥或真实服务兼容验收。旧账号保留原内核和登录空间，不自动搬迁。

本轮同路由证明覆盖 HTTP(S) 网页请求、multipart 上传和 HTTP 外部响应下载。blob/data 本地导出未纳入矩阵；从代码看，缺少 HTTP 响应票据的导出会被拒绝，兼容流程需单列后续工作。新 Gecko 环境的资产“自动交给 GPT”仍未迁入，可手动在网页选择附件；旧账号原流程保留。

网络用例采用单个原生 JVM、每字一次注入及精确前缀确认，所有输入共享原 75 秒截止；原批输入 helper 与共同恢复用例仍保留。此调度不表示 Gecko/IME 批量输入乱序的内部原因已定位或产品性能已优化。没有执行新的 CPU/内存或 30 分钟后台长测。

<details>
<summary>Beta.7 实现与失败修正记录（按发生顺序保留）</summary>

### 起始范围与验证过程

当前批准范围：在真实Android客户端中复用已有VLESS核心，GitHub Actions仅用合成RAW/TLS/REALITY节点验证Gecko网页、真实系统选择文件后的multipart上传、资产下载经A/B所选出口；验证切线在途保护、错误身份/核心停止时不静默直连或重放。发现的必要修复集中于picker/请求lease和下载原代次票据，保持账号/恢复格式与保活策略不变。沿用现有沉浸UI，不增加代理工具栏、系统VPN或其它平台。

目标为固定签名Beta.7 / 3000031候选。旧数据覆盖升级及Beta.6界面/恢复回归单列；新网络矩阵采用全新合成空间，不篡改旧数据golden。无截图、真实ChatGPT或个人设备操作，不合并main、不公开发布、不改官网。只有完整路由/字节/隔离及必要回归通过才交候选；下面Beta.6仍是已交付基线。

首轮42148393692（8e86909）单元、人用包、Java与普通合成包编译通过，网络合成包在NetworkSecurityConfig lint要求显式includeSubdomains处停止，设备用例尚未运行。生成XML改为两个精确owned域的includeSubdomains=false，不加基线或关闭规则。

42149349987网络合成包编译成功，包检查未找到原始CA文件路径而停止。检查改为同时验证编译资源表、application的networkSecurityConfig及APK内精确CA字节，兼容AGP优化后的资源路径；人用包同步从编译资源表证明不包含测试信任配置。设备矩阵仍未运行。

42150503896的完整build已通过，确认两类包的资源/字节检查及编译、lint通过。设备准备阶段主动取消此旧驱动批次，合入静态发现的必要修正后重跑：fresh install先初始化私有目录再写线路配置；等待Worker/IndexedDB/Cookie完整观测后断言；请求broker超时按原operation撤销lease，晚到回复不能放行请求。未改变超时或业务断言，取消批次不记为设备验收通过。

42151389890（407bca2）build及四组原客户端/升级回归全通过；新增六组网络均停在首个Gecko页面，无网页/文件同路由通过证据。RAW35原站仅收到核心/echo探测A，Gecko绑定及lease已批准，随后OnLoadError、原站/c/a1计数0。代码确认原夹具将CA:TRUE证书直接用于HTTPS站点，Mozilla PKIX会拒绝该叶证书；先仅修--gecko-network原站为同一CA签发的CA:FALSE/serverAuth/精确DNS SAN叶证书，保留CA/节点/负例，不关闭证书验证或修改客户端策略。

纯网络夹具复测可使用reuse_beta7_checks：固定复用42151389890/407bca267f2bf12a7af0b5aaa3f13b8b46424c7c原APK、信任CA与主机核心，以及四份原验收JSON。工作流须先核对build/4accept成功、App及原验收输入无差异，最终门槛再次核对原APK哈希；原证据sourceSHA保持不变，新网络证据同时记录driver和application来源。每次重新执行六组网络实际交互，不把原失败标为通过。人用ARM APK SHA-256为fe5514d7a924a18cfd9be5551bcdb40d0e8af7b8fa94311078e8b3a861085bd0。

42153825249（1d23b02）的来源校验及四份原证据复用通过；Android9/15 RAW的Gecko首屏、Worker/IndexedDB已观测，证书修正消除了此前首屏阻塞。新首错发生于type_draft调用原page检查时，网络handler未写requests[/c/a1]导致Accept-Language读取KeyError；补记录真实请求头以满足原契约，不填预期值、不去掉语言隔离断言，六组继续复测。

42154517285（f8b99c6）RAW35已通过实际请求头、环境及可信草稿输入，首错转为文件输入按钮定位超时。原生XML证明文件输入可见且可点击，其名称在Button.text（含浏览器Browse/未选文件状态）而非content-desc；上传/出口按钮也使用text，下载链接使用content-desc。驱动据实区分定位字段，保留唯一性、可用性及原生input tap，不增加JS点击或伪造文件。

42155283165（5f7c939）RAW28及TLS35已打开真实DocumentsUI并点选owned文件，但Gecko未返回File；RAW28日志5842为NS_ERROR_FILE_UNRECOGNIZED_PATH。固定158源码的FilePrompt URI解析不能解析普通DocumentsProvider，需在有效picker lease内用ContentResolver读取真实字节，后台暂存私有cache后复核owner/epoch/route，再交受支持文件URI；失败取消清理，不动原登录/恢复路径。此生产修正完成后须完整重建及回归，不沿用407 APK。另RAW35出现host /status TLS握手8秒超时，网络夹具的TLS listening accept会阻塞接收线程；仅网络模式将握手移至有界worker，保留原8秒驱动超时和首错，不重试掩盖。

provider适配已实现，待全量Actions验证：独立picker单线程使用64KiB缓冲，不占恢复落盘IO；元数据查询/读取共用取消信号，复制前后复核原owner/storage/epoch/lease。私有cache按槽/Entry/随机任务分目录，保留原安全文件名；失败取消清本批目录，成功保留到Entry关闭或下次本槽冷启动。没有增加权限、配额、超时、数据格式或UI。

42157104403（08bee00）完整build通过；Android9 RAW/TLS/REALITY与Android15 RAW/REALITY共五组网络完整通过，确认真实provider文件适配、双出口上传/下载、字节与账号归属、保留document/草稿、核心停止阻断及恢复。Android15 TLS在A传输完成后的切B仍收到busy=true/CONNECTED/selected=0/generation=2，原保护正常，正向驱动未建立空闲边界。仅CI控制增加显式waitIdle：原45秒截止内等待controlsBusy=false，在同一UI tick只调用一次select，回执记录调用次数；在途拒绝用例仍立即调用一次且断言拒绝，身份负例先等待空闲再单次选择，不重试切线。原恢复/升级三组通过，API28 synthetic在WebView provider adb install 90秒超时（客户端未启动），保留原错及日志，下一完整批次仍保持原超时。

42158765402（a17961c）Android9全部三协议及Android15 TLS通过，确认空闲单次选择/在途立即拒绝均有效。另两首错分别为：RAW35原生106事件全部accepted且顺序可重建50字，首个DOM/model完整样本却有两字符后移（同document、50次trusted input）；REALITY35系统抽屉与其后方tile均导出同provider名称。仅网络驱动按实际抽屉祖先限定真实点击目标，且逐字单次注入/精确前缀ACK、所有输入共用原75秒期限，不清稿、替换或重发；helper和共同恢复驱动保留批输入，此调度不表示Gecko/IME内部批量乱序已修复。

该轮API28原恢复用例在UI之后冷读注册表失败，API35及两组human通过。从代码看，已有严格只读20秒注册落盘屏障此前仅运行于API35；将原屏障同样运行于API28，检查精确的原账号与声明UI账号、各自原路径、无重复/未知注册，不改seed/Golden或最终退出后即时冷读断言。实际落盘观测与完整结果仍待下一Actions批次。

42161704887（1f32390）完整APK构建及两组human、API35环境回归通过。网络逐字ACK在API28完成50字耗时28,651ms；API35 TLS在75秒总期限后段反复启动app_process耗尽剩余0.956s，保留TimeoutExpired。仅CI改为一个常驻原生进程逐字调用原AsciiInput.main并返回原receipt，不重发/改字符、仍共用75秒；原helper与App字节不变。API28 DocumentsUI文件卡可见但Recycler不声明clickable，按实际目录/唯一卡片与bounds执行一次原生tap，不改变provider/真实文件断言。

同轮API28环境回归尚未到注册屏障，首错是强刷后的环境检查：0285-https中draft/model和IndexedDB已恢复，worker=null、workerError为空。原刷新等待只等草稿，补入与原page相同的Worker/IndexedDB/登录就绪条件，再执行原环境断言，不改变超时或seed。后续可固定复用42161704887/1f32390332b2d510f561db241f47068a34568a1e的App/test APK与两份human证明；源码差异门槛保证App、原helper、fixture不变，两个环境回归及六组网络均实际重跑。

42164399330（493464b）六组网络全部通过：24次A/B文件传输、8个TLS/REALITY身份负例、18个核心停止后阻断动作均保持原断言，单JVM退出0、每组50个可信前缀确认。API28完整环境/恢复通过，只读注册屏障942ms，旧账号及密文冷读通过；两份human证明沿原字节复用。最后API35旧WebView UI在首次composer focus超时，实际AX显示唯一可见EditText及正常页面，尚不能实证popup内部原因。仅API35测试在原30秒总期限内先确认窗口/布局，按唯一当前WebView原生EditText坐标tap一次，再执行原DOM焦点/InputConnection/trusted/fulltext断言；不重发tap、不改生产或Golden。重编API35 test APK，原App、API28及六组网络证据按源码/哈希护栏复用，保留原sourceSHA，不重复已通过网络矩阵。

42169784548（b53aabb）在测试前被旧复用分支残留的互斥开关阻止，未进入源码差异校验或设备验证。将该互斥限制限定回其所属历史复用模式，当前API35单测试包重编仍须通过预先的API28复用依赖检查，以及精确App/fixture源码和原APK哈希门槛；未改变数据或运行断言。

</details>

<details>
<summary>Beta.6 基线记录（以下为当时交付状态）</summary>

### Beta.6 交付

本批已完成，交付固定签名 **3.0.0-alpha.24-beta.6**（versionCode `3000030`），Android9及以上arm64候选。仅推送dev、通过Actions分发；未合并main、未创建公开Release、未改官网或个人设备。

- [下载APK、安装说明、SHA-256与完整验收JSON](https://github.com/ForceMind/ChatGPT/actions/runs/42107129970/artifacts/13452013891)
- [最终交付门槛：42107129970](https://github.com/ForceMind/ChatGPT/actions/runs/42107129970)
- [安装说明](android-environment-client-install.md)

下载需要GitHub登录，Actions产物保留14天。压缩包111,572,800字节；APK241,735,569字节，含完整Gecko内核。

| 项目 | 精确值 |
| --- | --- |
| App源码与原始构建 | `2b8738187ca7d02e83700334859ba77acd32f87a` / [42090385251](https://github.com/ForceMind/ChatGPT/actions/runs/42090385251) |
| 最终API35测试与运行 | `fa31871a2152482f21b01a2957ba7dcb10bc2605` / 42107129970 |
| 包名 | `com.forcemind.chatgpt.profiles.networkbeta` |
| 文件 | `ChatGPT-Studio-3.0.0-alpha.24-beta.6-android-arm64.apk` |
| APK SHA-256 | `54c1c80e8b6695501802567318ef2caa945f1cd48248acce41fe3f0c68ae15d4` |
| 固定Beta签名 SHA-256 | `bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0` |

## 本批用户行为

旧WebView和新Gecko页面共用紧凑原生顶部：左侧账号名称与小色点，中间直接展示可点选的对话标题，右侧固定刷新和更多。普通手机优先容纳两个完整标签；窄窗口保留一个完整标签，多标签可横向滚动，不缩小字号。当前标签自动进入可见范围，长按或“更多→标签管理”保留管理入口。无地址栏、无常驻底栏，白灰黑风格沿用。

更多首排提供后退、前进，按实际历史状态置灰；另有强制刷新。刷新只作用当前标签，加载或保护期间可停止；强刷仅绕过缓存，不清Cookie或登录，不自动重发POST。生成、文件任务、附件、恢复处理中或无法确认草稿保护时保留页面并提示，不强行丢弃未保存输入。

导航先捕获并加密保存草稿，成功后再次核对账号、storage、标签、URL及文档身份再执行；目标变化、超时或保存失败取消操作。修复历史返回时缺少该URL恢复来源，以及浏览器恢复可见文本后受控输入模型仍为空的问题：使用有界运行期历史草稿，并在文字已相等时幂等同步input，不重复插字或发送。

历史草稿缓存只保留URL、草稿、阅读位置及必要归属，不复制整页预览或图片；每标签最多6页、每进程最多48页、总256000序列化字符，超限LRU淘汰。这不是打开标签数量限制，不暂停或回收活标签。进程结束后仍只保证原有当前页加密恢复契约，不承诺所有历史页草稿持久保留。

Beta.5的账号环境功能沿用：旧账号继续原WebView与登录，不转换内核或复制Cookie；新建独立环境账号可选择语言和IANA时区，创建后固定。相同环境组合共用运行时，账号以contextId隔离Cookie、localStorage与IndexedDB。冷启动先本地只读副本，选择继续才连接；不同非空草稿明确选择，不自动覆盖或发送。

## 实际验收与来源

构建及所有运行检查仅在GitHub Actions的全新模拟器使用合成数据。没有截图、真实ChatGPT、个人账号或手机操作。

| 检查 | 实际结果 |
| --- | --- |
| 编译、单元与Lint | 原始构建17套件、85测试，0失败/错误；lint 0错误、42警告，未放宽规则 |
| 人用包 | 固定签名、minSdk28、arm64、16KiB对齐；无CI控制Activity、测试域或信任材料 |
| Beta.5→Beta.6升级 | API28同源码x86兼容变体、API35原始arm64包；同签名/UID，不卸载或清数据。旧账号对象、两标签、HttpOnly/Secure Cookie、localStorage、加密负载/恢复记录、资产、网络密文和偏好保留 |
| Android9完整功能 | [42092833123](https://github.com/ForceMind/ChatGPT/actions/runs/42092833123)，源码4c72fb0；两种内核的真实标题切换、后退/前进、普通/强刷、DOM及受控草稿、Cookie/存储、原数据收尾全部通过 |
| Android15完整功能 | 42107129970；相同App字节，单独编译并绑定新测试APK；两种内核同样全部通过 |
| 热切换与隔离回归 | 三账号/四页面、两组环境；主页面与Worker语言/时区/偏移、Accept-Language匹配；账号Cookie/localStorage/IndexedDB隔离，同账号标签共享；切换document身份与GET次数不变 |
| 恢复回归 | 实际竖横竖旋转不重载；强停后先只读副本，继续后一次加载；草稿和阅读恢复；冲突先保留网页内容，明确选择后才恢复；发送次数0 |
| 文件回归 | 原生点击合成下载链接，经真实主进程资产入口入库，128字节及SHA-256与所属账号一致 |
| API35冷开前置 | 两次均确认原进程已退出，再唯一一次open；前置与唯一open命令返回共用原60秒界限，实际273/497ms（不含后续页面加载）；无am重试、无closed/255放行 |
| API35测试Profile基线 | 首读只有原Profile，4201ms后新UI Profile真实落盘；原name/path不变、新路径唯一；只读等待不写EXPECTED/Chromium文件，随后原即时冷读通过 |

已通过的Android9与人用包升级证据保留原源码身份，不重写为新运行。最终交付门槛核对原App及synthetic包SHA、各自实际使用的测试APK SHA、签名和完整业务证明后才组装候选。API35测试APK SHA为`1b6a7d2765fb36a20ebc5ffb1f1ff7377305f4a9c0227ef91f4666f436321a96`，其源为fa31871，绑定原App源2b87381；不随用户安装包分发。

API28合成功能使用3GiB、SwiftShader indirect；API35使用4GiB、SwiftShader、禁Vulkan。两者是功能验证，不能据此作CPU/内存性能对比或物理手机最低内存保证。

## 失败记录与剩余范围

原始首错、窗口文字树、日志和来源留在对应Actions产物，本记录保留原因与边界：

- 42077061299：Gecko后退草稿为空；修运行期按URL保留，原磁盘主备不改。42086461399：旧WebView后退显示文字但受控检查失败；补来源与幂等输入同步，未删DOM/model断言。
- 注册扫描曾被动态Chrome临时文件/Session Storage文件替换中断，改为枚举目录并只严格读取目标pref_store，原注册路径和数据断言不变。
- 零间隔adb文本事件曾乱序；CI工具改为一次有节奏原生键盘批次并核对实际事件/字符/SHA，不补字、重试或JS填值。该工具不进入App，不是App输入性能优化。
- 42090385251、42099994705的Pixel Launcher ANR发生在App启动前；固定启动动作仅等开机标志即发MENU。API35改为等待真实焦点窗口后再测试，不关闭或忽略ANR。早期另有ADB通信失败，未确定其全部根因；原失败保留。
- 手动启动脚本曾误判AVD默认位置、调用runner未安装的rg；均在App启动前失败，已改显式临时目录和已预检查系统工具。这些轮次未计为功能通过。
- 新建旧内核测试空间曾在约8秒退出，未等异步注册提交；只对新建夹具补持久屏障，最终旧数据冷读仍即时执行。不能据此宣称生产旧内核新建Profile后立刻强停也能持久，原已知窗口仍需后续处理。

当前只交候选。真实ChatGPT/第三方登录、语音、通行密钥、OEM与arm64真机、最新候选CPU/内存/包体优化、正式原签名升级仍待验收。新Gecko环境的VLESS网页/上传/下载同路由联测，以及资产/Skill自动发送迁入仍属下一主线；目前新环境手动加附件，旧账号原流程保留。没有把原WebView网络结果当作Gecko结果，也没有进入iOS或其它平台开发。

</details>
