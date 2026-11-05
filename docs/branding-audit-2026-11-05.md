# ChatGPT Studio 命名审查（2026-11-05）

范围：Windows/macOS/Android当前源码、打包/安装/更新检查、官网普通下载和兼容更新清单。审查快照 `365e9066cd5ad7eb2f6ca686c52333550a28fe71`。本轮只读审查与报告，没有修改实现、运行应用、截图、安装用户设备或执行浏览器测试；未重新下载/运行Windows已发布安装包，因此不把静态结论写成运行界面已验收。

## 已定位的待修项

| 项目 | 依据与实际影响 | 建议 |
|---|---|---|
| Windows默认程序目录仍为旧名 | desktop/package.json:108显式win.executableName=ChatGPT Profiles；oneClick=false。锁定app-builder-lib26.15.3的appInfo.productFilename使用executableName，NsisTarget将getWindowsInstallationDirName结果设为APP_FILENAME，辅助安装模式采用该productFilename。因此从源码规则看，新装目录末级仍为ChatGPT Profiles；已有安装又会优先采用注册表InstallLocation。与用户实报吻合。 | 新装目录改Studio；旧默认目录另做可验证、可回退的程序目录迁移，不直接全局替换。 |
| Windows主EXE和卸载EXE仍为旧名 | 同一executableName配置决定ChatGPT Profiles.exe；锁定NSIS common.nsh:17定义Uninstall ${PRODUCT_FILENAME}.exe，PRODUCT_FILENAME也是旧productFilename。尽管卸载列表显示名已为Studio，卸载程序文件名仍沿用Profiles。 | 新程序、卸载程序统一Studio；联动快捷方式/卸载注册目标与重启路径。 |
| Windows更新识别只允许旧EXE | desktop/src/main/windows-update-platform.ts:27只接受chatgpt profiles.exe；不匹配即registeredWindowsDirectory返回undefined，createWindowsUpdates选择manual。 | 同时识别已验证的旧/新EXE，保留原目录、卸载项关联、危险目录拒绝等安全约束；不能只改打包名。 |
| 实际更新浮窗仍显示旧品牌 | desktop/src/renderer/update-notice.html:1含CHATGPT PROFILES · 更新已准备。该文件由build.mjs打包，并由main.ts:1912实际loadURL使用，不是历史夹具。 | 仅显示文案改为ChatGPT Studio，保持更新判断与任务/空闲策略。 |
| 更名验收漏掉默认目录，且锁定旧EXE | check-windows-install.ps1:9-14使用自定义临时目录与/D覆盖默认路径，只按Profiles.exe查找；check-windows-upgrade.ps1:22、58沿用同一个旧EXE变量到升级后启动。检查ProductName=Studio不能证明目录/EXE更名。 | 增加不传/D的新装默认路径验收；旧默认/自定义安装各自升级；新EXE和卸载EXE、快捷方式/注册表目标、旧程序残留、失败回退分别验证。旧种子仍应按真实旧名启动，不把夹具全局换名。 |
| 官网装饰用词 | site/index.html:112的SYS.PROFILES / LOCAL是可见装饰文字，并非旧应用完整名称，但会产生品牌混用感。 | 可同步改为Studio或中性本地工作空间用词；此项是文案一致性建议，不是安装/数据故障。 |

## 不应直接替换的兼容身份

- `main.ts:85`固定的ChatGPT Profiles数据目录：Windows安装版/ZIP的APPDATA以及MacApplication Support数据与加密恢复已沿用；官网帮助区跨行显示该真实路径。不能只把文案写成Studio或直接迁移目录。
- `main.ts:91`的内部app.setName、com.forcemind.chatgpt.profiles应用ID/WindowsAUMID、Android正式包名：涉及Chromium/Keychain、安装识别和已有数据身份；本轮不建议为字符串整洁改动。
- Mac兼容更新ZIP的Profiles文件名/包根目录、旧目录启动更名器、临时更新目录白名单：shared/updates.ts:69、update-install.ts、update-manager.ts、bundle-name.ts、update-installer.c及site/updates/macos.json共同约束。普通下载已为Studio，旧更新包为兼容用途，不应删除或随意改名。
- 旧Release资产名、固定摘要、历史alpha.18升级种子和测试期望：保留可复核历史。只改未来产物及当前用户可见名称，不重写历史记录。
- npm内部包名chatgpt-profiles与图标/打包临时目录前缀不是当前UI显示名。辅助NSIS安装模式下修正EXE名即可修正新装目录命名，无需借此改内部身份。

## 已有正确配置与未验证项

- Windows productName、桌面/开始菜单shortcutName和uninstallDisplayName已为ChatGPT Studio；这只是源配置确认，不等同于逐个系统入口运行验收。
- Mac主应用/DMG命名为Studio；main.ts:2456在桌面通用就绪流程显式设置AboutPanel applicationName=ChatGPT Studio，因此Windows/Mac“关于”的源配置都已覆盖旧内部名，不能将app.setName旧值直接判成“关于”标题错误。
- Android正式manifest、临时测试版和固定Beta的显示名称、当前打包文件前缀已区分；内部包名含profiles是身份，不是旧品牌UI文案。
- 官网普通下载中没有命中旧完整品牌名；Mac兼容更新URL和真实数据路径说明按兼容项保留。普通UI/Android标签的静态扫描没有发现其他旧完整品牌明文，不宣称全部系统通知、任务栏或图标视觉已验证。
- 旧通用promote-validated-release脚本保留Profiles前缀断言；该workflow的modern候选分支调用publish-desktop-candidate，后者支持Studio产物，旧脚本不能混同当前发布路径并全局替换。

