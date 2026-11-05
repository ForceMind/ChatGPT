# Android 固定签名长期测试版候选

**最新功能候选：[Beta.4实时网络管理](android-network-panel.md)。**面板实时状态、原位增删改/重试以及关闭清理已在Android9/15原生控件验证通过；沿用固定签名，可覆盖原固定Beta。仍只交付Actions候选，正式版与个人数据不变。

**当前后续候选为[Beta.3兼容与快速退出验证版](android-beta-continuation.md)**，已验证实际RetainedWebView与BrowserMemory生命周期，并明确Android9 CI输入与人用ARM原包边界。下方Beta.1/Beta.2保留为历史升级基线；没有替换正式版或自动迁移数据。

**后续 Beta.2 已完成浏览器状态覆盖升级验证。**以本页Beta.1已交付原APK为旧版，Android15合成Cookie/localStorage/IndexedDB及加密恢复数据升级通过；仍仅Actions候选。下载、安装和准确边界见[Beta.2报告](android-network-fixed-beta-browser-upgrade.md)，Beta.1原包作为可复核升级基线保留。Android9浏览器和真实设备仍待验收。

[下载Actions候选](https://github.com/ForceMind/ChatGPT/actions/runs/41959244766/artifacts/13384976249)，解压安装APK。Android9+、arm64，名称 **ChatGPT Studio Beta**，包名 `com.forcemind.chatgpt.profiles.networkbeta`，版本 `3.0.0-alpha.24-beta.1` / 3000025。

它与正式版和现有临时签名“ChatGPT Studio 测试版”并存，不覆盖它们或迁入其数据。请保留旧应用；Beta需重新登录，在“更多→网络”手动配置自己的节点。支持RAW/TCP none、TLS、REALITY，不提供免费节点。真实节点、登录和实机体验仍由用户验收。

## 固定签名与后续升级

使用测试专用固定证书，私钥/密码仅持久存于仓库两个专用Actions Secret，公钥及SHA256见[证书记录](signing/android-network-beta.json)。CI缺失密钥或签名指纹不符即停止，没有临时debug签名回退；不得重建证书来绕过错误，也不使用正式版证书。本机生成时的临时材料已清理，CI签名材料也有始终执行的清理步骤。请勿删除或擅自替换这些Secret。

后续同包名、同证书且更高版本号的包按Android正常覆盖流程升级，版本按实际交付批次递增。本次首次Beta用同一源码生成两个递增版本号验证基础安装/持久化路径，不是从正式版或临时签名版迁移。下一批功能变化应以这次实际交付包为旧版基线，不能只重建新源码来替代旧包验收。

## 已验证与未验证

[Actions41959244766](https://github.com/ForceMind/ChatGPT/actions/runs/41959244766)四项任务全部成功：两个独立builder的APK签名均匹配固定公钥；77项单元测试/0失败、lint及ARM APK隔离/签名/16KiB检查通过。Android9/15的新模拟器中install-r覆盖后，实际UID与证书不变，版本3000024→3000025，三个账号/四标签、四条真实PageRecoveryStore加密草稿/位置记录、另一加密载荷、资产内容、偏好和加密线路配置保持。测试未卸载或清数据。

升级测试为原生数据测试，不启动Activity或WebView；CI目标移除Internet权限且测试断言它被拒绝。人用ARM候选保留Internet权限，无共享UID且禁用备份。没有实际验证浏览器Cookie、真实ChatGPT、真实节点或个人手机；没有重复后台长测，不改变原保活策略。

[最终收据和校验和](releases/android-network-fixed-beta.1.json)。仅交付Actions候选，未合并main、创建新Release或更新官网正式下载/自动更新清单。旧临时测试版的GitHub预发布仍保留原签名与原包。
