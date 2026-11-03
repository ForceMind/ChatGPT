# Android 应用内网络首版

状态：Android 独立网络预览 0.1.0 已交付；核心停止状态和在途文件故障修复已在 GitHub Actions 合成验证通过（2026-11-03）。正式接入方案已形成，尚未实施。

## 当前批次：独立多 Profile 联合验收（进行中）

用户已确认按 [正式接入建议](android-network-integration-plan.md) 推进下一批联合验收；只修改独立包，不实施正式客户端适配。目标为三个真实隔离 Profile、四页及所属弹窗，核对账号 Cookie / localStorage、两类原生文件出口与网页同路由、账号 epoch、Activity 重建、系统 HTTP 代理 / PAC 及明确停用回退。正式 App、登录数据、生产签名、main、官网、公开 Release、依赖版本和工作流不变。

当前第一迭代先构建真实会话支架，并在原路由测试前执行独立能力门槛：动态读取实际 provider / API、`MULTI_PROFILE`、`PROXY_OVERRIDE` 和系统 ProxyInfo，报告明确标记 `fullJointAcceptance=false`。不支持即保存证据并失败，不用默认共享 Profile 代替，也不将原四项回归通过当作联合验收通过。

系统代理 / PAC 的后续真实验收只允许全新 CI 模拟器临时 DeviceOwner 和公开 DPM API；测试后恢复并撤销，不用隐 API / 安全豁免或仅写 settings 自证。管理员 Receiver、Provider 与 testOnly 仅在 ci flavor；预览 APK 的编译产物将单独核对隔离。Profile 清理先立即使该账号 epoch / 资源失效；物理删除按实际 API 结果报告，若已加载而无法删除，则在新的测试进程、加载 Profile 前验证 owned tombstone 清理，不冒称本进程已抹除数据。完整联合功能、系统路由与清理仍未运行验收。

能力门槛首轮 `4a58b3c` / Actions `41649274933` 的核心、合成服务和包检查通过；设备在编译 APK 隔离检查处停止，尚未查询多 Profile 能力。预览包的管理员 / Provider / 测试 CA 检查为不存在；CI `testOnly` 被初始解析器判为 false，但原始 aapt 字段未保留，不能据此断言是构建标记丢失或格式误读。检查脚本现保留两包原 manifest 文本和原始布尔行，严格识别明确 true 与 typed boolean true 编码，false / 缺失 / 未知仍失败；没有跳过 CI 必须为 testOnly、preview 必须隔离的断言，等待后续真实字段核对。

次轮 `01c61bf` / Actions `41652136446` 的实际 aapt 字段为 `android:testOnly(0x01010272)=true`，按明确布尔语义核对后，preview / ci 隔离通过。设备随后在 Provider 初始化处报 `SecurityException: Provider must be exported`，能力测试还未查询 MFP。按 Android DocumentsProvider 合约，将 CI Provider 声明为 exported 并用 `MANAGE_DOCUMENTS` 签名权限保护，同时补齐必要投影列；该 Provider 与权限不进入 preview，隔离检查同步要求权限确实存在。未通过取消权限检查或共享 Profile 绕过初始化，后续仍须真实运行能力门槛。

## 已验证：在途文件故障收口（2026-11-03）

用户已明确批准：先在独立包验证上传 / 下载进行中核心停止、取消、关闭和重连，必要时做最小修复；通过后形成正式接入方案，但不实施正式接入。构建和浏览器 / 文件交互仅在 Actions 合成环境，正式 App、登录 / 恢复数据、生产签名、main、官网和公开版本保持原状。

