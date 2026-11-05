# 固定签名 Beta.2：浏览器状态覆盖升级候选

本批目标：以已交付的 `3.0.0-alpha.24-beta.1` / 3000025 原始 arm64 APK 为旧版，覆盖到同证书 `3.0.0-alpha.24-beta.2` / 3000026。包名仍为 `com.forcemind.chatgpt.profiles.networkbeta`，显示名称仍为 ChatGPT Studio Beta；正式版和临时测试版不变。

**候选已交付：**[下载 Beta.2 APK 与验收 JSON](https://github.com/ForceMind/ChatGPT/actions/runs/41986549978/artifacts/13397180670)（GitHub 登录，14天有效）。[Actions 41986549978](https://github.com/ForceMind/ChatGPT/actions/runs/41986549978)的构建、浏览器升级与交付三项任务全部通过。原包基线、自冷启动、覆盖升级和新版再次冷启动均已验证；没有更改个人设备或正式版。[完整收据](releases/android-network-fixed-beta.2.json)。

APK：`ChatGPT-Studio-3.0.0-alpha.24-beta.2-android-arm64.apk`，40,911,373字节；SHA-256：`c6a6154b85a33de8668e2a79b1a2dbfe8b86b2234883faccfb4677ac3a5b1d60`。

## 安装与数据

已有固定签名 Beta.1 的用户，可在候选验收通过后直接打开新 APK 覆盖安装。不要卸载旧 Beta，不要清除数据。只有相同包名、相同固定证书和递增版本号的包可以按此升级。正式版与临时签名测试版使用其他包名，不会迁入其登录或线路配置；请保留它们。

候选仍需自行配置节点，不提供免费节点。构建继续使用既有 Actions Secret，不重建或替换固定证书，不使用正式签名。

## 验收目标与边界

- 旧包直接取自 Actions 41959244766 / artifact 13384976249，强制校验原始字节、大小、证书和已安装 APK 摘要，不用重编旧源码替代。
- 在全新 Android 15 Google APIs x86_64 模拟器中，先检查 ARM64 兼容 ABI，再按正常安装流程安装原始 arm64 APK。该环境不是 arm64 真机；本批不宣称 Android 9 浏览器升级通过。
- 同证书测试程序在目标 Beta 进程中创建真实 WebView Profile，只加载离线合成 HTML，不启动应用 Activity、截图或访问真实 ChatGPT。整个模拟器出网在运行前被拒绝。
- 三账号、四标签：逐账号验证持久 Secure/HttpOnly Cookie、JavaScript 可见 Cookie、localStorage 和 IndexedDB，另检同账号共享与默认 Profile 隔离。预期值只在旧包种子阶段写入，升级后不重新注入。
- 旧包先冷启动自检，再执行 `install -r`，随后验证新版与再次冷启动。期间不卸载、不清数据；UID、证书、Profile/storage ID、网页标记、四条真实加密恢复记录、加密载荷、资产、偏好与线路密文保持。
- 使用持久 Cookie；不承诺会话 Cookie、sessionStorage 或进程内未提交任务在退出后恢复。真实网站登录有效性、通行密钥、真实节点、手机/OEM 行为另行验收。

本批实施与 CI 结果记录于 `docs/releases/android-network-fixed-beta.2.json`。最终候选入口已在构建、签名、隔离检查与浏览器升级断言通过后生成；未合并 main、创建公开 Release、更新官网或个人手机。

## 最终验收结果

源码 `52684c241b0b04c0ee57525a8297e492dda3ca9a`，77项单元测试全部通过（16份JUnit、0失败/错误/跳过），lint、固定证书、隔离和16KiB检查通过。旧版已安装APK SHA-256精确匹配已交付Beta.1，新版也逐字匹配本轮候选；签名与UID在3000025→3000026覆盖后不变，期间没有卸载或清数据。

四阶段均验证：三账号四标签、三组持久Secure/HttpOnly及可见Cookie、localStorage、IndexedDB、同账号共享与默认空间隔离；四条真实PageRecoveryStore密文与草稿/位置、独立加密载荷、资产、偏好、线路密文均保留。每阶段5个自有内联文档，HTTP/未知资源回调为0；全模拟器出网拒绝，应用Activity未启动，无截图或真实网站访问。运行环境是Android15 / WebView124.0.6367.219，x86_64镜像使用libndk_translation运行原始ARM64包；这不是ARM真机或Android9浏览器验收。

本轮种子在全部网页存储断言后，又实测等待2586ms才只读确认三条注册表映射完整落盘；随后旧版冷启动、新版升级和再冷启动都保持同一映射及存储值。确认“已持久保存的基线可升级”，没有改内核pref_store或生产代码来补数据；不宣称新建Profile后在WebView异步提交之前立即强停也能恢复。这一短窗口作为后续风险保留，当前不扩大修复范围。

以下为历史诊断记录，失败轮次没有计作通过。

首轮 `c8aa679` / Actions 41981565990：构建、检查和签名通过，原始 Beta.1 成功安装并执行测试。种子阶段在来源地址断言失败：Android JSONObject 对斜杠转义与 Chromium JSON 表示不同；尚未到 Cookie 保留断言，不能据此判登录丢失。修正为解码后的地址精确比较，并在模拟器退出前保存 logcat；默认空间原生 Cookie 与无 Activity 断言同时补齐，等待复验。

第二轮 `e4eddae` / Actions 41982461084：77项单元、lint及签名/隔离构建通过，种子阶段已走过三账号Cookie、localStorage、IndexedDB与默认空间隔离；末尾拦截回调计数为10，尚无URL明细，不能将此计数直接称为外网请求。保留出网拒绝和零请求断言，补回调URL与主框架标记定位；覆盖升级尚未执行。

第三轮 `d4ff839` / Actions 41983441875 的原始URL明确为5次主框架 `data:text/html;charset=utf-8;base64,` 与5次合成域名 `/favicon.ico`，均本地拦截，无真实网站请求。夹具改为内置 `data:,` 空图标，精确区分并强制核对5次内联主文档；HTTP和任何未知资源仍必须为零，模拟器出网拒绝保留。[官方回调语义](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest))说明data/file也会触发该回调，原总数不是网络请求数。Cookie/存储和升级断言未放宽。

