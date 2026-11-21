package ai.speakpath.pronunciation

import android.os.Handler
import android.os.Looper
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject

/**
 * Expo 原生模块（Android）：桥接阿里云智能语音评测（先声 SingSound）SDK。
 *
 * 与 iOS 侧模块同名（SingSoundPronunciation），JS 侧共用同一套类型与调用约定：
 * 1. initWithConfig(appKey, secretKey, userId) 注册在线评测引擎；
 * 2. setWarrantId(warrantId, timeout) 配置服务端签发的授权；
 * 3. start(...) 内置录音评测 / startExternalAudio + feedAudio 外部喂音频；
 * 4. stop() 结束评测，结果经 onResult 事件回传。
 *
 * SDK 的全部调用集中在 [SingSoundNativeClient]，便于在拿到官方 aar 后核对类名/方法名。
 */
class PronunciationModule : Module() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val client = SingSoundNativeClient(
    listener = object : SingSoundNativeClient.Listener {
      override fun onInitSuccess() = onMain { sendEvent("onInitSuccess") }
      override fun onStart() = onMain { sendEvent("onStart") }
      override fun onStop() = onMain { sendEvent("onStop") }
      override fun onResult(result: JSONObject, isLast: Boolean, requestId: String?) = onMain {
        sendEvent("onResult", mapOf("result" to result.toMap(), "isLast" to isLast, "requestId" to requestId))
      }
      override fun onError(code: Int, message: String) = onMain {
        sendEvent("onError", mapOf("code" to code, "message" to message))
      }
      override fun onVolume(volume: Int) = onMain { sendEvent("onVolume", mapOf("volume" to volume)) }
      override fun onRequestId(requestId: String) = onMain { sendEvent("onRequestId", mapOf("requestId" to requestId)) }
      override fun onRealTimeResult(result: JSONObject) = onMain {
        sendEvent("onRealTimeResult", mapOf("result" to result.toMap()))
      }
      override fun onVadFrontTimeout() = onMain { sendEvent("onVadFrontTimeout") }
      override fun onVadBackTimeout() = onMain { sendEvent("onVadBackTimeout") }
      override fun onRecorderWillTimeout() = onMain { sendEvent("onRecorderWillTimeout") }
      override fun onWarrantIdNeedUpdate() = onMain { sendEvent("onWarrantIdNeedUpdate") }
    }
  )

  override fun definition() = ModuleDefinition {
    Name("SingSoundPronunciation")

    Events(
      "onInitSuccess", "onStart", "onStop", "onResult", "onError", "onVolume",
      "onRequestId", "onRealTimeResult", "onVadFrontTimeout", "onVadBackTimeout",
      "onRecorderWillTimeout", "onWarrantIdNeedUpdate"
    )

    Function("initWithConfig") { appKey: String, secretKey: String, userId: String ->
      client.register(appKey, secretKey, userId)
    }

    Function("setWarrantId") { warrantId: String, timeout: String ->
      client.setWarrantId(warrantId, timeout)
    }

    Function("start") { config: Map<String, Any?> ->
      client.start(buildEvaluatingConfig(config), storeWavPath = null)
    }

    Function("startWithStorePath") { config: Map<String, Any?>, storeWavPath: String ->
      client.start(buildEvaluatingConfig(config), storeWavPath)
    }

    Function("startWithWavPath") { wavPath: String, config: Map<String, Any?> ->
      client.startWithWavPath(wavPath, buildEvaluatingConfig(config))
    }

    Function("startExternalAudio") { config: Map<String, Any?> ->
      client.startExternalAudio(buildEvaluatingConfig(config))
    }

    Function("feedAudio") { base64: String ->
      client.feedAudio(base64)
    }

    Function("stop") { client.stop() }

    Function("cancel") { client.cancel() }

    Function("destroy") { client.destroy() }

    Function("isInitialized") { client.isInitialized }

    OnDestroy {
      client.destroy()
    }
  }

  private fun buildEvaluatingConfig(raw: Map<String, Any?>): SingSoundNativeClient.EvaluatingConfig =
    SingSoundNativeClient.EvaluatingConfig(
      coreType = raw["coreType"] as? String,
      refText = raw["refText"] as? String,
      userId = raw["userId"] as? String ?: "guest",
      rank = (raw["rank"] as? Number)?.toLong() ?: 100L,
      precision = (raw["precision"] as? Number)?.toInt() ?: 2,
      openFeed = raw["openFeed"] as? Boolean ?: false,
      vadEnable = raw["vadEnable"] as? Boolean ?: false,
      sampleRate = (raw["sampleRate"] as? Number)?.toInt() ?: 16000
    )

  private fun onMain(body: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) body() else mainHandler.post(body)
  }

  private fun JSONObject.toMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    keys().forEach { key -> result[key] = opt(key) }
    return result
  }
}
