# Windows 安装与发布计划

当前迭代为安装目录嵌套修复。先交 GitHub Actions 候选与合成升级报告，再决定公开发布。程序版本沿用 `2026.11.9`，候选由源码提交和 Actions 运行编号区分。

## 当前交付

- 将已注册的 `%LOCALAPPDATA%\Programs\ChatGPT Studio\ChatGPT Profiles` 迁到同级父目录 `ChatGPT Studio`。检查程序包、卸载记录和目录归属，父目录仅包含该旧程序子目录时才执行。
- 联动更新安装注册表、卸载程序、桌面 / 开始菜单快捷方式，以及合成固定快捷方式的目标、工作目录和图标。通过应用状态确认迁移后仍识别为可自动更新的 EXE 安装版。
- 备份放在安装目标外，失败时恢复原程序、注册表和快捷方式，清理本次写入的空目录，使安装可以直接重试。
- 登录与恢复数据继续保存在现有用户数据目录。合成升级比较该目录全部文件摘要，并在安装后读取原 Cookie 与 localStorage。

## 验收与进度

实现已进入待 CI 验证状态。仅在 GitHub Actions 的 Windows 2025 临时运行器构建和验证，使用回环 TLS 页面及合成数据，不截图。

使用 `Windows Studio Branding Candidate` 工作流，先在 desktop 运行 `npm run check`，再构建 EXE 与 ZIP。合成矩阵保留现有六项，新增旧 Profiles 嵌套迁移、当前 Studio 嵌套迁移、嵌套失败回退、回退后重试、父目录含其他文件时拒绝迁移五项。

候选下载、实际测试数量、摘要及剩余问题将在本文件记录。

## 后续顺序

1. 完成本迭代候选与报告，由用户在 Windows 实机验收路径及数据。
2. 跟进 alpha.24 的“更新暂未完成”。已核对线上清单及 alpha.24 原解析函数，识别最新版本正常；用户确认使用 EXE，具体错误文字尚缺，根因未确定。
3. 完善更新下载的可见进度。Mac / Windows 已有主窗口进度组件，Android 尚无应用内更新下载器，三端完整体验仍待单独方案和实现。
4. 接入 Windows Authenticode。当前公开包与本迭代候选均未配置可信签名，仍会出现“未知发布者”。用户暂无 Windows 代码签名证书或已验证的 Artifact Signing 账号，签名服务及身份条件具备后再接入 Actions，并校验主程序、安装器与卸载器签名。
5. 继续已列入路线图的真实账号 / 文件验收、通行密钥、稳定 Apple 签名与 Touch ID、iOS 原生客户端、差量更新等工作，按各自批准范围推进。

Android R8 正式优化批次已发布，包体及 CPU/PSS 对照结果见 [Android 优化记录](android-optimization-plan.md)。本次 Windows 候选沿用现有应用功能与保活策略。