## 下一批修复建议（待确认，不是本次已实施）

1. 统一Windows新装目录、EXE、卸载EXE为Studio；主应用更新器同时兼容旧/新可执行名，保留严格目录与卸载身份检查。
2. 对可验证的旧默认程序目录设计迁移和回退，仅移动程序，不动旧账号数据目录/密钥；自定义目录、目标占用或身份不明不自动覆盖。桌面/开始菜单、卸载/重启目标及已固定任务栏入口列入迁移验收，不能假设旧路径会自动消失。
3. 更新实际空闲更新弹窗的旧品牌文案；官网装饰文案可一并收口，布局保持。
4. 修改安装/升级验收，补默认路径而非只测/D；保持旧版种子真实名字，并分别校验升级后的新入口。原旧安装脚本包含真实网页启动和截图，本轮未执行；下一批应另明确合成/native验收范围，避免直接运行旧脚本越界。
5. 桌面实现提交必须跑desktop/npm run check，保留Prettier与ESLint规则；不本机打包，所需构建/合成验证放Actions，不合并main或自动发布。若不需要界面运行，先用原生安装、注册表、快捷方式和合成数据标记验证路径与数据保留，不把它替代成浏览器业务验收。

## 安装目录规则来源

读取项目package-lock锁定的官方npm源码包，SHA512与锁文件integrity一致，未安装依赖或上传第三方源码：

[app-builder-lib26.15.3分发源码](https://registry.npmjs.org/app-builder-lib/-/app-builder-lib-26.15.3.tgz)。关键位置为out/appInfo.js:54-57、out/targets/targetUtil.js:40-41、out/targets/nsis/NsisTarget.js:165-166、templates/nsis/common.nsh:17及multiUser.nsh:25-47。它们分别证明EXE/productFilename关系、默认目录选择、卸载EXE名以及旧InstallLocation优先复用。

## 已批准修复实施记录（2026-11-05）

用户已确认完整方案：新装及旧默认程序目录Studio，安全回退、自定义目录保留，更新器兼容旧/新EXE、快捷方式及更新浮窗联动；数据目录/AppID/Mac兼容包不变。实现源码仅dev，完整npm run check和默认/旧默认/自定义安装、程序备份/注册信息回退、Cookie/LocalStorage保留等合成启动均在独立Windows Actions；不运行旧截图/真实网页检查，不发布或合并main。当前代码尚待CI，不将实现当作通过。

实现首轮Actions41970512586的typecheck/lint通过，format:check报告124文件不符而停止；源Git blob为LF，正在固定Windows checkout的core.autocrlf=false并记录git ls-files --eol，保持原Prettier规则，不整体格式化或改blame忽略列表。补充保留Plan阶段错误及每次安装的迁移结果，避免失败时只留退出码。

后续CI41971460871、41972291363均通过完整check、脚本解析和NSIS编译，但停在合成TLS准备（Cert驱动缺失、RSA KeySize只读），未执行安装；已用显式RSACng和临时JWK/DER替代证书存储依赖，增加提前TLS自检。41973125990进入实际安装前校验，因安装器PowerShell找不到Get-FileHash而退出3；迁移结果保留首错且未准备/卸载旧程序。现改为相同SHA256算法的.NET文件流，仍逐文件强制比对，不把这些失败当通过。

41974018097通过完整check、新装及旧默认目录迁移、快捷方式目标和安装前后用户数据字节比对，随后Cookie读回失败。夹具设置的是无有效期的会话Cookie，不能据此判定持久登录数据丢失；补充明确有效期和session=false的种子前置断言，保留升级后原值与LocalStorage严格读回验收，等待重跑。

## Verified candidate: alpha.24

[Actions 41975812832](https://github.com/ForceMind/ChatGPT/actions/runs/41975812832) passed on source `6412dad85abeee8d889b628afb910e77da6117e4`: full `npm run check` exit 0; 106 unit tests, 105 passed, 0 failed, 1 Mac native installer test platform-skipped. PowerShell parsing, EXE/ZIP packaging and all six synthetic installation cases passed.

- Fresh default: `%LOCALAPPDATA%\Programs\ChatGPT Studio`, `ChatGPT Studio.exe`, `Uninstall ChatGPT Studio.exe`.
- Public alpha.21 legacy default migrated to Studio; custom directory location retained. Desktop, Start Menu and synthetic pinned shortcut targets updated. User-data SHA256 values unchanged across installation; persistent Cookie and LocalStorage values survived packaged application restart.
- Forced failure after new payload installation restored old program bytes, registry and data; old application started and read original Cookie/LocalStorage. Unknown old files and occupied destinations were refused without overwriting owned sentinel files.
- Data directory, app identity and Mac compatibility identities unchanged. No personal installation or login data touched.

[Candidate EXE and ZIP](https://github.com/ForceMind/ChatGPT/actions/runs/41975812832/artifacts/13392425702), retained 14 days, GitHub login required. Windows code signing is absent; Windows may warn about an unknown publisher. Hashes and evidence: `docs/releases/windows-branding-alpha.24-candidate.json`. Source/report pushed to dev only; no main merge, public Release, website deployment or update-feed change.

Limits: isolated Windows runner and owned loopback TLS pages only; no screenshots, real ChatGPT or real account acceptance. Pinned shortcut target verified, not Explorer icon-cache appearance. Run 41975077148 had one generic launch failure after the fresh case; the instrumented full matrix passed with seven packaged app launches exiting 0. Its earlier failure root cause remains unknown and is not claimed fixed.
