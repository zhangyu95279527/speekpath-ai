package ai.speakpath.pronunciation

import android.util.Base64
import org.json.JSONObject

// ⚠️ 以下 import 的包名/类名为按官方 iOS 头文件（同名 API）推定的安卓包名，
// 拿到官方 aar 后请核对并修正（仅此一个文件需要改）：
//   unzip -l ssound.aar                       # 查看 classes.jar
//   unzip -p ssound.aar classes.jar > /tmp/classes.jar && jar tf /tmp/classes.jar | grep -i evaluating
// import com.singsound.SSOralEvaluatingConfig
// import com.singsound.SSOralEvaluatingManager
// import com.singsound.SSOralEvaluatingManagerConfig
// import com.singsound.SSOralEvaluatingManagerDelegate
// import com.singsound.EvaluatingPrecision
// import com.singsound.OralEvaluatingType

/**
 * 阿里云智能语音评测安卓 SDK（先声 SingSound 3.0.2）调用层。
 *
 * ⚠️ 重要：DASHSCOPE_PRON/ssound-android-3.0.2-3/ 目录只包含引擎 .so 文件，
 * 缺少 Java API 的 aar/jar（无评测管理器类），本文件在拿到完整 SDK 前无法编译。
 * 请向阿里云控制台「口语评测」项目索取完整安卓 SDK 包，将 aar 放入本模块 android/libs/ 目录，
 * 取消上方 import 注释并按实际类名修正本文件。
 *
 * 安卓 SDK 与 iOS SDK 同版本（3.0.2）API 一致，对应关系见各方法注释中的 iOS 方法名。
 */
class SingSoundNativeClient(listener: Listener) {

  interface Listener {
    fun onInitSuccess()
    fun onStart()
    fun onStop()
    fun onResult(result: JSONObject, isLast: Boolean, requestId: String?)
    fun onError(code: Int, message: String)
    fun onVolume(volume: Int)
    fun onRequestId(requestId: String)
    fun onRealTimeResult(result: JSONObject)
    fun onVadFrontTimeout()
    fun onVadBackTimeout()
    fun onRecorderWillTimeout()
    fun onWarrantIdNeedUpdate()
  }

  data class EvaluatingConfig(
    val coreType: String?,
    val refText: String?,
    val userId: String,
    val rank: Long,
    val precision: Int,   // 0: 0.1分制 1: 0.5分制 2: 1分制（默认）
    val openFeed: Boolean,
    val vadEnable: Boolean,
    val sampleRate: Int
  )

  private val manager = SSOralEvaluatingManager.shareManager()
  private lateinit var delegate: SdkDelegate
  var isInitialized: Boolean = false
    private set

  init {
    // 强引用 delegate，防止被 GC；SDK 侧可能为 weak 引用
    val delegate = SdkDelegate(listener)
    manager.delegate = delegate
    this.delegate = delegate
  }

  /** 对应 iOS - registerWithConfig:type:userId:（在线模式 type=2） */
  fun register(appKey: String, secretKey: String, userId: String) {
    val config = SSOralEvaluatingManagerConfig()
    config.appKey = appKey
    config.secretKey = secretKey
    config.serverTimeout = 60.0
    config.connectTimeout = 20.0
    config.allowDynamicService = true
    config.isOutputLog = false
    manager.registerWithConfig(config, OralEvaluatingType.Line, userId)
  }

  /** 对应 iOS - setAuthInfoWithWarrantId:AuthTimeout:（timeout 为过期时间戳，单位秒） */
  fun setWarrantId(warrantId: String, timeout: String) {
    manager.setAuthInfoWithWarrantId(warrantId, timeout)
  }

  /** 对应 iOS - startEvaluateOralWithConfig: / startEvaluateOralWithConfig:storeWavPath: */
  fun start(config: EvaluatingConfig, storeWavPath: String?) {
    val eval = buildConfig(config)
    if (storeWavPath != null) {
      manager.startEvaluateOralWithConfig(eval, storeWavPath)
    } else {
      manager.startEvaluateOralWithConfig(eval)
    }
  }

