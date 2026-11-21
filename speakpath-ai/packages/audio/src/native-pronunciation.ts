import { requireOptionalNativeModule, type EventSubscription } from 'expo-modules-core';
import {
  DashScopePronunciation,
  isPronunciationSupported,
  type PronunciationConfig,
  type PronunciationResult,
} from './dashscope-pronunciation';

/**
 * 原生发音评测适配层（iOS/安卓 SingSound SDK 桥接）。
 *
 * 与 Web 端 DashScopePronunciation 保持同一调用约定（init / startRecord / stopRecord / destroy），
 * 通过 createPronunciationService() 自动选择：原生模块可用 → 原生；否则降级 Web JS SDK。
 *
 * 原生模块实现见 apps/mobile/modules/pronunciation/（iOS Swift + 安卓 Kotlin）。
 */

export const NATIVE_PRON_MODULE_NAME = 'SingSoundPronunciation';

/** 原生模块初始化参数（Web 端仅需 applicationId + warrantId，原生还需 appKey/secretKey） */
export interface NativePronunciationConfig extends PronunciationConfig {
  appKey: string;        // 阿里云口语评测控制台 appKey
  secretKey: string;     // 对应 secretKey
  warrantTimeout?: string; // 授权过期时间戳（秒），不传时由服务端返回的过期时间决定
}

/** 原生评测请求参数 */
export interface NativePronunciationStartConfig {
  coreType: string;
  refText: string;
  userId?: string;
  rank?: number;
  precision?: number;    // 0: 0.1分制 1: 0.5分制 2: 1分制（默认 2）
  openFeed?: boolean;
  vadEnable?: boolean;
  sampleRate?: number;
}

export interface NativePronunciationEvents {
  onInitSuccess: [];
  onStart: [];
  onStop: [];
  onResult: [{ result: Record<string, unknown>; isLast: boolean; requestId: string | null }];
  onError: [{ code: number; message: string; requestId?: string | null }];
  onVolume: [{ volume: number }];
  onRequestId: [{ requestId: string }];
  onRealTimeResult: [{ result: Record<string, unknown> }];
  onVadFrontTimeout: [];
  onVadBackTimeout: [];
  onRecorderWillTimeout: [];
  onWarrantIdNeedUpdate: [];
}

/** 原生模块暴露的函数（与两端 ModuleDefinition 保持一致） */
export interface SingSoundNativeModule {
  initWithConfig(appKey: string, secretKey: string, userId: string): void;
  setWarrantId(warrantId: string, timeout: string): void;
  start(config: NativePronunciationStartConfig): void;
  startWithStorePath(config: NativePronunciationStartConfig, storeWavPath: string): void;
  startWithWavPath(wavPath: string, config: NativePronunciationStartConfig): void;
  startExternalAudio(config: NativePronunciationStartConfig): void;
  feedAudio(base64: string): void;
  stop(): void;
  cancel(): void;
  destroy(): void;
  isInitialized(): boolean;
  addListener<EventName extends keyof NativePronunciationEvents>(
    eventName: EventName,
    listener: (...args: NativePronunciationEvents[EventName]) => void
  ): EventSubscription;
  removeListeners(count: number): void;
}

export function getNativePronunciationModule(): SingSoundNativeModule | null {
  try {
    return requireOptionalNativeModule<SingSoundNativeModule>(NATIVE_PRON_MODULE_NAME);
  } catch {
    return null;
  }
}

export function isNativePronunciationAvailable(): boolean {
  return getNativePronunciationModule() != null;
}

/** 统一的发音评测服务接口（原生与 Web 一致） */
export interface PronunciationService {
  onInit?: () => void;
  onDone?: (result: PronunciationResult) => void;
  onFail?: (message: string) => void;
  onVolume?: (volume: number) => void;
  onNotSupport?: () => void;
  /** 原生专属：warrantId 需更新（重新调用服务端 /speech/pronunciation 签发后 setWarrantId） */
  onWarrantUpdate?: () => void;
  init(config: NativePronunciationConfig): Promise<void>;
  setWarrantId?(warrantId: string, timeout?: string): void;
  /** Web 端异步等待麦克风就绪；原生端同步启动。 */
  startRecord(params: { coreType: string; refText: string; precision?: number; evalTime?: number }): void | Promise<void>;
  stopRecord(): void;
  cancel?(): void;
  destroy(): void;
}

