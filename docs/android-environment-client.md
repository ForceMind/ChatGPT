# Android 独立环境客户端：文件与任务主线

## 公开渠道：Android 独立 Beta 2026.11.07-1905

用户已明确确认公开此独立Beta，并同步main/dev及现有官网。入口为 [GitHub 预发布](https://github.com/ForceMind/ChatGPT/releases/tag/android-beta-2026.11.07-1905) 与 [官网独立Beta下载](https://chatgpt.xincreates.com/#download-android-beta)。本次复用下方已通过验收的原APK字节，不重新构建或签名；正式Android alpha.22、Mac alpha.23、Windows alpha.24及其下载/自动更新仍保留原版本。

已有同签名固定Beta可覆盖安装，常规版与Beta的账号/资产/恢复空间独立，不自动迁移或更新个人设备。历史正向TLS reset与首次后退归因仍未完成，真实ChatGPT、节点与厂商手机仍待验收。公开发布的完整安装和验证边界见 [发布说明](releases/android-beta-2026.11.07-1905-notes.md)。原候选及诊断过程按当时状态保留。Release已公开（非draft、prerelease），五个原资产ID/大小/摘要不变，原始与国内加速APK入口均返回206和正确ZIP头；官网已部署到现有Pages并通过11次HTTP回读。[实际发布、分支同步与部署记录](releases/android-beta-2026.11.07-1905-publication.json)。本轮未运行网站浏览器或视觉交互验证。

## 原候选构建与验收：2026.11.07-1905

- [下载候选：ARM64 APK、安装说明、SHA-256 与完整验收证据](https://github.com/ForceMind/ChatGPT/actions/runs/42370754805/artifacts/13574370247)（需 GitHub 登录，保留14天）。
- [最终续验及全部交付门禁：42370754805](https://github.com/ForceMind/ChatGPT/actions/runs/42370754805)，全部通过；[原完整矩阵与构建：42366675306](https://github.com/ForceMind/ChatGPT/actions/runs/42366675306) 的原失败仍保留，未改写为成功。
- [安装与数据保留](android-environment-client-install.md)。已有固定 Beta 可直接覆盖安装，不卸载、不清数据；正式版及临时测试版保持各自独立空间，不自动搬迁。

| 安装包身份 | 已核对值 |
| --- | --- |
| 文件名 / 内部版本 | `ChatGPT-Studio-2026.11.07-1905-android-arm64.apk` / `3000034` |
| APK / 候选ZIP字节 | 241,907,607 / 111,828,723 |
| APK SHA-256 | `8555c1b1e8ad64659bdccd8832c84302a2a8986f049235d9d9f77eae416d4125` |
| 固定证书 SHA-256 | `bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0` |
| 包名 / 系统与架构 | `com.forcemind.chatgpt.profiles.networkbeta` / Android9以上，arm64-v8a |
| 人用包检查 | 非debug、非testOnly、16KiB对齐；测试开关关闭，无合成入口/节点输入/测试CA/Probe激活 |
| App / 最终harness源码 | `364ddd14d2fb76f234c3284e1372e8de9b37c4f8` / `a94185dbb39a9115b3b39d3d10dc27e8df223234` |

本候选包含已验证的加密保存竞争修正，保留每账号语言/时区环境、热标签复用、加密页面/草稿恢复、VLESS、资产库与Skill完整任务。App版本、文件名、receipt和一次冻结的上海时区元数据一致。人用包可含被编译期开关和运行guard保护的辅助实现；隔离门禁不宣称所有测试辅助代码已从二进制物理剔除。

| 原严验收 | Android9 / API28 | Android15 / API35 |
| --- | --- | --- |
| 33→34同签升级，旧Cookie/存储/密文/资产及偏好保留 | human/synthetic通过 | 原ARM字节human/synthetic通过 |
| 三账号四页隔离、语言/时区/Worker、热切换不重载、旋转/冷恢复/草稿冲突 | 通过 | 通过 |
| 原生前后退、普通/强制刷新与草稿保护 | WebView/Gecko通过 | WebView/Gecko通过 |
| RAW/TLS/REALITY网页、真实选择文件上传、下载同路由及原负例/恢复 | 三组通过 | 三组通过 |
| Blob HTML/PNG及data文本本地导出、离线/归属/取消/关闭源页 | 通过 | 通过 |
| 资产/Skill完整指令与附件、正常/取消/失败/新任务恢复/结果待确认 | 五项通过 | 五项通过 |
| 原任务崩溃门：owned fatal / 未归属native fatal | 0 / 0 | 0 / 0 |
| 获批保存时序对照 | 未新增此专项 | 旧行对照原断言真实失败单列；修正版保存后导航、先发poll保护拒绝通过 |

实际构建报告为17份JUnit XML、85项Android单元测试，失败/错误/跳过均0；Go、16项夹具、lint、签名/对齐/包隔离检查通过。所有构建与运行仅Actions。完整矩阵13项设备场景先通过，Android15任务场景因AX几何不一致误点失败；纯harness修正未改App及其他driver，固定来源校验后只续验该项。最终所有原升级、导航时序、六协议、导出、任务、零崩溃verifier重新退出0，没有重复后台长测。

Android15最终任务 proof实际为submitted/cancelled/failed/submitted/uncertain，发送/网页click次数1/0/0/1/1，共3次；5次实际上传的10份HTML/PNG原字节和SHA匹配，3条完整指令均1707 UTF8字节/891 UTF16且SHA一致。原生Skill、目标账号与跨账号勾选正确，原资产不变，暂存全部清空，原3热页的document/draft/model与加载次数不变。不确定任务重开不重放；原始crash buffer/stderr均0字节且哈希已核。不得将应用层单次发送扩大为运输层或第三方服务端恰好一次。

候选ZIP附带 `evidence/candidate-recovery-source.json`，分别记录原App/构建来源、最终harness来源、14个未改写的成功job，以及三份未计入通过的原误点失败证据（13571809283、13572559534、13573770384）。原失败与前几次续验未改写成绿色，详情保留在下方过程记录。最终job128398235412/artifact13573896231为实际Android15任务成功证据，最终deliver128400072968全部通过。

已在线有界读取最终ZIP：102项、仅一个人用APK；读取144,505字节、43个Range请求，版本/收据、升级、六协议、本地导出、任务、导航时序和来源记录均核对，原crash源文件哈希一致；未下载大型APK到本机。SHA-256由CI实际重算完整APK，本机在线核对收据及ZIP条目大小，不将元数据读取冒称完整APK重新哈希。

**已知边界**：历史正向TLS reset与首次后退超时仍未严格定位，不把本轮通过称为根因解决；原TCP无响应重传失败继续保留。实站、真实节点、通行密钥与OEM手机仍待验收。当前仅独立固定Beta候选，公开Android仍alpha.22；未合并main、公开Release、部署、改更新清单或安装个人设备。

<details>
<summary>本批范围、首错与续验过程（按发生顺序保留）</summary>

## 本批范围：完整 Android 独立候选交付


2026-11-07 用户已明确选择先交付完整 Android 候选：纳入已验证的保存竞争修正，在 Actions 跑此前批准的 Android9/15 完整回归，交付可安装包；正向 TLS reset 与历史首次后退归因继续列为未定位项，不作为已修复宣传，也不缩减任何验收断言。此前根因目标已由用户清除，本迭代不重启无新证据的机械诊断。

候选内部版本递增至3000034，用户可见名称仍为一次构建冻结的上海日期时间。升级基线改为实际已交付的 `2026.11.07-1001 / 3000033`：ARM原receipt及API28原human proof已核，包名/固定证书不变，旧数据不迁出原空间。当前仅准备dev候选，不合并main、不公开发布或部署、不更新手机。

完整验收包括两系统human/synthetic同签升级与语言/时区隔离、热标签/冷恢复/导航/草稿，六组RAW/TLS/REALITY网页上传下载同路由，两系统本地导出与资产/Skill任务，原零owned崩溃及来源/字节/归属门禁。API35同时执行已批准的三组导航时序对照，严格区分旧单变量对照的expected failure与修正版通过，并随包保存简要原始证据。所有构建与运行只在Actions，用合成数据，无截图、真实ChatGPT或真实节点；不重复此前后台长测。当前待本批实际结果，不以旧SHA通过替代。

本批完整运行 `42366675306`，来源 `364ddd14d2fb76f234c3284e1372e8de9b37c4f8`。构建已通过，冻结版本 `2026.11.07-1905 / 3000034`；实际17份JUnit XML共85项，失败/错误/跳过均0，Go、16项夹具、lint与包检查通过。人用ARM收据为241,907,607字节、SHA-256 `8555c1b1e8ad64659bdccd8832c84302a2a8986f049235d9d9f77eae416d4125`，固定Beta签名和16KiB检查通过。14个设备场景中13个通过，只有Android15任务场景失败；未把构建产物当作合格可安装候选交付。

首错 job `128387214254` / artifact `13571809283` 实证是harness在Skill弹窗几何未一致时误点：focused popup根bounds为[123,883][831,1857]，ListView/选项却暂报从[0,0]起，实际input tap354,189落在弹窗之外。随后Skill仍为“不使用Skill”、jobs为空，未进入文件或消息发送。最小修正只改 `run-gecko-handoff.py`，原45秒动作预算内只读重取直至ListView viewport/目标原始center与focused根、可见祖先几何一致，仍只真实点击一次；不猜偏移、不放宽原15秒选中断言，也不修改App。

为避免无变化的整包与13个通过场景重复运行，新增固定来源的 `android-environment-candidate-resume.yml`：校验原run/source及全部成功任务，限制源码差异仅为已审阅harness、续验工作流和说明，固定driver SHA；复用原APK/fixture/core及未改写的成功proof，只续跑handoff35。最后重跑全部原升级、时序、六协议、导出、任务和零崩溃交付verifier，追加recovery来源记录并保留原失败为excluded-from-pass。App日期、code、APK字节与receipt继续沿原1905，验收脚本来源另记。当时续验尚待结果；最终通过与来源见顶部当前交付。

第一次续验 `42368635085` / job `128391980774` / artifact `13572559534` 已越过前四个任务，但第五项在附件选择前失败。更早AX证据显示资产操作菜单根[28,406][1052,1514]，ListView上沿却为163；harness点击512,982后实际把原文件标为收藏，并未打开任务表单，后续零滚动容器是错误页面的表现。只对spinner启用几何校验不足，现纯harness修正扩大到所有可见ListView菜单：原center不变，不一致只读重取、不tap或scroll；0/多容器也只在原45秒内等真正唯一。原15秒、20次scroll上限、实际tap次数和App全部不改。原两次错误及排除通过的来源都随recovery记录保留，不将其改写为App业务修复。

第二次续验 `42369738440` / job `128395232848` / artifact `13573770384` 在首项跨账号checkbox前失败，jobs仍为空。原AX显示普通表单也暂缺根偏移(28,63)：点击前checkbox y805..889，实际正常y868..952，input tap512,847落在框上方；外层frame y42又在focused root y63之外。现harness普通控件与scroll也要求focused root、外层frame和可见祖先几何一致，保持原center一次tap，不直接赋值勾选、不绕跨账号安全断言。ScrollView内部长内容允许合法超出viewport，但点击中心必须落在各可见clip。App与原APK仍未改，失败来源另列。

</details>

## 前一迭代：Android 15 两项首错定位（历史证据保留）

用户已批准继续定位 TLS startup reset 与旧 WebView 导航超时。专用 `android-stability-diagnostics.yml` 仅在 Actions 构建并运行原 Android15 TLS 场景，以及保留完整 synthetic 客户端前置的原导航用例；不重复后台长测。已增加仅测试启用的核心错误枚举、导航步骤/保护状态与失败摘要，保留原完整Java异常栈、断言、操作次数和超时。已定位并修正本轮强制刷新保存竞争；原首次后退超时与正向 TLS reset 仍不能宣称根因已解决。

本批不改变正式客户端的登录/恢复格式或保活策略，不合并 main、不公开发布或部署。上一可安装候选仍是下方 `2026.11.07-1001`，尚未包含本轮保存竞争修正；诊断构建不替代完整候选验收，也不自动提供为日常安装包。

首轮取证：`42350115528` / `145fc5632ca66e8c71bc6079ce4e3bed041c9be8` 构建通过（17份XML、85项Android单元、失败/错误/跳过均0；Go诊断及16项夹具测试通过）。TLS job `128338288799` / artifact `13565842539` 的正向原场景通过，初次probe在567ms完成；错误hostname/CA负例的reset不等于原正向首错，原根因仍未知。核心日志确有来源枚举，但Xray会在outbound日志前把cause格式化进私有message；下一轮改用其公开typed错误反馈接口，不打印/格式化原文，仍不能恢复retry私有message中的逐次dial原因。

导航 job `128338288841` / artifact `13566185043` 失败于 `force-refresh`，原后退/前进步骤本轮已通过。新增状态实证：guard已到recovery-start，serial15/epoch6/URL不变，captureTicket从6推进到8后recovery-cancelled，无action-approved或新page-start，原草稿/存储保留。源码对应JS采集后提前清inflight、IO保存未结束时4秒poll可启动新capture，旧导航保存因ticket失效取消。修正覆盖采集至保存确认的inflight生命周期，同serial/ticket才能释放与更新，完成回调仅一次，保留原票据/归属/成功门及2秒、7秒、30秒预算。此证据只确证本次强制刷新竞争，不能先认定原后退超时同根。

修正回归：`42351983115` / `85b1737e5cac3f5eec2b43dde3c06dddcb0a12e1` 的构建、原完整synthetic35与TLS场景首轮通过；实际17份XML、85项Android单元、失败/错误/跳过均0，Go诊断测试（含原3项及新增2项）、16项夹具测试、lint/包检查通过。导航 job `128343890758` / artifact `13565997733` 的原instrument为OK(1 test)，强制刷新实际263437ms recovery-start→263450ms action-approved，captureTicket仅6→7，随后263598ms page-start、epoch6→7；原新document、切标签不重载、前后退、普通/强制刷新、草稿/Cookie/storage及旧账号密文资产保留断言均通过。本轮保存仅13ms，未声称受控撞上poll竞争或原后退超时已同根复现。

TLS 首轮 job `128343890742` / artifact `13566935493` 的正向启动为582/518/549/583/538ms，未复现reset。公开 `session.TrackedConnectionError` 已在两个预期凭证负例中实际产生 `outbound-feedback` / `retry-exhausted`，各17条、无丢弃；固定类型链不调用Error/String，不输出地址、配置、凭据或正文。每次启动独立tracker，旧context晚反馈不能混入新实例；泛logger仅辅助时序，跨代次因果以typed tracker为准。Xray将逐次dial原因放私有message，因此仅能报告公开retry耗尽，不能虚构证书或网络终因。

原2053ms首错已越过core启动及本地连接检查，进入Java TLS握手时核心和代次仍有效；但这不足以排除本地HTTP/CONNECT、outer TLS/VLESS、host路由与origin接收调度。没有APP accepted日志只表示未观察到handler标记；host RST为空也不能排除guest loopback、FIN或TLS alert。不能用新负例链回填旧正向故障。

第二轮仅对TLS原任务追加一次全新模拟器采样，不重建、不重复导航/后台长测：[attempt2 job `128345290927`](https://github.com/ForceMind/ChatGPT/actions/runs/42351983115/job/128345290927) / artifact `13566402583` 通过，同源码85b1737。正向probe为580/516/565/634/577ms，各8条、无丢弃/正向reset；两个凭证负例各17条，实际typed反馈为4层retry-exhausted，正确拒绝后显式恢复。proof核对noDirect/noReplay/draftCookieIdentityPreserved，sendCount=0，未截图或访问真实服务。另直接复核了导航原proof与recovery-start→action-approved→page-start序列、旧数据保留汇总；新增诊断总计三个独立任务样本通过，仍不等于原TLS根因解决。

当前未关闭项：原首次后退超时是否属于同一保存竞争，及正向TLS reset根因。现有原失败缺少typed因果，新的通过样本不能补回旧失败链；需要带新取证的同场景正向失败样本才能继续因果定位。本轮停止机械采样，不预先增加平台、后台长测或新的网络行为，也不将当前诊断包提升为完整可安装候选。主线仍处于稳定性收口，尚未进入公开版迁入。

### 后续定向验证（2026-11-07，已获明确同意）

上一轮属于实质进展，未将未复现作为完成。继续只读核对发现：原Activity恢复02:14:35.087，原第二次菜单关闭02:14:39.083，紧邻首次4秒poll；这支持优先检查第一次后退的排序，但缺少原phase/URL/capture计数，不能作同根证明。固定Xray VLESS的五次快速dial失败带0/200/400/600/800ms退避，合计2000ms；原2053ms与此分支相容，仍非其实际耗尽证明。HTTP CONNECT在派发outbound前已回复200，因此Java进入TLS握手不代表outer TLS/VLESS已建立。

用户已明确同意新增两项Android15合成时序验证：后发poll与真实保存重叠的单变量旧early-release对照/修正版，以及先发poll时点击原后退的保护拒绝。测试钩子仅ENVIRONMENT_TEST+owned fixture+显式case启用；不伪造票据/保存结果，不替换存储，真实IO门控在原2秒内，保留2/7/30秒及原4000ms轮询。旧early-release对照不是原历史APK复现，原新document断言真实失败并单列expected failure，不计入fixed pass。原完整synthetic前置及导航先照常执行，新增case分别保留日志/JSON和原数据复核，实际结果见下表。

TLS同一原场景的必要取证补充：host只记录owned端口SYN/FIN/RST文本元数据，不保存payload；network-config前和首错后只读记录loopback/Owned A/B allow计数和REJECT聚合，后者不是UID专属，不能直接归因App。不加预检连接/应用重试/等待。另在Actions以固定retry函数和立即失败闭包验证真实五次退避时序及typed耗尽形状，不能把该代码级对照当原网络故障复现。所有构建与运行仍仅Actions，不截图/真实服务/个人设备，不合并main、发布或部署。

定向验证构建 `42355022185` / `263db3713870538808903a91aa75e3cd037628ac` 已通过：实际17份JUnit XML共85项，失败/错误/跳过均0；Go、16项夹具、lint和包检查通过。独立verbose证据 `13568125301` / `stability-code-evidence/retry-timing.txt` 实际执行固定retry五次立即失败闭包，测得2002ms，返回typed retry-exhausted；这是注入代码级对照，不是实际节点复现。

导航 [job `128353716253`](https://github.com/ForceMind/ChatGPT/actions/runs/42355022185/job/128353716253) / artifact `13568230600` 全部完成，原main为OK(1 test)、全部原flags保持。三个case的raw instrument、navigation/timing JSON和最终旧数据复核分别保存；严格拒绝运行器异常，已直接复核原始baseline失败栈及汇总。

| 受控case | 实际票据与行为 | 实际门控 | 判定 |
| --- | --- | --- | --- |
| baseline-late-poll | 只恢复旧提前释放一行，真实poll使7→8；真实保存changed与解密读回均成功，取消1/批准0/新页面0；原新document断言真实超时，唯一stack为对应AssertionError，Tests run1/Failures1，无运行器异常 | 9ms | expected-original-assertion-failure，未改记为修正版通过 |
| fixed-late-poll | 相同真实poll期间仍为7；310201ms保存完成，310203ms批准，310222ms产生新页面；真实changed/读回及全部原positive断言通过 | 34ms | fixed-original-assertions-pass |
| fixed-early-poll | 原后退在318214ms被保护拒绝，318398ms才保存完成；capture3/serial5不变，无批准/新页面，原next document、草稿/模型及Cookie/storage保持；真实changed/读回成功 | 374ms | safe-refusal-pass |

三组均在1250ms门控及原2秒预算以内，未放宽7秒或30秒。最终另跑原旧子集raw OK(1 test)：1旧账号/2标签/2密文record及payload/asset/index/network/preferences全部哈希和record与原expect、case前精确一致，无重新seed/clear或helper停止页面。因果验证确证修正覆盖后发poll抢票据；先发poll的安全拒绝是独立路径，不据此反推缺日志的历史first-back必然是哪一种排序。

TLS [job `128353716199`](https://github.com/ForceMind/ChatGPT/actions/runs/42355022185/job/128353716199) / artifact `13568350394` 的5次正向probe为588/509/540/519/544ms，未复现正向reset；两个凭证负例正确拒绝，实际typed retry-exhausted后显式恢复。原noDirect/noReplay/draftCookieIdentityPreserved与sendCount0保持。before计数快照IPv4/6均exit0、预期ruleCount各1；guestClockSample与前后host时钟完整，首个节点SYN及origin SYN可在约30ms采样界内对应Java绝对elapsedRealtime阶段。capture正常退出，2377条文本记录、kernel dropped0，无payload或pcap文件。成功关闭同样存在FIN→RST，因此RST本身不是startup失败证据。成功case没有after-first-error快照，首错快照分支仍未在新版本发生的错误中实证。

本批已完成获批的确定性导航对照及TLS被动取证验证，源码和报告仅同步dev；未作完整Android9/15候选发布回归或替换可安装候选。正向TLS原首错仍缺带新取证的失败样本，不能仅凭2002ms代码级相容、成功样本或负例链认定根因。历史first-back归因同样保留未知，主目标保持未完成，不继续无新证据的机械重跑，不合并main、公开发布或部署。

### 剩余根因审计与阻塞条件（2026-11-07）

上一轮已完成确定性对照，属于实质进展。本轮回读确认 `42355022185`、`42351983115`、`42350115528` 均已终止，无遗漏的活跃验证任务；原首错artifact `13556391205`、`13556366868` 均仍未过期，source为 `d5f187e84cdcc3568e51fe581041dca7c3368171`，未删除或改写。当前客户端/工作流源码仍与已验证的 `263db3713870538808903a91aa75e3cd037628ac` 一致。

| 目标验收项 | 当前证据 | 结论 |
| --- | --- | --- |
| 导航保存竞争的最小修正 | 真实IO/轮询单变量旧行对照失败、修正版及先发轮询保护通过 | 本次竞争分支已确证；缺原phase/ticket的历史首次后退不能严格归同根 |
| 正向TLS reset实际原因及对应修正 | 原2053ms缺typed/counter/连接生命周期；新版本正向样本通过，负例及注入单元另列 | 未完成，不得用相容时序或负例链补写旧原因 |
| 首错与完整必要运行器/状态证据 | 原artifact及各轮raw instrument、计数、跨钟、TCP flags、失败分类保留 | 已保存；成功case没有伪造after-first-error数据 |
| 原断言、预算和真实保存 | 原2/7/30秒及4秒轮询；旧行对照真实失败；真实changed/解密读回 | 保持，未删除功能或放宽关键断言 |
| 登录/恢复及标签保活 | 原完整synthetic35、旧子集哈希与record、热切换原flags；源码未改数据格式或保活策略 | 本批API35证据通过，不扩大成完整多平台发布验收 |
| Actions-only、无截图/真实服务/个人设备，dev-only | CI guards、禁用测试截图、各proof flags及Git记录 | 保持；未合并main、发布或部署 |

为确认是否仍有必要的安全行动，只读审计了固定Xray `0afec66085a8`：原system dialer为私有对象且无getter；公开替换接口不返回原对象，无法直接委托；保存DialSystem函数仍动态读全局，包装后会递归。Socket controller只在DialContext前执行，不能被动取得之后connect或TLS的typed结果；已有context反馈位于outbound整体返回后，拿不到内部每次dial错误。新建默认dialer、替换transport、私有反射或格式化原错误会改变本次硬约束，因此未实施。

依据：[SystemDialer对象与替换接口](https://github.com/XTLS/Xray-core/blob/0afec66085a8/transport/internet/system_dialer.go#L200)、[socket controller调用时机](https://github.com/XTLS/Xray-core/blob/0afec66085a8/transport/internet/system_dialer.go#L149)、[outbound聚合反馈](https://github.com/XTLS/Xray-core/blob/0afec66085a8/app/proxyman/outbound/handler.go#L238)。

同一阻塞条件已连续三个目标轮次存在：缺少带现有取证的正向失败因果数据。当前没有可安全补回旧信息的实现动作，也无活跃任务可等待；继续收集成功样本不会区分仍相容的多个原因。目标标为blocked而非complete，完整目标保持。恢复条件是同一合成场景产生带现有typed/counter/clock/flags的正向失败，或取得原首错更完整的因果日志；历史后退归因同样需要相应原状态证据。未安排后台采样、定时自动化或额外发布。

## 历史开发：日期版本、本地导出与资产任务

该阶段交付 `2026.11.07-1001` 固定签名候选：Gecko 本地导出与资产/Skill 自动任务通过 Android9/15合成验收。原下载、失败及边界见下方历史交付；后续1905候选同时纳入保存竞争修正，正式版迁入与真实服务、手机验收仍未完成。

**当前确认范围（2026-11-07）**：用户已明确确认按应用层单次发送继续。第五项新增“完整 HTTP 响应、缺少业务确认”的合成故障，仍要求服务器真实记录一次、页面一次发送点击、完整文件/指令、持久化发送栅栏、uncertain 后重开不自动再执行和零 App 崩溃。原“已提交但完全无 HTTP 响应就 TCP 断流”模式与两次 POST 失败证据保留为运输层限制，不更名为通过，不承诺第三方恰好一次处理。其他原定回归与数据/保活边界保持。

新夹具模式 `send-business-ack-missing` 已通过两端 Actions：真实 commit 后写完整 HTTP200/JSON/Content-Length，无 `accepted` 业务字段。服务端保存 write/flush 的实际字节、长度与SHA，网页通过真实 Response.arrayBuffer/JSON解析记录收到的状态、header、字节与缺ACK；driver和Gate逐项互核。原 `send-ack-drop` TCP shutdown/close 分支保留。候选 proof/summary 明确 `onceScope=application-task`、`transportLevelReplayGuarantee=false`，单独引用原失败run/artifact且 `includedInCurrentPass=false`。本次范围确认后未改 App 代码或重试设置，内部序号仍3000033；前批已实现的桥接与恢复修正一并纳入本候选。

用户已要求继续 Android 主线，alpha.22 原包 UI 单独回迁暂缓。当前顺序为：先补齐 Gecko 的 blob/data 本地文件导出，再接入新环境“资产 → GPT / Skill”完整流程；两步沿用原固定 Beta 包名、证书、账号空间、加密恢复与热标签，不合并 main、不公开发布、不部署官网或更新个人设备。

用户可见版本名已采用上海时区 `YYYY.MM.DD-HHmm`，APK 文件名采用同一版本。日期版首批内部 versionCode 为3000032，本批递增至3000033，继续由整数保证覆盖升级。一次完整构建只生成一份时间版本元数据，人用包和所有 CI 变体共用；复用原 APK 时保留其原日期，旧交付记录不改名。本轮仅接入 Android，其他平台在各自后续版本接入，当前公开下载与更新清单保持原状。

第一步交付可安装的日期版候选及本地导出证据：HTTP(S) 仍严格核对原线路票据；blob/data 使用单列的本地来源、账号及文档授权，消费浏览器原字节流，不冒充网络线路、不为导出放开任意本地页面导航。成功文件写入原账号资产库，失效、取消、关闭及迟到回调不能误归档或串号。

第二步已在同一基础完成资产/Skill 的附件与完整指令、应用层一次发送、清晰进度以及取消/失败恢复，复用原工作台流程。用户已明确批准新浏览器验收范围：Actions Android 9/15 的 Blob 图片/HTML、data 文本、账号切换/关闭/取消、资产/Skill 一次发送及旧登录/恢复/热标签/同签升级。所有构建与测试仍在 Actions，只用合成数据，不截图、不访问真实 ChatGPT。

## 实现与验证记录（按发生顺序保留）

以下记录当时的状态，最终交付与当前限制以“最新交付”一节为准。

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

`42249616228` / `d42de4c8b534876f1c7a71929b81df3265845cd8` Android9 的真实 focused AX 已推动首正常、上传中取消、上传错误、源热标签与明确新任务恢复；恢复任务也已实际第二次发送891字整文并ACK。之后 focused 窗口是 TYPE_SYSTEM，WM明确为 Application Error；这是App崩溃，取证没有将它伪装成业务页。slot6350在16:21:02.998的Java主线程栈为 `EventDispatcher.shutdownNative→WebExtension.Port.disconnected→disconnectFromExtension` 的 NullHandle；更早slot3743的libxul SIGSEGV只有地址，未确定同根。Android15仍在业务前 probe reset，尚未检验focused/helper或五case。

固定158源码与6350的 `/c/` LocationChange→Recovery Connect→崩溃时序确认存在双主动关闭竞争：Native invalidate 调 Port.disconnect，JS URL变化也 disconnect；已排入main的远端回调随后再shutdown，SDK并非幂等。最小修正只改恢复桥关闭方法：Native先同步撤销旧epoch/token/port/能力，旧Port只通知JS；JS每连接先清引用并标关闭，再唯一调用disconnect，URL/pagehide/revoke共用单次关闭。身份门、原local流/EOF、保活和重载不变，不全局捕获或抑制异常；更早原生SIGSEGV仍独立未知。

同五case成功结束前新增必要崩溃回归门：只读原crash buffer，按完整包名/子进程名识别owned Java/native fatal，原字节/命令/exit/SHA留证，Gate重读重算。owned fatal或无唯一归属的native fatal均拒绝交付；没有清log、补UI动作或降低文件/全文/一次发送/fence/hot断言。关闭修正与完整五case仍待下一轮Actions。

`42254916165` / `2bc002f29f7424e0413c335721d61b09a320c4b7` 的 build、两端本地导出、六组网络及两端human/synthetic完整升级恢复均通过。两端任务也已走到 submitted/cancelled/failed/submitted/uncertain，真实双附件/891字完整指令与前四项检查通过；第五项 server 实际各收到同一文档两次 POST（间隔约2063ms），DOM sendClicks仍1、原生输入仍1。总sendCount4/rejected1，原断言正确失败；uncertain重开及末尾owned-crash门尚未执行，未生成完整候选。

固定158 `nsHttpTransaction.cpp:1560–1640` 允许某些连接复用且完全无响应的事务重启，`2007–2051` 会回卷可seek请求体再 Restart。夹具当前已真实commit后直接TCP shutdown、无任何HTTP头；route/core只转发HTTPS密文，没有生成第二份有效JSON请求的逻辑，证据指向浏览器运输层重放，但未单独记录本次具体restart reason。App的一次Native点击/持久fence无法单方面证明第三方服务只收到一次，真实ChatGPT服务端幂等亦未验证；没有偷偷启用影响全runtime/其它热标签的max-attempts设置。

**范围确认过程（2026-11-07，现已确认）**：当时向用户说明两种边界：验证应用层“真实commit、完整HTTP framing但无业务ACK”，仍严格一次发送、结果待确认、不自动再执行并披露运输层限制；或继续研究可靠单事务禁止底层重传方案并暂不交付。等待期间没有替换故障模式、删除原失败产物、放宽次数或把现第五项改成通过。用户随后明确回复“确认”，现按页面顶部列明范围执行；原失败仍保留。

原范围只读续查（2026-11-07）：未把自动Goal续跑当作范围变更批准，继续核对固定158的窄作用域方案。`nsHttpChannel.cpp` 在 BeginConnect 先按原请求头调整 keep-alive 能力，随后 PrepareToConnect 才触发普通 webRequest 的修改阶段；`HttpBaseChannel::SetRequestHeaderInternal` 只改请求头，不回写连接能力。因此没有把扩展晚加 `Connection: close` 当作可证的单请求禁重传修复。公开 `proxy.ProxyInfo.connectionIsolationKey` 只承诺代理连接隔离，direct 模式忽略其它字段，不能据此宣称覆盖系统直连或所有事务重启。固定158的流式请求体会拒绝已消费后的回卷，但要求HTTP2/3，且强改网页fetch/请求体并非当前已核实的透明兼容路径。未写入全runtime max-attempts、未修改网页fetch或夹具，没有新增测试场景或候选发布。

依据：[固定158 channel顺序](https://github.com/mozilla-firefox/firefox/blob/FIREFOX_158_0_RELEASE/netwerk/protocol/http/nsHttpChannel.cpp)、[请求头修改实现](https://github.com/mozilla-firefox/firefox/blob/FIREFOX_158_0_RELEASE/netwerk/protocol/http/HttpBaseChannel.cpp)、[事务重启与流式请求体](https://github.com/mozilla-firefox/firefox/blob/FIREFOX_158_0_RELEASE/netwerk/protocol/http/nsHttpTransaction.cpp)、[Mozilla代理隔离API](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/proxy/ProxyInfo)。结论为尚未找到可靠且不改变其它热标签行为的公开单事务禁重传路径，不是对所有可能实现作不可能性证明；用户现已确认应用层范围，原运输层失败不计入通过项。

## 历史交付：2026.11.07-1001 文件与任务候选

- [下载候选：APK、SHA-256、安装说明与完整验收证据](https://github.com/ForceMind/ChatGPT/actions/runs/42327120729/artifacts/13557011670)
- [Actions 42327120729：最终全部通过](https://github.com/ForceMind/ChatGPT/actions/runs/42327120729)；源码 `d5f187e84cdcc3568e51fe581041dca7c3368171`。
- App 与文件名版本均为 `2026.11.07-1001`，上海时区，内部序号 `3000033`。APK `ChatGPT-Studio-2026.11.07-1001-android-arm64.apk`，241,891,223 字节。
- APK SHA-256：`b3bae319466c1da37e295949e1db137723b240c7fb1bc8a0232c0f275e612794`。
- 包名 `com.forcemind.chatgpt.profiles.networkbeta`；固定证书 SHA-256 `bd7b9c924795ec21d27c73f280b5fc71b83ffea950e36e05967b77f092056ac0`。人用 arm64 包非 debug、非 testOnly、16 KiB 对齐，不含测试 CA、合成节点或 CI Probe。已有固定 Beta 可覆盖安装，不卸载、不清数据；正式版和临时测试版保持独立。

本轮可在资产库核对文件、目标账号及 Skill 后创建处理任务。真实系统选文件、原字节上传、完整指令写入、发送、原对话回看均有明确进度；跨账号确认、停止、失败后的明确新任务及结果待确认状态保留。原始文件不变，已有登录空间、加密恢复格式和后台保活策略不变。

| 合成验收 | Android 9 / API28 | Android 15 / API35 |
| --- | --- | --- |
| 正常发送、上传中取消、上传失败、明确新任务恢复、缺少业务确认 | 五项通过 | 五项通过 |
| 真实双附件与完整 891 字指令、原生审核、主进程持久进度 | 通过 | 通过 |
| 发送次数依次 1/0/0/1/1、发送标记、临时文件清理 | 通过 | 通过 |
| 完整 HTTP200 但无 accepted；uncertain 重开不再执行 | 通过 | 通过 |
| 原 A0/A1/B0 文档、草稿和加载次数保持 | 通过 | 通过 |
| 原始 crash buffer：owned fatal / 未归属 native fatal | 0 / 0 | 0 / 0 |
| Blob HTML/PNG、data 文本在线/离线保存、归属与取消 | 通过 | 通过 |
| RAW/TLS/REALITY 网页、上传、下载同路由 | 三组通过 | 三组通过 |
| 日期版32→33同签升级、旧登录/密文/资产、热标签与冷恢复 | human / synthetic 通过 | human / synthetic 通过 |

构建原始报告为17份 JUnit XML、85项测试，失败/错误/跳过均0；16项网络夹具测试、lint和打包检查通过。所有构建与运行在 Actions，未本机构建、截图、访问真实 ChatGPT、使用真实节点或更新个人设备。两端任务汇总与 crash 原始字节随包附带；API28 crash buffer 为4421字节，API35为空，独立门禁重新计算哈希及归属后均通过。

本次首轮另有两项失败，未隐去：Android15 TLS 在网页前 `probe-owned-target` 的2053ms处出现 SocketException/reset，当时核心仍运行、原代次未变，宿主捕获未见RST；[首错 job](https://github.com/ForceMind/ChatGPT/actions/runs/42327120729/job/128266894418)、产物 `13556391205` 保留。旧 WebView 导航在 next/后退/前进组合的 `loaded("one")` 超时，失败树仍显示后续页，尚未进入普通刷新；[首错 job](https://github.com/ForceMind/ChatGPT/actions/runs/42327120729/job/128266894774)、产物 `13556366868` 保留。两项分别用同一源码、原断言和预算定点重跑通过，原因尚未确定，不作为已修复问题或稳定性保证；没有重复已通过的后台长测。

在线范围读取最终ZIP：89项、仅一个人用APK、压缩包111,808,636字节；版本、文件名、源码、证书收据与任务摘要一致，未下载大型APK到本机。下载需GitHub登录，产物保留14天。该候选仅推送dev，未合并main或公开发布，也未用于官网正式下载与更新清单。真实ChatGPT附件/一次业务处理、通行密钥、真实节点与OEM手机仍待验收；原TCP无响应断流的浏览器重传限制仍见上文，不纳入本次通过。

2026-11-07 已将本轮候选进度同步到现有官网里程碑，未加入正式下载或自动更新。Pages 生产部署 `d686deb8-6c64-401c-94d4-175f15052050` 已在部署专属地址、主域和自定义域名通过9次HTTP回读；新里程碑与链接匹配，更新清单和脚本字节保持一致。未启动浏览器或截图。[部署与核对记录](releases/2026-11-07-site-repository-sync.json)。

## 历史交付：2026.11.06-2024 本地导出候选

**已知限制（2026-11-07）**：已对照该包源码，恢复桥同样包含本轮发现的 Native/JS 双主动 Port 关闭逻辑。它通过了当时的文件与恢复矩阵，但没有覆盖此次竞争崩溃；当前不建议将它作为日常主版本。最新单关闭方修复与完整任务候选仍在 Actions 验收，未将历史包描述为已修复。

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
