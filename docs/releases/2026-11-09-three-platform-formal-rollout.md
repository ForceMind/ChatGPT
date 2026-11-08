# 三平台常规正式发布：2026.11.9

用户已授权测试并正式发布 macOS、Windows、Android，每个平台仅保留一个最新下载入口；Beta/候选从官网主下载区撤下，历史 Release 保留。三平台完整 CI 合成界面测试与 CI 匿名截图已获明确同意，不访问真实账号，不自动更新个人设备。测试报告必须列实际 OS，不能把 CI runner 等同于全部历史版本或所有厂商真机。

## 当前执行顺序

1. 官网重复入口与 Actions 404：已移除两个额外 Android 面板，旧分享锚点转到唯一 Android 卡片，源码 `ec5ed08`。部署 `e1d9f438.chatgpt-studio.pages.dev`，三域名 HTTP 回读已验证：恰好三个下载卡片、一个 Android 入口、无 Actions 下载链接；新正式包通过前仍保留原公开 APK，不冒称最新版。
2. 冻结桌面日期版本 `2026.11.9`（SemVer 年.月.日，兼容现有更新器和 Windows 文件版本），依赖、格式和 lint 规则不改。以待 CI 验证提交推 dev，构建/完整 npm run check/界面与安装升级验证在 Actions。
3. Mac：运行现有 macOS release workflow，arm64、macos-15；验证真实安装助手、加密恢复和菜单栏。当前为 ad-hoc 签名、未经 Apple 公证，不冒称 Developer ID 签名。
4. Windows：运行现有 Windows workflow，x64、windows-2025；完整界面、原生输入、安装/卸载保留数据及 NSIS 升级。报告中列 Windows Server runner 具体版本，不冒称 Win10/11 实机；当前无 Authenticode。
5. Android：复用不变的原签 APK `2026.11.08-1923` / code3000035 / SHA256 `177ef874c6c904a653aecb3ffc61a76ef45ebc8e69cb80c6addbdb9e74806259`，原签 CI `42493929455` 与完整功能证据复核后上传；原包名和证书不变，不发布测试 instrumentation APK 或独立 Beta，不上传私钥。
6. 所有要求门禁通过后，将同一批验证字节上传统一正常 Release `v2026.11.9`，不标 prerelease；失败不放宽断言或改名掩盖。记录 App/harness 源码、实际 OS、单元/E2E 数量、全部文件 bytes/SHA256、已知限制。未完成的真实 ARM64 手机/真实 ChatGPT/节点验收明确列出。
7. 同步 main/dev 和中英文 README，官网仅三个系统卡片，全部指向可匿名下载的长期 Release 附件，更新国内加速、里程碑、Mac/Windows 自动更新清单。先验证 URL/字节/版本，再生产部署并回读；保留旧 Mac 更新包名兼容，保留已有登录/恢复数据身份。

## 边界与遗留

- Android 首次连接/输入 sink 的历史间歇问题尚有未定位记录；本次若能复现并阻塞验收，先定位修正，不以重复直到变绿代替原因说明。
- iOS/iPadOS 不纳入本次三个平台发行。Apple 后台挂起限制不等于不可开发；前台多标签、本地保存与恢复可设计，后台任务受系统控制，不承诺无限驻留 WebView。相关规划独立，不混入会话库研究对话的实现。
- `docs/local-conversation-knowledge-client-plan.md` 为另一个对话的未提交方案，保留原样，不混入本次提交。

## 运行与交付记录

桌面源码 `6d337d7` 已推送并启动 Mac `42501547295`、Windows `42501551123`。Mac 完整 check 通过并进入页面验收；Windows 的格式门禁报 124 文件差异。仓库已有 LF 源字节，旧 Windows branding workflow 也专门禁用了 autocrlf；本次用 `.gitattributes` 对 desktop 设置 text=auto/eol=lf，保留二进制并固定检出，不整体格式化、不改 Prettier 或 lint 规则。该提交不改 desktop 树；Mac 原验证继续，Windows 使用新检出策略验证。尚未发布新正式 Release。

首轮 Mac 界面 5 项失败：站点缺 alpha.24 统一清单；更新夹具固定 alpha.999 已低于日期版，三种路径不触发升级；认证字段关闭恢复实例检查失败。Windows 固定 LF 后完整 check 通过，后续更新夹具同样报当前 idle 而非 available。现补基于已发布原记录的 alpha.24 清单，测试新版改为当前 major+1 的真实更高版本，原升级断言和预算不变。关闭标签的认证字段防护加强为主文档存在 password/OTP 字段即不缓存该 view，不依赖布局可见性，不清 Cookie/账号空间，不改后台保活；原恢复用例增加命令成功断言与仅含布局/标识的诊断，原新实例/无密码断言保留。上述修改待下一轮完整 CI，不把静态修正写成通过。