  /** 对应 iOS - startEvaluateOralWithWavPath:config:（本地音频文件评测） */
  fun startWithWavPath(wavPath: String, config: EvaluatingConfig) {
    manager.startEvaluateOralWithWavPath(wavPath, buildConfig(config))
  }

  /** 对应 iOS - startNoAudioStreamEvaluateOralWithConfig:（外部喂音频模式） */
  fun startExternalAudio(config: EvaluatingConfig) {
    manager.startNoAudioStreamEvaluateOralWithConfig(buildConfig(config))
  }

  /** 对应 iOS - feedAudioToEvaluateWithData: */
  fun feedAudio(base64: String) {
    manager.feedAudioToEvaluateWithData(Base64.decode(base64, Base64.DEFAULT))
  }

  /** 对应 iOS - stopEvaluate */
  fun stop() = manager.stopEvaluate()

  /** 对应 iOS - cancelEvaluate */
  fun cancel() = manager.cancelEvaluate()

  /** 对应 iOS - engineDealloc */
  fun destroy() {
    manager.engineDealloc()
    isInitialized = false
  }

  private fun buildConfig(raw: EvaluatingConfig): SSOralEvaluatingConfig {
    val config = SSOralEvaluatingConfig()
    config.coreType = raw.coreType            // 如 en.sent.score / en.pred.score
    config.oralContent = raw.refText
    config.userId = raw.userId
    config.rank = raw.rank
    config.precision = when (raw.precision) {
      0 -> EvaluatingPrecision.Small
      1 -> EvaluatingPrecision.Medium
      else -> EvaluatingPrecision.High
    }
    config.openFeed = raw.openFeed
    config.vadEnable = raw.vadEnable
    config.sampleRate = raw.sampleRate
    config.channel = 1
    config.sampleBytes = 2
    config.audioType = "wav"
    config.soundIntensityEnable = true
    config.recordTimeinterval = 100
    return config
  }

  /**
   * SDK 回调代理。方法名与 iOS SSOralEvaluatingManagerDelegate 一致，
   * 拿到 aar 后按实际接口名修正（重写方法需加 @Override/override）。
   */
  private inner class SdkDelegate(private val listener: Listener) {
    // : SSOralEvaluatingManagerDelegate  ← 取消注释以继承实际接口

    fun oralEvaluatingInitSuccess() {
      isInitialized = true
      listener.onInitSuccess()
    }

    fun oralEvaluatingDidStart() = listener.onStart()

    fun oralEvaluatingDidStop() = listener.onStop()

    fun oralEvaluatingDidEndWithResult(result: Any?, isLast: Boolean) {
      listener.onResult(parseSdkResult(result), isLast, null)
    }

    fun oralEvaluatingDidEndWithResult(result: Any?, requestId: String?) {
      listener.onResult(parseSdkResult(result), true, requestId)
    }

    fun oralEvaluatingRealTimeCallBack(result: Any?) {
      listener.onRealTimeResult(parseSdkResult(result))
    }

    fun oralEvaluatingDidEndError(error: Throwable?) {
      listener.onError(-1, error?.message ?: "评测失败")
    }

    fun oralEvaluatingDidUpdateVolume(volume: Int) = listener.onVolume(volume)

    fun oralEvaluatingReturnRequestId(requestId: String?) {
      requestId?.let(listener::onRequestId)
    }

    fun onWarrantIdNeedUpdate() = listener.onWarrantIdNeedUpdate()

    fun oralEvaluatingDidVADFrontTimeOut() = listener.onVadFrontTimeout()

    fun oralEvaluatingDidVADBackTimeOut() = listener.onVadBackTimeout()

    fun oralEvaluatingDidRecorderWillTimeOut() = listener.onRecorderWillTimeout()
  }
}

/** 将 SDK 结果对象（String JSON / Map / JSONObject 等形态）统一转换为 JSONObject */
private fun parseSdkResult(result: Any?): JSONObject = when (result) {
  null -> JSONObject()
  is String -> runCatching { JSONObject(result) }.getOrElse { JSONObject() }
  is Map<*, *> -> JSONObject(result)
  is JSONObject -> result
  else -> JSONObject(result.toString())
}
