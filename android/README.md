# ChatGPT Profiles for Android

原生 Java + Android System WebView，版本 `3.0.0-alpha.1`。打开即进入网页，没有地址栏。界面延续 Mac 版的白色、浅灰和黑色，账号以名称、邮箱备注和小色点区分。手机与平板使用同一 APK，各设备分别登录。

## 设备要求

- Android 9 / API 28 或更高。
- WebView 提供商必须支持 AndroidX `MULTI_PROFILE`。此能力在运行时检查，仅有较新的 Android 系统版本不等于一定支持；请保持 Android System WebView / Chrome 更新。
- 不支持时展示更新提示，阻止进入共享浏览器空间。
- 需要设备网络可以访问 ChatGPT。本应用不提供代理或网络绕过服务。

## 使用

1. 首次启动建立“个人账号”空间，直接打开 ChatGPT。
2. 点击右上角账号名称，选择“添加账号”，填写名称与可选邮箱备注，再在新空间内手动登录。
3. 从“切换账号”选择账号；每个账号拥有自己的标签组，当前名称始终显示在顶部。
4. 点击 `+` 新建标签；长按标签可固定、排序或关闭；“恢复关闭的标签页”最多保留最近十项。关闭最后一页会建立首页标签。
5. “管理当前账号”支持编辑、排序、清除本机登录和移除。清除立即换成全新空间，旧档案下次启动清理；其他账号不受影响。
6. 返回键优先在网页内返回；没有历史时将应用放到后台。刷新、前进、首页位于账号菜单。

网页文件选择使用系统文档选择器，不申请整个存储空间权限；HTTPS 下载保存到用户选择的位置，并使用发起账号的 Cookie，跨站重定向重新选择该账号对应域的 Cookie。`blob:` 下载暂不支持。下载在当前进程内运行，退出进程会取消未完成下载并尝试移除不完整文件。

麦克风与摄像头需要网页来源确认和 Android 系统授权；切换账号/标签会取消未完成的权限和文件选择请求。网页没有 JavaScript 到本地代码的桥接接口，禁止文件 URL、明文流量和忽略证书错误。

第三方身份提供商可能限制嵌入式 WebView 登录。不会伪造浏览器身份或承诺所有登录方式可用；应以真实设备和账号验收为准。系统浏览器的登录属于系统浏览器，不能自动带回此应用的独立档案。

## 本地数据

发布版包名：`com.forcemind.chatgpt.profiles`；调试版加 `.debug`，数据分别存储。

- `files/workspace.json`：账号备注、当前账号、标签和待删除档案。
- `files/workspace.backup.json`：前一个有效状态；主文件损坏时保留损坏原件并尝试恢复，均损坏则停止启动。
- `files/revoked-profiles.json`：清理过的浏览器档案标识；备份恢复时也不能重新启用这些档案。
- 每个账号的 `profile-<UUID>` 由 WebView 自身管理，包含 Cookie 和网页存储；账号清除时更换 UUID。登录弹窗绑定发起账号的同一档案。

系统备份已禁用。不收集密码、不建设云同步。卸载应用会移除其本机空间；清除本机空间不删除 ChatGPT 云端对话。

## 开发与验证

需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0；项目固定 Gradle 8.13 / AGP 8.13.2 / AndroidX WebKit 1.17.1。配置 `ANDROID_HOME`，或创建不提交的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
# 已连接支持多档案的设备或模拟器后：
./gradlew connectedDebugAndroidTest
```

调试 APK 位于 `app/build/outputs/apk/debug/`。单元测试验证账号/标签状态、清除后的档案轮换和导航边界。设备测试使用拦截的本地 fixture（明确标记非 ChatGPT）验证 Cookie、LocalStorage、IndexedDB、CacheStorage 隔离，以及清除记录在损坏恢复时的保护；不会发送模型消息或使用真实凭据。

GitHub Actions 默认运行检查和调试构建。维护者手动运行 `Android` 工作流，可以另外启用模拟器验收或签名构建。检查与设备测试结果分别保留，不把编译通过描述为真实登录验收。

## 签名构建

发布 APK 使用固定签名身份，以便之后覆盖升级。签名材料不进入 Git；本机 `keystore.properties` 结构：

```properties
storeFile=.signing/android-release.p12
storePassword=YOUR_LOCAL_SECRET
keyAlias=chatgpt-profiles
keyPassword=YOUR_LOCAL_SECRET
```

```sh
./gradlew assembleRelease
```

维护者应离线备份密钥和密码；丢失密钥后无法为已安装版本提供同签名更新。CI 签名仅在维护者显式启用时使用仓库的 `ANDROID_RELEASE_KEY`（Base64 PKCS12）与 `ANDROID_RELEASE_PASSWORD` Secrets。普通 PR 检查不读取它们。

## 参考

- [Android WebView 多档案绑定](https://developer.android.com/reference/androidx/webkit/WebViewCompat#setProfile(android.webkit.WebView,java.lang.String))
- [ProfileStore 的创建与删除限制](https://developer.android.com/reference/androidx/webkit/ProfileStore)
- [AndroidX WebKit 发布记录](https://developer.android.com/jetpack/androidx/releases/webkit)
- [AGP 8.13 工具兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)

当前交付证据与待验项统一见根目录 [MACOS_MIGRATION_PLAN.md](../MACOS_MIGRATION_PLAN.md)。