/** 原生桥接实现：封装 SingSoundPronunciation Expo 模块 + 事件订阅 */
export class NativePronunciation implements PronunciationService {
  private module: SingSoundNativeModule | null;
  private subscriptions: EventSubscription[] = [];
  private userId = 'guest';

  onInit?: () => void;
  onDone?: (result: PronunciationResult) => void;
  onFail?: (message: string) => void;
  onVolume?: (volume: number) => void;
  onNotSupport?: () => void;
  onWarrantUpdate?: () => void;

  constructor(module?: SingSoundNativeModule | null) {
    this.module = module ?? getNativePronunciationModule();
  }

  get isAvailable(): boolean {
    return this.module != null;
  }

  async init(config: NativePronunciationConfig): Promise<void> {
    if (!this.module) {
      throw new Error('原生发音评测模块未链接：请执行 expo prebuild 后重新构建原生应用');
    }
    this.userId = config.userId || 'guest';
    this.subscribe();
    this.module.initWithConfig(config.appKey, config.secretKey, this.userId);
    // 未传过期时间时按服务端 warrantId 默认有效期 7200 秒推算
    const timeout = config.warrantTimeout ?? String(Math.floor(Date.now() / 1000) + 7200);
    this.module.setWarrantId(config.warrantId, timeout);
  }

  /** warrantId 更新（配合 onWarrantUpdate 使用） */
  setWarrantId(warrantId: string, timeout?: string): void {
    const t = timeout ?? String(Math.floor(Date.now() / 1000) + 7200);
    this.module?.setWarrantId(warrantId, t);
  }

  startRecord(params: { coreType: string; refText: string; precision?: number; evalTime?: number }): void {
    if (!this.module) {
      throw new Error('请先调用 init()');
    }
    this.module.start({
      coreType: params.coreType,
      refText: params.refText,
      userId: this.userId,
      rank: 100,
      precision: params.precision ?? 2,
      openFeed: false,
      vadEnable: false,
    });
  }

  stopRecord(): void {
    this.module?.stop();
  }

  cancel(): void {
    this.module?.cancel();
  }

  destroy(): void {
    for (const sub of this.subscriptions) sub.remove();
    this.subscriptions = [];
    this.module?.destroy();
  }

  private subscribe(): void {
    const m = this.module;
    if (!m || this.subscriptions.length > 0) return;
    this.subscriptions = [
      m.addListener('onInitSuccess', () => this.onInit?.()),
      m.addListener('onResult', ({ result, isLast }) => {
        if (isLast) this.onDone?.(normalizeNativeResult(result));
      }),
      m.addListener('onError', ({ message }) => this.onFail?.(`评测失败：${message}`)),
      m.addListener('onVolume', ({ volume }) => this.onVolume?.(volume)),
      m.addListener('onWarrantIdNeedUpdate', () => this.onWarrantUpdate?.()),
    ];
  }
}

/** 原生 SDK 结果字段归一化为 PronunciationResult（accuracy 兼容 pron/pronunciation 命名） */
export function normalizeNativeResult(raw: Record<string, unknown>): PronunciationResult {
  const num = (v: unknown): number => {
    const n = typeof v === 'string' ? Number(v) : (v as number);
    return Number.isFinite(n) ? n : 0;
  };
  const accuracy = raw.accuracy ?? raw.pron ?? raw.pronunciation;
  const words = Array.isArray(raw.words) ? raw.words : undefined;
  const details = words?.map((w: Record<string, unknown>) => ({
    char: String(w.word ?? w.char ?? ''),
    score: num(w.pron ?? w.score),
  }));
  return {
    ...raw, // 原始字段兜底，下方归一化字段优先
    overall: num(raw.overall),
    accuracy: num(accuracy),
    integrity: num(raw.integrity),
    fluency: num(raw.fluency),
    rank: num(raw.rank) || 100,
    recordId: raw.recordId as string | undefined,
    applicationId: raw.applicationId as string | undefined,
    details,
  };
}

/** 平台自适应工厂：原生可用 → 原生桥接；Web → JS SDK；否则 null */
export function createPronunciationService(): PronunciationService | null {
  const native = getNativePronunciationModule();
  if (native) {
    return new NativePronunciation(native);
  }
  if (isPronunciationSupported()) {
    return new DashScopePronunciation() as unknown as PronunciationService;
  }
  return null;
}
