# 安卓 SDK 包缺失说明

`DASHSCOPE_PRON/ssound-android-3.0.2-3/` 目录只包含引擎 `.so` 原生库
（armv5te/armv6/armv7a/i686/mips/mips64/x86_64 变体），
**缺少包含 Java API 的 aar/jar 包**，因此安卓桥接无法编译。

## 需要向阿里云索取的内容

向阿里云控制台「口语评测」项目（或对应技术支持）索取：

1. **完整安卓 SDK 包**（zip），其中应包含：
   - `*.aar`（或 `*.jar`）：含 `SSOralEvaluatingManager` / `SSOralEvaluatingConfig` /
     `SSOralEvaluatingManagerConfig` / `SSOralEvaluatingManagerDelegate` 等 Java 类
   - 对应 ABI 的 `jniLibs`（arm64-v8a / armeabi-v7a / x86_64）
2. 若仅提供 jar：还需确认 `libssound-3-android_*.so` 应放置的 ABI 目录
   （`android/app/src/main/jniLibs/<abi>/`）。

## 拿到 aar 后的接入步骤

1. 将 `*.aar` 放入本目录（`modules/pronunciation/android/libs/`），
   `build.gradle` 已通过 `fileTree(dir: 'libs', include: ['*.aar'])` 自动引入。
2. 核对实际类名/包名：

```bash
unzip -l ssound.aar                    # 查看 classes.jar
unzip -p ssound.aar classes.jar > /tmp/classes.jar
jar tf /tmp/classes.jar | grep -i evaluating   # 确认 SSOralEvaluatingManager 包名
```

3. 若包名与 `com.singsound` 不同，仅需修改
   `android/src/main/java/ai/speakpath/pronunciation/SingSoundNativeClient.kt`：
   - 顶部 import（当前按 iOS 头文件同名 API 推定并已注释）
   - `SdkDelegate` 继承实际 delegate 接口（方法名与 iOS `SSOralEvaluatingManagerDelegate` 对齐）
4. 重新 `npx expo prebuild --platform android` 后构建。
