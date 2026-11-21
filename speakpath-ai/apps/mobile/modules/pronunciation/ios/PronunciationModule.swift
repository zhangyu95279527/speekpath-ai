import ExpoModulesCore
import SingSound

/**
 * Expo 原生模块：桥接阿里云智能语音评测（先声 SingSound）iOS SDK。
 *
 * 流程（与 Web 端 JS SDK 一致）：
 * 1. 后端 POST /speech/pronunciation 签发 warrantId；
 * 2. initWithConfig(appKey, secretKey, userId) 注册在线评测引擎；
 * 3. setWarrantId(warrantId, timeout) 配置授权；
 * 4. start(...) 内置录音评测，或 startExternalAudio + feedAudio 由 RN 侧喂音频；
 * 5. stop() 结束评测，结果通过 onResult 事件回传。
 *
 * SDK delegate 协议要求 NSObject，且回调线程不定；
 * 这里用独立的 NSObject 桥接对象承接回调，再统一切回主线程 sendEvent。
 */
public class PronunciationModule: Module {
  private let manager: SSOralEvaluatingManager = SSOralEvaluatingManager.share()
  private let delegateBridge = PronunciationDelegateBridge()

  public required init(appContext: AppContext) {
    super.init(appContext: appContext)
    delegateBridge.owner = self
    manager.delegate = delegateBridge
  }

  public func definition() -> ModuleDefinition {
    Name("SingSoundPronunciation")

    Events(
      "onInitSuccess",       // 引擎初始化成功
      "onStart",             // 评测开始
      "onStop",              // 评测停止
      "onResult",            // 评测结果 { result, isLast, requestId }
      "onError",             // 评测失败 { code, message }
      "onVolume",            // 录音音量
      "onRequestId",         // 本次评测 request_id
      "onRealTimeResult",    // 边读边评实时结果
      "onVadFrontTimeout",   // VAD 前置超时
      "onVadBackTimeout",    // VAD 后置超时
      "onRecorderWillTimeout", // 录音即将超时
      "onWarrantIdNeedUpdate"  // 授权 ID 需要更新（重新向后端签发）
    )

    // 注册在线评测引擎（appKey/secretKey 来自阿里云口语评测控制台）
    Function("initWithConfig") { (appKey: String, secretKey: String, userId: String) in
      let config = SSOralEvaluatingManagerConfig()
      config.appKey = appKey
      config.secretKey = secretKey
      config.serverTimeout = 60
      config.connectTimeout = 20
      config.allowDynamicService = true
      config.isOutputLog = false
      self.manager.register(with: config, type: .line, userId: userId)
    }

    // 配置授权 ID 与过期时间（warrantId 由服务端签发，timeout 为过期时间戳，单位秒）
    Function("setWarrantId") { (warrantId: String, timeout: String) in
      self.manager.setAuthInfoWithWarrantId(warrantId, authTimeout: timeout)
    }

    // 内置录音评测。config: { coreType, refText, rank, precision, openFeed, vadEnable, sampleRate }
    Function("start") { (config: [String: Any]) in
      let evalConfig = self.buildEvaluatingConfig(config)
      self.manager.startEvaluateOral(with: evalConfig)
    }

    // 内置录音评测并保存录音文件（便于调试回听）
    Function("startWithStorePath") { (config: [String: Any], storeWavPath: String) in
      let evalConfig = self.buildEvaluatingConfig(config)
      self.manager.startEvaluateOral(with: evalConfig, storeWavPath: storeWavPath)
    }

    // 本地音频文件评测（wav），不走麦克风
    Function("startWithWavPath") { (wavPath: String, config: [String: Any]) in
      let evalConfig = self.buildEvaluatingConfig(config)
      self.manager.startEvaluateOral(withWavPath: wavPath, config: evalConfig)
    }

    // 外部喂音频模式：不开启 SDK 录音，由 RN 侧采集音频后经 feedAudio 传输
    Function("startExternalAudio") { (config: [String: Any]) in
      let evalConfig = self.buildEvaluatingConfig(config)
      self.manager.startNoAudioStreamEvaluateOral(with: evalConfig)
    }

    // 向评测服务器传输音频数据（base64 编码的 PCM/wav 分片），仅外部喂音频模式有效
    Function("feedAudio") { (base64: String) in
      guard let data = Data(base64Encoded: base64) else {
        throw InvalidAudioDataException()
      }
      self.manager.feedAudioToEvaluate(with: data)
    }

    // 停止评测，结果经 onResult 回传
    Function("stop") {
      self.manager.stopEvaluate()
    }

    // 取消评测（无结果回调）
    Function("cancel") {
      self.manager.cancelEvaluate()
    }

    // 释放引擎
    Function("destroy") {
      self.manager.engineDealloc()
    }

    // 引擎是否已完成注册
    Function("isInitialized") {
      return self.isRegistered
    }

    OnDestroy {
      if self.manager.delegate === self.delegateBridge {
        self.manager.delegate = nil
      }
      self.manager.engineDealloc()
      self.isRegistered = false
    }
  }