- 合成下载先实际发出 4096 字节，剩余响应由可查询 gate 挂起；确认本地文件已读入部分内容、传输计数为 1 后，再停止核心或取消 / 关闭。残片不得报成功，结束后文件集合回到开始前基线、计数归零，每个操作完成回调至多一次。
- 原生 / 网页 File 上传先由合成原站确认收到完整请求并处理一次，再挂起 ACK。停止核心后，不把客户端错误解释为“肯定未发送”，不自动重发；显式恢复用新操作 ID，原操作最终仍只能出现一次。
- 取消后同一下载器可继续；关闭后拒绝新请求，新实例可接替，旧任务的回调不能改变新任务结果。取消 / 关闭的实际终结界限为 10 秒，小于现有 20 秒读超时，不能依赖原超时冒充主动终止。
- 从代码看，原 `NativeDownloads.close()` 缺少关闭标记，也未追踪同步请求；本批补 terminal guard、在途请求连接释放，以及已尝试发送请求体却未得到完整成功回应时的 `RequestFailure.outcomeUncertain`。取消仍只取消下载，不扩大为隐式取消上传；未调整原连接或读超时。
- 原三项 instrumentation 及既有协议、安全负例、资源回归保留；新增第四项覆盖十二个唯一业务操作。控制请求仅存在于 androidTest，自有直连控制通道与业务路由分开，不进入正式包、不记为业务直连回退。

首轮源码 `cc29ede` 的 Actions `41629262698` 中，合成服务 13 项单元、核心与独立包检查通过，但六组设备未通过新增网页上传中断断言。已读取的原始数据表明：停止核心后原生下载 / 上传约 19.9 秒才结束（原读超时），网页上传在 25 秒界限内仍 pending，原站已完整接收一次、ACK 尚未释放。固定核心的 `Close` 关闭监听但不取消传给已接受连接的根上下文，原包装层使用不可取消的 `core.New`。本批改为自行持有可取消上下文，`Stop` 先取消再关闭；增加 HTTP / HTTPS 在途上传 / 下载的 Go 回归，保持原站 gate 未释放、客户端常规超时 30 秒，要求停止后 2 秒内终结并排除自然超时。没有延长或跳过原 Android 25 秒断言。该轮 Android 15 / REALITY 另有首次 DIRECT 首页前置失败，当时未保留明确错误码，未将它归因于后续安全负例或流取消问题。

第二轮源码 `627d9cb` / Actions `41633119556` 的核心和独立包检查、四个真实 Go 在途子场景、五组完整设备回归通过；Android 15 / TLS 仅首次 DIRECT 首页断言失败，其新增在途用例通过。保留下来的原始错误码为 `net::ERR_ADDRESS_UNREACHABLE`，目标是合成 `http://10.0.2.2:18080/page`。据此在 androidTest 增加 Activity 启动前的 owned-origin 可达检查：只以显式直连 GET `/echo`、HTTP 200 / route=direct / 有界 JSON 验证前置条件，总界限 15 秒，失败明确报 setup 错误；不访问外网、不附 Cookie、不发业务 POST、不增加页面加载或改动原业务断言 / 超时。该准备不是生产流量回退或自动重发。

