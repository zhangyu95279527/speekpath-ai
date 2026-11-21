/**
 * SingSoundPronunciation 原生模块（iOS/安卓桥接）入口。
 *
 * 原生模块名与 iOS PronunciationModule.swift / 安卓 PronunciationModule.kt 中的
 * Name("SingSoundPronunciation") 一致。
 *
 * 类型、访问器与平台自适应封装统一收敛在共享包 @speakpath/audio（native-pronunciation.ts），
 * 此处仅做再导出，供应用层直接引用。
 */
export {
  NATIVE_PRON_MODULE_NAME,
  createPronunciationService,
  getNativePronunciationModule,
  isNativePronunciationAvailable,
  NativePronunciation,
  normalizeNativeResult,
} from '@speakpath/audio/src/native-pronunciation';
export type {
  NativePronunciationConfig,
  NativePronunciationEvents,
  NativePronunciationStartConfig,
  PronunciationService,
  SingSoundNativeModule,
} from '@speakpath/audio/src/native-pronunciation';