  fileprivate var isRegistered = false

  fileprivate func buildEvaluatingConfig(_ raw: [String: Any]) -> SSOralEvaluatingConfig {
    let config = SSOralEvaluatingConfig()
    // coreType 优先（如 en.sent.score / en.pred.score），与后端配置保持一致
    config.coreType = raw["coreType"] as? String
    config.oralContent = raw["refText"] as? String
    config.userId = (raw["userId"] as? String) ?? "guest"
    config.rank = raw["rank"] as? UInt ?? 100
    config.precision = mapPrecision(raw["precision"] as? Int)
    config.openFeed = raw["openFeed"] as? Bool ?? false
    config.vadEnable = raw["vadEnable"] as? Bool ?? false
    config.sampleRate = raw["sampleRate"] as? Int ?? 16000
    config.channel = 1
    config.sampleBytes = 2
    config.audioType = "wav"
    config.soundIntensityEnable = true
    config.recordTimeinterval = 100
    return config
  }

  fileprivate func mapPrecision(_ value: Int?) -> EvaluatingPrecision {
    switch value {
    case 0: return .small
    case 1: return .medium
    default: return .high
    }
  }

  fileprivate func emit(_ name: String, _ body: [String: Any?] = [:]) {
    let dispatch = {
      self.sendEvent(name, body)
    }
    if Thread.isMainThread {
      dispatch()
    } else {
      DispatchQueue.main.async(execute: dispatch)
    }
  }

  fileprivate static func dictionary(from raw: NSDictionary?) -> [String: Any] {
    guard let raw else { return [:] }
    var result: [String: Any] = [:]
    for (key, value) in raw {
      guard let key = key as? String else { continue }
      if let nested = value as? NSDictionary {
        result[key] = Self.dictionary(from: nested)
      } else if let arr = value as? NSArray {
        result[key] = arr.compactMap { item -> Any? in
          if let d = item as? NSDictionary { return Self.dictionary(from: d) }
          return item
        }
      } else {
        result[key] = value
      }
    }
    return result
  }
}

// MARK: - SDK Delegate 桥接（SDK 要求 NSObject 且回调线程不定）

private final class PronunciationDelegateBridge: NSObject, SSOralEvaluatingManagerDelegate {
  weak var owner: PronunciationModule?

  func oralEvaluatingInitSuccess() {
    owner?.isRegistered = true
    owner?.emit("onInitSuccess")
  }

  func oralEvaluatingDidStart() {
    owner?.emit("onStart")
  }

  func oralEvaluatingDidStop() {
    owner?.emit("onStop")
  }

  func oralEvaluatingDidEnd(withResult result: [AnyHashable: Any]?, isLast: Bool) {
    guard let result, let owner else { return }
    let dict = PronunciationModule.dictionary(from: result as NSDictionary)
    owner.emit("onResult", [
      "result": dict,
      "isLast": isLast,
      "requestId": dict["request_id"] as? String ?? NSNull()
    ])
  }

  func oralEvaluatingDidEnd(withResult result: [AnyHashable: Any]?, requestId: String?) {
    guard let result, let owner else { return }
    let dict = PronunciationModule.dictionary(from: result as NSDictionary)
    owner.emit("onResult", [
      "result": dict,
      "isLast": true,
      "requestId": requestId ?? NSNull()
    ])
  }

  func oralEvaluatingRealTimeCallBack(_ result: [AnyHashable: Any]?) {
    guard let result, let owner else { return }
    let dict = PronunciationModule.dictionary(from: result as NSDictionary)
    owner.emit("onRealTimeResult", ["result": dict])
  }

  func oralEvaluatingDidEndError(_ error: Error?) {
    guard let error, let owner else { return }
    owner.emit("onError", [
      "code": (error as NSError).code,
      "message": error.localizedDescription
    ])
  }

  func oralEvaluatingDidEndError(_ error: Error?, requestId: String?) {
    guard let error, let owner else { return }
    owner.emit("onError", [
      "code": (error as NSError).code,
      "message": error.localizedDescription,
      "requestId": requestId ?? NSNull()
    ])
  }

  func oralEvaluatingDidUpdateVolume(_ volume: Int32) {
    owner?.emit("onVolume", ["volume": volume])
  }

  func oralEvaluatingReturnRequestId(_ request_id: String?) {
    owner?.emit("onRequestId", ["requestId": request_id ?? NSNull()])
  }

  func onWarrantIdNeedUpdate() {
    owner?.emit("onWarrantIdNeedUpdate")
  }

  func oralEvaluatingDidVADFrontTimeOut() {
    owner?.emit("onVadFrontTimeout")
  }

  func oralEvaluatingDidVADBackTimeOut() {
    owner?.emit("onVadBackTimeout")
  }

  func oralEvaluatingDidRecorderWillTimeOut() {
    owner?.emit("onRecorderWillTimeout")
  }
}

// MARK: - Exceptions

private class InvalidAudioDataException: Exception {
  override var reason: String {
    "feedAudio 收到的 base64 音频数据无法解析"
  }
}
