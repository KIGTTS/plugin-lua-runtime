# 插件编辑器

KIGTTS的插件编辑器独立仓库。本仓库采用 **MIT**，完整文本见[LICENSE](LICENSE)，来源与第三方许可见[NOTICE.md](NOTICE.md)。SDK的许可不会覆盖插件的许可。

## 构建

需要JDK17、Android SDK36、PowerShell7。克隆时获取已固定版本的SDK及共享组件，不需要克隆主程序。

```powershell
git clone --recurse-submodules https://github.com/KIGTTS/plugin-lua-runtime.git
cd plugin-lua-runtime
./gradlew.bat testDebugUnitTest assembleDebug
./Package.ps1 -SigningKey /path/to/publisher-private.der -AndroidSdk /path/to/android-sdk
```

Linux/macOS使用`./gradlew`。编译产物在`build/outputs/aar`，签名插件包在`build/plugin-packages`。发布私钥始终由维护者保管，不提交到本仓库；正式更新沿用既有发布者签名、插件ID与Appdata范围。已发布版本不可覆盖，发行新内容须增加版本号。

[SDK与接口](https://github.com/KIGTTS/plugin-sdk) · [官方源](https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Official-Plugins)
## 0.9.3 · 本地化

Lua 工具可使用与原生插件一致的 `assets/i18n/*.json`。用 `ctx.locale` 取语言标签，或直接调用 `host.t("key", { name = "KIGTTS" }, "备用文案")`。切换界面语言后自动刷新已启用工具的入口和页面，不会重复执行动作，也不修改现有工具数据。
