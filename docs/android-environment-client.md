# Android Beta.6：标题标签与页面导航

## 当前主线：Beta.7 Gecko VLESS 同路由（实施中）

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

## 当前交付

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