第四轮 `1a5e7ca` / Actions 41984284662：旧Beta.1种子全部断言通过（WebView124.0.6367.219，5次内联文档、0 HTTP/未知请求），其自身冷启动在原Profile存在性检查处失败，尚未安装新版。对照已通过的NetworkUpgradeCompatibilityTest，当前夹具在构造首个WebView之前读取Profile列表，可能早于完整内核启动；现补充构造前后列表，并在首个WebView初始化后、任何命名Profile绑定前仍严格检查原名存在，不创建替代Profile，不延长等待或跳过旧版冷启动。

第五轮 `50dd234` / Actions 41985190404 排除单纯初始化顺序：原生日志确认冷启动构造前后都仅Default，升级仍未执行。读取实际124.0.6367.219官方源码：[Profile登记](https://chromium.googlesource.com/chromium/src/+/124.0.6367.219/android_webview/browser/aw_browser_context_store.cc)、[登记键](https://chromium.googlesource.com/chromium/src/+/124.0.6367.219/android_webview/browser/aw_browser_context_store.h)、[JsonPrefStore](https://chromium.googlesource.com/chromium/src/+/124.0.6367.219/components/prefs/json_pref_store.cc)、[默认10秒异步提交](https://chromium.googlesource.com/chromium/src/+/124.0.6367.219/base/files/important_file_writer.cc)。本批种子约8秒结束，推断可能早于注册表正常落盘；改为种子结束前只读核对真实pref_store内三条原Profile名称/目录映射，20秒有界，记录耗时，不写入或修补内核文件。后续冷启动和升级仍立即校验同一映射、原Profile、Cookie与存储。此为建立持久基线的前置，不能据此宣称新建空间后立即强停也可保留；效果待CI核实。
