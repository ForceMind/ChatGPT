# Android Beta.3：兼容与快速退出验证

当前迭代承接Beta.2，持续核实Android9浏览器兼容与新建Profile快速退出的持久化边界，再形成正式接入准备说明。不修改正式版/个人设备/现有登录，不合并main、不公开发布。全部构建与运行在Actions；仅合成数据，无截图或真实ChatGPT。

**本批已交付：[下载固定签名 Beta.3 APK 与两端验收 JSON](https://github.com/ForceMind/ChatGPT/actions/runs/41993581770/artifacts/13399442541)**（需登录GitHub，保留14天）。[Actions41993581770](https://github.com/ForceMind/ChatGPT/actions/runs/41993581770)构建、API28、API35与候选交付四项任务通过。[完整收据](releases/android-network-fixed-beta.3.json)。

## 候选与安装

本批候选为ChatGPT Studio Beta `3.0.0-alpha.24-beta.3` / 3000027，继续使用已有独立包名和固定测试证书，已通过构建、签名、隔离及两端验收。安装时覆盖原固定Beta，不卸载、不清数据；正式版和临时测试版保留，各自数据不迁移。

APK：`ChatGPT-Studio-3.0.0-alpha.24-beta.3-android-arm64.apk`，40,911,377字节，SHA-256：`2e4468dd243fc1266cc346330f104f5993dbec8b190a29421b4de2904126d17d`。

## 已验证结果

源码`a47eafa523b5e2c45b93ab095764f89dffe4f7ca`；77项单元测试通过，16份JUnit、0失败/错误/跳过；lint、签名、包隔离和16KiB检查通过。最终人用APK不包含测试程序、测试provider、合成节点或合成信任材料。

| 项目 | Android9 / API28 | Android15 / API35 |
|---|---|---|
| 原始人用Beta.2安装 | x86模拟器明确拒绝，无匹配ABI；不计原包升级通过 | 原始arm64字节安装成功，使用系统ARM兼容桥，不是ARM真机 |
| 浏览器覆盖升级 | 既有固定签名CI x86 Beta.1→新版x86输入通过 | 实际已交付Beta.2→Beta.3原包通过 |
| 原包自冷启动、升级后及再冷启动 | 合成三账号持久Cookie/localStorage/IndexedDB、四条加密恢复记录等通过 | 同左，通过 |
| 真实RetainedWebView与BrowserMemory策略 | 前台保留执行、真实Home后无后台服务时释放，通过 | 同左，通过 |
| 新Profile可见到Home | 520ms | 393ms |
| Home到真实登记落盘 | 209ms | 749ms |
| Home前是否已周期落盘 | 否 | 否 |
| 随后强停和冷启动 | 新Profile原值及原有三账号数据保持 | 同左，通过 |

快速退出样本明显早于WebView10秒周期提交，直接只读确认真实内核登记，未手工写pref_store；结果支持正常窗口失去前台会触发及时提交，无需为该已验证路径新增写盘或改变保活策略。测试用原生资产窗口承载离线网页，未点选MainActivity的真实账号菜单，也未测试后台任务服务已开启或系统在生命周期回调之前直接杀进程。

## 当前验收范围

1. Android15直接使用已交付Beta.2原始arm64 APK（Actions41986549978/artifact13397180670）作为升级基线，逐字核验已安装APK；验证原包冷启动、覆盖升级和新版冷启动。
2. Android9先尝试正常安装同一个原始arm64 APK；不强制ABI、不改签或重包。若运行器仅支持x86_64且明确拒绝原包，保留原错误，再用此前已构建的固定签名CI x86基线（Actions41959244766/artifact13384831553）补浏览器兼容验证。工件归档SHA256为`be89eb57a743dc968eda09083f4dc10ed7f4e9d0fd3996332fdfc010f82a8847`。该补充结果不计作Android9真实ARM升级验收。WebView只更新全新模拟器的官方固定历史provider，不作为手机安全版本建议。
3. 两端继续检查三账号四标签、持久Secure/HttpOnly Cookie、localStorage、IndexedDB、默认空间隔离、加密恢复记录/资产/偏好/线路保存。模拟器整体拒绝出网。
4. 在新版中使用原生资产窗口承载自有离线WebView，创建额外合成Profile，实际窗口附着并获焦点后写入持久状态。5秒内按真实系统Home，要求窗口实际STOPPED且2秒内只读确认登记落盘；再强停并冷启动核对原值及既有三账号数据。只读观察内核pref_store，不代写文件、不等10秒周期，不使用截图。

上批无窗口短命进程未经历正常前台丢失，其失败不能直接推断正常App退出会丢登录。从WebView124源码看，`AwContentsLifecycleNotifier`在可见窗口失去前台时调用`AwBrowserProcess::OnLoseForeground`提交本地状态。本批先验证该机制，未证实产品缺陷前不添加冗余保活或写盘策略。系统在任何生命周期回调之前直接杀进程，仍不能承诺最后未提交数据零丢失。

## 正式接入准备

保持正式包名、账号/storage ID、加密格式、原签名及保活策略；网络默认关闭，复用既有同路由及文件故障证据。只有已批准的候选验证进入当前迭代；正式原证书升级、真实节点/手机及ARM设备兼容仍单列，不用模拟器或测试证书结果替代。版本与官网下载维持既有公开版本，后续发布需要明确授权。

| 接入条件 | 当前结论与依据 |
|---|---|
| 正式业务源码和核心 | 亲自核对：自原正式接入候选`d0b8db4`以来，`android/app/src/main`、核心bridge及prepare-network-core.sh无差异；本批只改测试/CI/说明，因此复用原路由、文件故障及30分钟后台结果，不重复长测。 |
| 数据与默认网络 | 从代码看：正式身份仍为com.forcemind.chatgpt.profiles；账号/storage ID与加密格式未更改。NetworkSettingsStore在无配置时返回enabled=false，读取失败保留旧文件；没有Beta数据复制到正式版。 |
| 已交付Beta升级 | 本批实际原包、同证书、UID、Cookie/网页存储、加密记录与资产保持已验证，详见上述作用域。 |
| 正式原签名升级 | 未完成。正式alpha.22证书SHA256为`8b6eef928fded91f291ad954e20976b2cc6011b55e8cbec0152695fe1f05cf19`，与Beta证书不同；不得用Beta结果冒充正式原签名验收。本批没有读取、移动或上传正式私钥。 |
| Android9人用ARM包与真实业务 | 仍需对应ARM设备条件及真实登录/节点/语音/文件验收；当前CI的ABI拒绝不能推断手机不兼容，也不能宣称已通过。 |
| 发布与部署 | 当前只交dev候选和报告；main、正式APK、官网下载与更新清单保持。正式签名接入与发布另行安排。 |

## 当前诊断记录

首轮fb0aced / Actions41991865116构建通过；API35在安装任何Beta之前因`adb root`返回`unable to connect for root: closed`停止，首错和原生日志已保留。将root切换的退出码/输出单独保留，只有该明确的transport关闭情形允许等待连接恢复；随后仍强制检查实际UID=0并验证两种防火墙规则，不能跳过隔离或把它计作App升级失败。

首轮API28完整补充验证通过，原arm64包拒绝为INSTALL_FAILED_NO_MATCHING_ABIS；旧CI x86输入升级和普通WebView窗口Home/冷启动通过，样本464ms进入Home、233ms登记落盘。复核发现实际App使用RetainedWebView覆盖可见性回调，因此不把普通WebView结果外推为产品结论；下一轮改用原RetainedWebView和BrowserMemory.register及其真实ActivityLifecycleCallbacks，严格检查前台保留执行、Home后无后台服务时释放执行与登记落盘。没有修改保活实现，也不人工触发释放来代替系统回调。

真实BrowserMemory会按系统网络状态设置WebView网络开关，因而新版x86验证输入改为正常Beta manifest（具有INTERNET，与人用ARM候选一致）；旧CI原包仍保持其历史无INTERNET manifest不改动。逐阶段精确断言旧CI权限拒绝、新版权限授予，整个模拟器仍防火墙拒绝出网；没有以移除真实组件或跳过权限断言让用例通过。
