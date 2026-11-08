# 三平台常规正式发布：2026.11.9

用户已授权测试并正式发布 macOS、Windows、Android，每个平台仅保留一个最新下载入口；Beta/候选从官网主下载区撤下，历史 Release 保留。三平台完整 CI 合成界面测试与 CI 匿名截图已获明确同意，不访问真实账号，不自动更新个人设备。测试报告必须列实际 OS，不能把 CI runner 等同于全部历史版本或所有厂商真机。

## 当前执行顺序

1. 官网重复入口与 Actions 404：已移除两个额外 Android 面板，旧分享锚点转到唯一 Android 卡片，源码 `ec5ed08`。部署 `e1d9f438.chatgpt-studio.pages.dev`，回读处理中；新正式包通过前仍保留原公开 APK，不冒称最新版。
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

待追加实际 CI、发布、主分支同步和生产回读结果。尚未发布新正式 Release。