最终源码 `254685aa9c0cf0b41d2fd22a2a75a9a0e5e81fe4` 的 [Actions 41636997369](https://github.com/ForceMind/ChatGPT/actions/runs/41636997369) 十个必要网络作业一次通过。Android 9 / 15 的 RAW / TLS / REALITY 六组完成原有路由、安全负例和资源回归；RAW 另含 HTTP / HTTPS 两种原站，共八个路由套件各四项 instrumentation，通过 32 次测试执行。96 个在途业务操作均只到原站一次，另有 16 个关闭后请求被拒绝、未到原站；原生任务终结计数为零、完成回调每操作一次、失败残片回到基线，网页与草稿保留。13 项合成服务、7 项驱动契约、10 项 Java 单元及四个 Go 在途子场景均通过。

本批原生任务的终结观测为：核心停止 1–19 ms、取消下载 1–5 ms、关闭下载器 1–6 ms；这不是 UI 状态提示时间、真实设备承诺或 CPU / 内存收益。合成上传已收到并提交但 ACK 未到客户端时，原生明确返回结果不明，网页请求终结为错误；恢复线路没有自动重发。完整计数、包身份 / SHA-256、失败历史与限制见 [在途故障证据](benchmarks/android-network-transfer-faults.json)；[独立 arm64 候选](https://github.com/ForceMind/ChatGPT/actions/runs/41636997369/artifacts/13231850662) 为实验签名，仅供独立验收。按批准顺序形成的 [正式接入建议](android-network-integration-plan.md) 仍需后续批准，未修改正式代码、登录数据、main、官网、公开 Release 或工作流。

## 已验证：核心状态失效监测（2026-11-03）

本批仅修复独立包的核心意外停止后状态仍显示已连接问题，不接入正式 App、不导入账号或变更现有登录 / 恢复数据，不更新公开版本或官网。候选沿用独立预览 0.1.0，通过源提交与 CI 运行标识区分。

- Go 只读接口核对真实本地 Xray 实例生命周期，不将实例指针非空或检查目标可达当作当前核心仍运行。
- 已连接时由单条约 2 秒的检查链在串行核心线程读取状态，不探测网站、不使用唤醒锁；返回 App 立即复核。休眠时不承诺实时执行，不改网页保活。
- 核心停止后进入失败状态，界面提示手动重连，阻止主页面及弹窗的新网络请求；保留当前网页、草稿、所选线路和代理覆盖，不降级直连、不自动重启或重发。
- 检查结果和后续排程绑定同一线路快照与检查会话；切换、失败和关闭后，旧回调不能覆盖当前状态或续排。
- 在现有 Actions 工作流中保留原路由 / 文件 / 上传 / 安全负例回归，增加不靠请求触发的停止识别、可见状态、显式恢复、生命周期和关闭回调检查。没有修改工作流配置；构建及运行都在 CI。

源码 `5c6e8fc9b9fdd86df3ed715afda74e59e57a2797` 的 [Actions 41604827606](https://github.com/ForceMind/ChatGPT/actions/runs/41604827606) 最终通过。Android 9 / 15、RAW / TLS / REALITY 六组保留原路由与资源回归；RAW 含 HTTP 和 HTTPS 原站，共八组路由套件，每组原两项加核心故障一项均通过。Java 单元 10 项、驱动契约单元 5 项及 Go 生命周期 / 传输检查通过。已核验的合成前台检测延迟为 992–1762 ms，不作为真实设备或休眠状态时限承诺。

每组都确认：外部停止真实核心后，在新业务请求之前自动进入 `FAILED`，界面显示“连接失败 · 请手动重连”；主页面新网络请求被阻止，原生失败请求与网页失败请求均未到达原站。原文档、草稿和提交计数保持；显式重连和直连之后的新请求各一次。真实 Home / 系统返回复用了原 Activity；关闭后至少观察 4.5 秒，核心关闭、代际稳定、旧完成回调为零。未测精确调度调用次数，也未将这批称为 JNI / 进程崩溃自动恢复或新增长后台验收。

首轮 Android 15 / REALITY 在初始直连首页不可达、首次代理目标检查 `IOException` 处失败，健康监测尚未启动，合成原站日志没有访问记录；具体环境原因未确定。其余五组通过后，对该组进行同源码重跑并通过，没有修改代码、超时或断言，保留首轮失败工件。完整结果、独立 APK 身份与摘要见 [核心状态验证记录](benchmarks/android-network-core-health.json)；[独立 arm64 修复候选](https://github.com/ForceMind/ChatGPT/actions/runs/41604827606/artifacts/13217025632) 沿用实验签名，不是正式升级包。本次未访问真实 ChatGPT、用户节点或个人设备，不截图、不修改正式 App / 登录 / 签名、main 或官网。

## 已完成：多标签与故障恢复稳定性

源码 `8b963538e61aaab44110e0a8b13855d787b79857` 的 [Actions 41068430363](https://github.com/ForceMind/ChatGPT/actions/runs/41068430363) 四组全部通过。构建、单元检查、lint及候选隔离检查均在Actions完成。模拟器实际按Home退到桌面，主机从确认非前台开始计时；设备另行等待进入停止态，再计满1800秒。到时带回原任务并核对同一Activity与文档。没有用测试框架的生命周期变更冒充系统后台。

| 系统 / 协议 | 设备停止态后台 | 主机独立后台计时 | 后台采样 | 原站业务请求 |
|---|---:|---:|---:|---:|
| Android9 / TLS | 1800.001 秒 | 1804.02 秒 | 30 次 | 17 项，各一次 |
| Android9 / REALITY | 1800.001 秒 | 1801.23 秒 | 30 次 | 17 项，各一次 |
| Android15 / TLS | 1800.001 秒 | 1805.47 秒 | 30 次 | 17 项，各一次 |
| Android15 / REALITY | 1800.001 秒 | 1804.20 秒 | 30 次 | 17 项，各一次 |

每组保留三个合成页面的草稿、JS内存标记和最初文档，切换A/B线路、故障恢复和返回前台后均未重载。17项原站业务请求包括三个页面和逐阶段网页上传、原生上传与落盘下载，服务端逐项验证仅一次且出口为A/B。线路断网仅拒绝所选两个合成监听端口，HTTPS原站保持正常；断网及核心停止时发起的四个POST均未到达原站，恢复后也未被自动重发。核心监听停止后不自行重启，显式重新连接后恢复上传/下载。

**当时发现的缺口：**四组中，绕过RouteManager停止核心后，状态快照仍为`CONNECTED`，仅代表此前配置成功。实际请求失败且未直连，手动重连可以恢复。这份 2026-10-30 记录不证明自动故障识别；后续的 2026-11-03 核心状态修复与证据见上节，未接入正式客户端，也未增加自动重启或请求重发。

[完整处理后证据](benchmarks/android-network-stability.json) 保存四组结果、主机后台记录、原站请求计数、故障规则/计数、provider、归档SHA-256与前置失败记录。读取并核验的四个证据ZIP合计约310 KiB，全程在内存处理；未下载APK，没有本地构建、运行、截图或留下临时取证目录。[独立arm64候选](https://github.com/ForceMind/ChatGPT/actions/runs/41068430363/artifacts/12983987042) 仍为实验签名，证据和候选保留14天，临时合成私钥材料保留1天。归档摘要不是APK摘要。

范围仍为Android9 / Android15的x86_64模拟器、固定WebView124、自有TLS/REALITY节点。三个页面由测试支架创建，共享独立CI空间，不冒充三个隔离账号；Activity保持停止态且instrumentation进程存活，不模拟系统杀进程、Doze或OEM限制。核心故障注入为绕过RouteManager的核心停止，不模拟JNI崩溃。本批不重测内存/CPU，不访问真实ChatGPT或用户节点，不更改正式App、生产签名、main或现有登录/恢复数据。

## TLS / REALITY 完整链路与资源结果

源码 `df9486a` 的 [Actions 41005311920](https://github.com/ForceMind/ChatGPT/actions/runs/41005311920) 六组全部通过：Android9 / Android15 × raw / TLS / REALITY。Android9实际使用固定AOSP WebView124，Android15使用Google WebView124。每组先验证链路，再采集全部六个资源阶段，每段至少60秒、7个内存样本；记录进程身份和实际协议，App与所属renderer合计，未使用App-only或半截窗口替代。

链路验证包括HTTPS网页、A/B线路、原生/网页上传、multipart文件、下载落盘字节、草稿/文档保留、传输时禁止切换、失败不降级和显式直连。TLS同时拒绝错误主机名和未授权CA；REALITY同时拒绝错误公钥和short ID。raw额外保留原HTTP回归。临时CA只存在CI源集与测试资源中；预览APK通过无测试CA资源、无生成信任类的打包检查。

以下均为平均PSS合计（MiB）/ CPU单核百分比。固定负载仍为30轮20字节原生下载、128 KiB上传及回显、20字节网页下载；这次所有资源场景（含raw）统一使用自有HTTPS原站，不直接对比之前HTTP原站的数值。

| 系统 / 协议 | 代理空闲 PSS / CPU | 固定负载 PSS / CPU | 代理后台 PSS / CPU | 恢复直连 PSS / CPU |
|---|---:|---:|---:|---:|
| Android9 / RAW | 209.0 MiB / 0.57% | 212.0 MiB / 3.97% | 212.6 MiB / 0.43% | 213.5 MiB / 0.47% |
| Android9 / TLS | 213.2 MiB / 0.53% | 216.5 MiB / 4.52% | 216.1 MiB / 0.40% | 217.1 MiB / 0.38% |
| Android9 / REALITY | 212.0 MiB / 0.43% | 215.3 MiB / 3.64% | 215.9 MiB / 0.33% | 216.9 MiB / 0.35% |
| Android15 / RAW | 177.5 MiB / 0.33% | 180.2 MiB / 2.26% | 180.3 MiB / 0.35% | 181.0 MiB / 0.32% |
| Android15 / TLS | 176.7 MiB / 0.48% | 179.9 MiB / 3.27% | 179.0 MiB / 0.43% | 179.0 MiB / 0.37% |
| Android15 / REALITY | 183.7 MiB / 0.60% | 188.0 MiB / 4.09% | 186.2 MiB / 0.48% | 185.7 MiB / 0.48% |

这些是带instrumentation的x86_64模拟器、轻量单页、原生TCP场景，未启用Vision/Mux。各协议运行于不同新模拟器，顺序窗口包含缓存、GC及宿主调度差异，不能将单次差值当作确定增量或通用快慢排名；真实ChatGPT、多标签、真实节点/证书链、HTTP2原站与arm64真机仍未验。

首轮合成原站沿用HTTP1.0默认关闭方式，Android在HTTPS下载后原生POST出现EOF；服务端改为HTTP1.1、明确Content-Length/Connection:close并完整刷新响应，重复HTTPS对照与六组设备验证随后全部通过。这不能据此认定所有HTTP1.0服务或Android客户端都有缺陷。测试未降低TLS校验，也未给结果不明的POST增加自动重发。

[完整处理后记录](benchmarks/android-network-tls-reality-resources.json) 保存全部协议、provider、身份负例、原始计数与样本。原始设备证据保留14天，临时合成私钥材料保留1天，不进入用户安装包。[本轮独立arm64候选](https://github.com/ForceMind/ChatGPT/actions/runs/41005311920/artifacts/12954623848) 仍为实验签名；不替换正式App。main、生产签名和现有登录/恢复数据未改动；本机只读取小型结果，临时下载已清理。

前一批WebView升级验证范围：仅在全新 Actions 模拟器更新 Android 9 的官方 AOSP WebView，核对 APK 固定来源、签名、最低 SDK、实际 provider 与代理能力，强制补齐网页/文件同路由回归；通过后测量直连、代理、固定文件负载、后台及恢复阶段的进程内存/CPU。资源统计包含可明确归属的 renderer，报告系统/版本与观察开销，不把合成模拟器数值等同真实 ChatGPT 或手机占用。不更换用户设备 WebView，不修改正式 App、生产签名、main 或现有数据。

WebView升级批次完成：源码 `c699571f4fd9ba95dbfb27fcf14d9226972c759a` 的 [Actions 40932254243](https://github.com/ForceMind/ChatGPT/actions/runs/40932254243) 全部通过。Android 9 AOSP 模拟器已实际切换为官方 AOSP WebView 124.0.6367.219（最低 API26），完整网页/文件回归通过，不再走原生-only 分支。Android 15 的 Google WebView 124 同样完整通过。资源预检曾遇到 API28 缺少 getconf、API35 root 诊断查询返回255，已改为读取内核 AT_CLKTCK、用常规 shell 身份读取服务；两组的时钟频率实读均为100，所有阶段包含 App 和可明确归属的 renderer。

## WebView 124 资源结果

以下为 x86_64 CI 模拟器中的轻量单页合成测试，使用自有 VLESS raw/TCP、security=none。每段约60秒，预热5秒，7个内存样本；CPU按进程内核计数计算。内存为 App（包含Go JNI与测试框架）和所属WebView renderer的平均PSS合计，单位MiB；CPU的100%代表占满一个核。表内两个数分别为PSS / CPU，不跨系统推断性能差异。

| 场景 | Android 9（PSS / CPU） | Android 15（PSS / CPU） |
|---|---:|---:|
| 直连空闲 | 204.8 MiB / 0.56% | 178.1 MiB / 0.45% |
| 代理空闲 | 208.5 MiB / 0.68% | 178.8 MiB / 0.40% |
| 代理固定负载 | 212.0 MiB / 5.08% | 182.0 MiB / 2.11% |
| 代理后台空闲 | 212.4 MiB / 0.51% | 182.5 MiB / 0.37% |
| 恢复直连 | 213.5 MiB / 0.53% | 182.8 MiB / 0.37% |

两组所有阶段的最高采样PSS分别约214.2 / 183.5 MiB。固定负载为30轮20字节原生落盘下载、128 KiB上传及等量回显、20字节网页下载；不含TLS/REALITY性能评估。空闲CPU在本场景中均低于单核1%。恢复直连时PSS比初始约高8.7 / 4.7 MiB，记录为后续观察项，不能用这次短窗口判定泄漏或永久增量。

原始内核计数、进程归属、时间、provider、工作量与样本保存在 [完整处理后记录](benchmarks/android-network-webview124-resources.json)，未只报App-only或把缺失值补零。阶段为顺序执行，不强制GC，存在缓存、分配保留和instrumentation/采样开销；Android15未启动App的系统总CPU窗口高于后续窗口，未用它相减来估算App占用。真实ChatGPT、多标签、真机、长期后台和其他传输成本仍未测。本机仅取回小型报告，临时下载已清理，没有本地构建或运行客户端。

[Android9原始证据](https://github.com/ForceMind/ChatGPT/actions/runs/40932254243/artifacts/12923128284) / [Android15原始证据](https://github.com/ForceMind/ChatGPT/actions/runs/40932254243/artifacts/12924825037) / [本轮独立arm64候选](https://github.com/ForceMind/ChatGPT/actions/runs/40932254243/artifacts/12922403732)，Actions产物保留14天。正式App、main、生产签名及现有登录/恢复数据未修改。

## 第一批可操作交付

先在独立包中提供「直连 / 使用线路」与线路列表，支持手动粘贴 VLESS 地址、命名、编辑、删除和选择。沿用白灰黑与原生弹层，无地址栏、无常驻网络工具栏。连接状态使用「未连接、正在连接、已连接、连接失败」；详细错误按需展开，不把开发日志放入普通菜单。

线路由用户提供。WARP/其他免费通道另行核实，不把它列为已接入或承诺免费节点。完整系统VPN、订阅抓取、自动测速、按账号不同出口不纳入首批。

## 路由契约

- 首版以整个独立App为单位选择线路，使用自己的浏览器和凭据空间，不导入正式账号。代理核心监听127.0.0.1，将WebView和原生文件请求接入同一路由，不改系统代理。正式接入时仍须保留原有账号隔离。
- AndroidX ProxyController作用于App中的WebView，必须运行时检查PROXY_OVERRIDE，并等应用/清除回调后再开始新页面请求。当前正式Android使用webkit1.17.1，尚无网络覆盖实现。
- 网页上传使用网页所在路由；原生AssetDownloads/DownloadHelper所发请求需显式使用同一线路配置，不能只改WebView。登录弹窗与其所属账号同线路。
- 线路变更先保存本机草稿与工作区。已有连接并不保证立即切路，不宣称无缝迁移WebSocket；需要重连时由用户明确选择，既有任务保持原路由或显式取消。结果不明的消息、上传不得自动重试。
- 直连失败可提示选择线路；启用代理后的连接错误不静默改成直连。连通检查只说明检查目标和结果，不能据此保证ChatGPT所有业务可用。

## 已接入的独立核心与依赖

独立模块位于 `android/network-poc`，使用项目自行维护的 Go 启停接口，直接依赖 Xray-core `v1.260327.1-0.20261029083850-0afec66085a8`。固定 Go 1.27.3、x/mobile `v0.0.0-20261011034528-197770444867`、NDK 28.2.13676358；不追踪上游 main。生成 arm64/x86_64 AAR，使用 16 KB 页对齐；Android 构建需要 `-checklinkname=0` 兼容上游 anet 对 Go 内部符号的引用。

Xray-core 使用 MPL-2.0，x/mobile 使用 BSD-3-Clause，AndroidX 使用 Apache-2.0。CI 附带 Go 模块版本、依赖许可及安装包摘要；本项目非商业许可只用于自身代码，不能覆盖第三方文件。未复制代理核心源码到本仓库，Go 依赖只在 Actions 下载。

已实现应用内 loopback HTTP 入口，供 WebView 和 Java URLConnection 显式使用；无直连备用 outbound。TCP、WebSocket、TLS、REALITY 及 HTTPS CONNECT 在自有节点矩阵通过。Android 设备侧验证独立记录，不能把主机 Go 测试等同于 Android 或真实 ChatGPT 验收。IPv6 真机、真实服务与手机厂商差异仍未验证。

合成节点的 freedom 出口在固定核心版本中默认阻止回环目标，首轮四种传输因此超时。CI 服务端仅精确放行自有回显服务的 IP/端口，保持应用配置和失败断言不变；未开放任意私网出口。

## 配置、凭据与错误处理

VLESS导入需校验协议、UUID、主机/IPv6、端口及受支持参数。明确拒绝未知或未支持的组合，保留错误原因，不猜测参数。节点凭据仅本机加密；列表只展示用户名称和脱敏端点，不记录完整链接、UUID、密钥或对话内容。导出/分享凭据须用户主动操作。

核心只绑定loopback，端口和生命周期归App持有；启动失败、配置无效或端口冲突必须回收进程/监听，连接状态不能提前显示成功。核心异常退出后保留配置，任务失败可见，不形成无限自动重启。

## 实施顺序与验收

1. 独立模块：配置解析、支持矩阵、加密保存、直连/代理状态流和核心启动停止。GitHub Actions构建，不读取正式App路径或生产签名。
2. 本地合成VLESS服务及回显端点：实际HTTP/TLS、文件字节、上传、错误凭据、错误地址、连接中断、DNS/IPv6和核心退出。地址/口令均为临时CI值，不使用用户节点。
3. 独立WebView手机/平板界面：点击选择线路，确认回调后请求、网页/下载/上传同路由、账号隔离、切换时不重复发送、旧页面/草稿保留。只在明确批准的对应交互范围运行，不默认截图或访问真实ChatGPT。
4. 交付独立候选及来源/摘要。真实节点与真实ChatGPT、OEM/arm64和正式接入另行验收；完成前不替换现有登录空间、不宣称网络功能已发布。

## 首次交付记录（升级 WebView 前）

- [最终 Actions](https://github.com/ForceMind/ChatGPT/actions/runs/40924727232) 全部通过，应用源码提交 `badc584e9f99d8c5bb3a69fb5b1c2fdf2acf5488`。构建、JVM 单元测试、Lint 与模拟器运行均在 Actions 完成。
- [arm64 候选下载](https://github.com/ForceMind/ChatGPT/actions/runs/40924727232/artifacts/12918964909)，ZIP 内含 APK、摘要、许可证与依赖清单，保留 14 天。独立包名 `com.forcemind.chatgpt.networkpreview`，临时 CI 签名，不替换正式 App。
- APK 40,668,298 字节，下载归档 14,369,928 字节。去掉手机包中仅供模拟器使用的 x86_64 核心后，APK 从双 ABI 候选的约 76.5 MB 减到约 40.7 MB。arm64 ELF LOAD 对齐实读为 16 KB。
- Android 15 / WebView 124：网页与原生文件 A/B 线路、下载字节、网页 multipart 文件上传、草稿与文档保留、传输阻止切换、失败不回退及显式直连均通过。
- Android 9 / Chrome WebView 69：原生 VLESS A/B 下载与加密存储检查通过；该旧网页组件没有代理覆盖能力，App 明确拒绝网页代理。本项不计为 Android 9 网页代理通过，需要更新网页组件后另验。
- 初轮 runner 未给 KVM 权限，软件模拟器启动约 8 分钟，网页 JS 回调超时；补齐一次性 runner 的硬件加速权限后，原有断言通过。原生缓存仅按固定运行代码/依赖/构建脚本精确复用，当前 host 测试仍每轮执行，缓存许可缺失仍失败。
- 原始摘要与分项范围见 [验证记录](benchmarks/android-network-preview-0.1.0.json)。本机只读取产物元数据/哈希并清理临时下载，没有本机构建、运行 App 或启动浏览器。

剩余验收：真实节点和 ChatGPT、arm64 真机/OEM、IPv6、系统 HTTP 代理/PAC 混用专项及 WebRTC/UDP。旧网页组件降级后的状态恢复、正式账号/资产库接入另行迭代；本批不改正式登录、恢复数据、生产签名、main、官网或公开 Release。

## 官方核对来源

- AndroidX ProxyController：https://developer.android.com/reference/androidx/webkit/ProxyController
- Xray-core 固定源码与许可：https://github.com/XTLS/Xray-core/tree/0afec66085a8
- x/mobile 固定源码与许可：https://go.googlesource.com/mobile/+/197770444867
