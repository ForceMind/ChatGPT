# 独立网络包依赖

应用自有代码遵循仓库根许可证。第三方依赖保留各自许可证，不受自有代码的非商业限制覆盖。

- Xray-core：`v1.260327.1-0.20261029083850-0afec66085a8`，MPL-2.0；[对应源码](https://github.com/XTLS/Xray-core/tree/0afec66085a8)，[许可证](https://github.com/XTLS/Xray-core/blob/0afec66085a8/LICENSE)。未修改其源码，Go 模块在 CI 下载；本仓库只维护启停接口。
- golang.org/x/mobile：`v0.0.0-20261011034528-197770444867`，BSD-3-Clause；[源码及许可证](https://go.googlesource.com/mobile/+/197770444867)。
- AndroidX WebKit 1.17.1：Apache-2.0；[源码及许可](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)。

CI 候选附带完整 Go 模块版本清单与代理核心许可证；正式分发前须补齐传递依赖许可清单。实验包不复用生产签名。
