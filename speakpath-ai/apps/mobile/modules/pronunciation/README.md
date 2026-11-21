# SingSoundPronunciation —— 阿里云智能语音评测原生桥接模块

Expo 本地原生模块（`modules/pronunciation/`），封装先声 SingSound 3.0.2 SDK，
为 SpeakPathAI 提供 iOS / 安卓端在线发音评测能力（warrantId 授权 + 流式评测）。

## 目录结构

```
modules/pronunciation/
├── expo-module.config.json        # Expo 自动链接配置（apple + android）
├── index.ts                       # TS 入口（再导出 @speakpath/audio 的适配层）
├── ios/
│   ├── PronunciationModule.swift  # Swift 桥接（Expo Modules API）
│   ├── SingSoundPronunciation.podspec
│   └── SingSound.framework/       # vendor 的官方 SDK（已补 Modules/module.modulemap）
└── android/
    ├── build.gradle
    ├── libs/README.md             # ⚠️ 缺 aar，见下
    └── src/main/java/ai/speakpath/pronunciation/
        ├── PronunciationModule.kt          # Kotlin 桥接（Expo Modules API）
        └── SingSoundNativeClient.kt        # SDK 调用层（全部集中于此文件）
```

## 调用约定（两端一致，JS 侧见 @speakpath/audio）

| 方法 | 说明 |
| --- | --- |
| `initWithConfig(appKey, secretKey, userId)` | 注册在线评测引擎 |
| `setWarrantId(warrantId, timeout)` | 配置服务端签发的授权（timeout=过期时间戳秒） |
| `start(config)` | 内置录音评测 `{ coreType, refText, rank, precision, openFeed, vadEnable, sampleRate }` |
| `startWithStorePath(config, path)` | 内置录音评测并保存 wav |
| `startWithWavPath(path, config)` | 本地音频文件评测（不走麦克风） |
| `startExternalAudio(config)` / `feedAudio(base64)` | 外部喂音频模式（配合 RN 侧采集） |
| `stop()` / `cancel()` / `destroy()` | 结束 / 取消 / 释放引擎 |

事件：`onInitSuccess / onStart / onStop / onResult / onError / onVolume / onRequestId /
onRealTimeResult / onVadFrontTimeout / onVadBackTimeout / onRecorderWillTimeout / onWarrantIdNeedUpdate`。

## 业务接入（应用层）

Web 跟读页面从后端 `POST /api/v1/speech/pronunciation` 获取 `appid`、`userId` 和短时 `warrantId`，然后用 Web SDK 发起评测。将经授权的 `engine.js` 放在 `apps/mobile/public/sdk/`。

原生桥接保留了旧 SDK 的 `initWithConfig(appKey, secretKey, userId)` 接口。原生发布前，需要确认并接入供应商支持的短时授权初始化方式。评测项目的长期 AppSecret 仅由后端读取。

## iOS 接入步骤

1. 把授权的 `SingSound.framework` 放在本模块 `ios/` 下，然后执行 `npx expo prebuild --platform ios`（生成 ios/ 工程，自动链接本模块 —— 已配置
   `expo.autolinking.searchPaths: ["./modules"]`）。
2. `pod install`（本模块 podspec 通过 `vendored_frameworks` 挂载 SingSound.framework）。
3. 麦克风权限已在 `app.json` 的 `NSMicrophoneUsageDescription` 声明，无需改动。
4. 构建：`npx expo run:ios`。

注意：`SingSound.framework` 为静态 framework（含 i386/armv7/x86_64/arm64），
已为其补充 `Modules/module.modulemap`（原包缺失，Swift `import SingSound` 依赖它）。

## 安卓接入步骤（⚠️ 需要向阿里云补包）

1. `npx expo prebuild --platform android`。
2. **向阿里云控制台「口语评测」项目索取完整安卓 SDK 包**：
   当前 `DASHSCOPE_PRON/ssound-android-3.0.2-3/` 只有引擎 `.so`，
   缺少含 Java API 的 **aar/jar**（无 `SSOralEvaluatingManager` 等类，无法编译）。
3. 将 aar 放入 `android/libs/`，并按 [android/libs/README.md](android/libs/README.md)
   核对类名后取消 `SingSoundNativeClient.kt` 顶部 import 注释。
4. 构建：`npx expo run:android`。

## 测试情况

| 测试 | 结果 |
| --- | --- |
| iOS Swift 桥接 `swiftc -typecheck`（真实 SingSound.framework 头文件） | ✅ 0 error |
| TS 类型检查 `pnpm typecheck`（新文件无错误；项目原有报错与本模块无关） | ✅ |
| Web 构建 `pnpm build:web`（expo export） | ✅ |
| 适配层运行时单测（node + type stripping，11 项：降级、归一化、事件映射、warrant 续期等） | ✅ 11/11 |
| 安卓 Kotlin 编译 | ⏸ 缺 aar 无法编译（代码已按 iOS 同名 API 对齐） |
| 真机端到端（需要真实 appId/appSecret + 真机） | ⏸ 待真机验证 |