最新源码 `9cc7ecd`：Desktop CI 成功；Mac `42503190684` 完整 check 成功，24 个页面用例通过、1 个 Windows 专用跳过、1 个站点清单失败（旧 Mac manifest 的 blockmap 和兼容 ZIP 被误计为主下载）。修正为仅匹配 Studio DMG/ZIP，保留五个主下载断言。恢复、热切换和三种更新安装用例已实际通过。Windows `42503194662` 完整 check 成功，19 个页面用例通过、23 个平台专用跳过；最后安装包迁移用例在打包步骤前执行，报 windows-build-info.json 不存在。新增专用 windows-formal-validation.yml 将原打包步骤前移，完整用例、安装升级及后台留存步骤均保留，不改原 windows.yml。两项修正待下一轮 Actions，未发布正式包。

`19d0a41` 的 Mac `42503890335` 再次 24 通过/1 跳过，站点更新清单对照发现新增 alpha.24 清单缺 SHA-512；从原公开 Release 的 windows-build-info.json 按同名/bytes/SHA-256 核对补齐，原 SHA-512 断言保留，修正文档 `553e22e` 后启动 Mac `42504496888`。Windows `42503893665` 已构建安装包，19 页面用例通过，安装迁移首步失败码 3，原证据错误“目录不属于可识别的 ChatGPT Studio 版本”，回退成功。代码定位 windows-migration.ps1 仅接受 alpha 并只按末段数字防降级；修正为严格 alpha/stable 版本解析和完整三段＋预发布顺序比较，保留应用身份、目录归属、文件哈希与回退守卫。新增 14 项无浏览器版本检查；完整安装迁移回归不删减。Mac 不包含 Windows PS/NSIS 资源，原 Mac CI 继续，不因此重复已有效验收。

Mac `42504496888` / `553e22e` 已全部成功：106 单元通过，25 E2E 通过、1 Windows 专用跳过、失败/重试 0；原 ZIP 与兼容 ZIP 的真实 alpha.18 助手升级核对账号数据不变，包内恢复及原生菜单栏通过。实际 macOS 15.8.2 arm64（最终日志待统一提取）。只下载约几十 KB 的 JSON 收据，本机未构建或运行页面。Windows 新根因修正 `51950d2` 的专用运行 `42504717549` 正在完整界面/安装迁移验收。已建非公开草稿 Release `438403006`，tag `v2026.11.9`、target `51950d25ba0d2342f194a811e082ae6322513e33`、draft=true/prerelease=false，尚无公开新包。Mac 产物来自 `553e22e`，后续仅 Windows 专用 PS/NSIS 变更不会进入 Mac bundle；发布脚本按平台核对实际产品输入，不重编已验收原字节。

Windows `42504717549`：完整 check 成功，新增 14 项版本比较/拒绝检查成功，20 E2E 通过/23 平台跳过，六项实际安装迁移与回退完成。后续旧常规 smoke 脚本在寻找旧 EXE 名时报 Installed application is missing。只改验收脚本：新安装和升级后的启动目标明确为 Studio，旧 alpha.18 基线仍用 Profiles；主启动检查和升级后启动均接 CI 自有 TLS 回环页面，Chromium 全域映射 127.0.0.1 并仅信任一次性 SPKI，不修改正式程序或开启产品测试后门。临时 TLS 私钥仅在 runner 临时目录并在结束清理；请求取证单列，不访问真实 ChatGPT。所有原始启动/退出/卸载数据保留、升级精确 ASAR、身份和清理断言保留，完整 Windows 流程继续验收。

最终 Windows `42505793683` / `f9f4d4e` 全部成功：106 单元中 105 通过/1 平台跳过，14 版本检查通过，20 E2E 通过/23 平台跳过/失败重试 0，六项安装迁移完成；Studio 原生启动/正常退出/卸载保留数据、alpha.18 shipping updater 升级、精确 ASAR 与原身份保留通过。8 标签留存 330,276 ms，实例/草稿不变，后台事件持续；两次安装启动均记录自有 TLS 请求，未接真实网页。实际 Windows Server 2025 Datacenter 10.0.26100 x64。最终 `2026.11.9.json` 冻结六个二进制（五个主下载＋Mac 旧名兼容 ZIP）及实际 OS/数量/边界；公开前由 Actions 再核验所有原字节并上传草稿。草稿 target 更新为最终 Windows build/source `f9f4d4e35661ccc20fc15853ffef661c592c23c3`，依然 draft=true/prerelease=false。尚未公开新版本或改变线上更新源。
