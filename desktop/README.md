# ChatGPT Profiles Desktop

桌面开发版本：`3.0.0-alpha.2`（Windows 首版试用）；已公开的 Mac 安装包仍为 `3.0.0-alpha.1`。Electron `44.9.1`、React `19.3.1`、TypeScript。各设备独立使用，本版不需要自建服务。

## 使用

```sh
npm ci
npm start
```

`npm ci` 的安装步骤准备官方 Electron 运行时。如果安装在网络中断时退出，恢复连接后执行 `npm run setup`。已构建后可直接执行 `npm run start:built`。

1. 首次打开直接进入 ChatGPT，默认建立“个人账号”空间。
2. 点击“添加账号”，填写名称、可选邮箱备注和颜色；在新空间的网页内登录另一个账号。
3. 通过右上角账号菜单切换其专属标签组；顶部持续显示当前账号名称，菜单中显示邮箱备注。界面没有地址栏和额外账号侧栏。
4. 标签支持新建、关闭、恢复、固定和拖动排序；右键标签或使用 macOS“标签页”菜单操作。关闭最后一个标签会保留一个首页标签。
5. 在账号管理中修改备注、调整账号顺序、清除该账号的本机登录数据或移除账号。清理和移除均有本机确认，并关闭该账号的标签和登录弹窗。

顶部仅一行：窗口控制、标签页、拖动空白区和账号切换。刷新、前进后退、首页、外部打开和原生全屏位于 macOS 菜单及快捷键中。原生全屏的窗口移动遵循 macOS 行为，退出全屏后可移动。

账号名称与邮箱是手动备注，真实身份以网页内账号菜单为准。本程序不收集或保存密码；登录 Cookie 由浏览器保存。

## 快捷键

| 操作 | 快捷键 |
|---|---|
| 新建标签 | `⌘T` |
| 关闭标签 | `⌘W` |
| 恢复关闭标签 | `⌘⇧T` |
| 添加账号 | `⌘⇧N` |
| 切换标签 | `⌃Tab` / `⌃⇧Tab` |
| 第 1–8 个 / 最后一个标签 | `⌘1`–`⌘8` / `⌘9` |
| 重新加载 | `⌘R` |
| 打开/关闭账号菜单 | `⌘B` |
| 后退 / 前进 | `⌘[` / `⌘]` |
| 进入/退出全屏 | `⌃⌘F` |

## 数据和恢复

- 开发版：项目内 `.local-data/`。
- Mac 安装版：`~/Library/Application Support/ChatGPT Profiles/`。
- Windows 安装版及 ZIP：`%APPDATA%\ChatGPT Profiles\`。免安装 ZIP 仍使用该数据目录，不把登录数据写入程序文件夹。
- `state.json`：账号备注、标签、窗口位置；`state.backup.json`：上一个有效状态。
- `Partitions/profile-<UUID>/`：每账号独立持久浏览器数据。
- 损坏主状态文件时，从有效备份恢复并保留损坏原件；主文件与备份均损坏时停止启动，避免静默清空账号。
- 清除本机登录数据不会删除 ChatGPT 云端对话，也不等同于撤销服务器端所有登录会话。
- 旧版与新版浏览器内核不同，首版按重新登录设计，不读取旧版 Cookie。

账号登录弹窗使用发起账号的同一 session。网页采用沙箱和上下文隔离，没有 Node 或本地控制 IPC。系统浏览器打开的链接使用系统浏览器自身的账号状态。摄像头、麦克风、通知和剪贴板读取按来源请求本机许可。

账号菜单或编辑框打开时，使用仅保留于内存的网页画面作为背景，避免原生网页视图遮住本地菜单；关闭即清除，不写入状态文件或上传。

## 验证和构建

```sh
npm run check
npm run test:e2e
npm run dist:mac
```

- 单元测试覆盖状态持久化、损坏恢复、账号归属和导航策略。
- Electron 验收仅使用本机回环网页、临时数据目录，页面明确标记“非 ChatGPT”。测试不会操作真实账号或发送模型消息。
- `dist:mac` 在临时目录干净打包并作本机 ad-hoc 签名，将 ARM64 DMG、ZIP 及 `build-info.json` 放到 `release/`；其中记录 `.app` 的实际路径和安装包摘要。Developer ID 签名、公证和 Intel Mac 实机验收尚需对应外部条件。
- 渲染截图与测试报告保存于 `test-results/`、`playwright-report/`，不纳入 Git。
- 原生拖动的开发诊断未取得自动化通过证据；用户已在最终 App 中用真实鼠标确认普通窗口顶部空白区可以正常拖动，此项人工验收通过。

完整验证记录和未验证项见仓库根目录 `MACOS_MIGRATION_PLAN.md`。实际 ChatGPT 登录、第三方登录提供商、语音及真实文件上传下载必须分别实测，不能从本地隔离测试推定通过。

## 依赖与参考

新版源码独立编写；旧目录的上游署名及许可元数据保留。应用图标由本项目 SVG 绘制，未复制旧版图标。

- Electron 会话分区：https://www.electronjs.org/docs/latest/api/session
- Electron 安全建议：https://www.electronjs.org/docs/latest/tutorial/security

本轮依赖审计：生产依赖 0 个已报告漏洞；打包工具 `electron-builder` 的间接依赖链报告 8 个 moderate 告警，根项为 `sprintf-js` 格式化拒绝服务问题。打包工具不随 App 分发；未使用强制降级或放宽检查掩盖告警。

## Windows 构建与使用

目标为 Windows x64；Windows ARM64 暂不原生打包。顶部使用 Windows 原生窗口按钮，账号菜单为按钮区域留空，不显示地址栏。`Ctrl+T/W/Shift+T` 分别新建、关闭、恢复标签；`Ctrl+Shift+N` 添加账号，`Ctrl+B` 切换菜单，`F11` 全屏。按 `Alt` 可临时显示系统菜单。

在 Windows x64、Node 26 环境执行：

```sh
npm ci
npm run dist:win
```

输出在 `release/windows/`，含 EXE 安装包、ZIP、`windows-build-info.json` 与 `SHA256SUMS.txt`。安装包默认按当前用户安装，不要求管理员权限；ZIP 解压后运行 `ChatGPT Profiles.exe`。退出应用后可更新程序文件，保留账号数据目录。卸载默认保留账号数据；在应用内清除账号才会清除相应登录。

当前未配置 Authenticode 证书，试用包未签名，SmartScreen 或组织策略可能拦截；请核对来源与摘要。CI 只验证本地 fixture 隔离、窗口、安装与卸载，不代表 Windows 10/11 真机与真实 ChatGPT 登录已经通过。最新检查及安装包入口见根目录进度记录。
