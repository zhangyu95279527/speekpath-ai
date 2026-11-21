import type { AudioService } from '@speakpath/types';

/**
 * 阿里云「智能科教内容生成平台」发音评测 JS SDK 封装（Web 端）。
 *
 * 流程：服务端签发 warrantId（见 /speech/pronunciation）→ 前端加载 engine.js →
 * 初始化 EngineEvaluat → startRecord/stopRecord → 引擎内部做录音/wav/speex/ogg/WebSocket 流式 → 结果回调。
 *
 * 注意：engine.js 需从阿里云控制台「口语评测」项目下载，放到 public/sdk/engine.js。
 * 移动端（iOS/安卓）需另行桥接官方原生 SDK。
 */

export interface PronunciationConfig {
  applicationId: string;   // appid（appkey）
  userId: string;          // 与授权时 user_id 一致
  warrantId: string;       // 服务端授权返回的 warrant_id
  engineJsUrl?: string;    // engine.js 地址，默认 /sdk/engine.js
}

export interface PronunciationResult {
  overall: number;         // 总分
  accuracy: number;        // 准确度（同 pron）
  integrity: number;       // 完整度
  fluency: number;         // 流利度
  rank: number;            // 分制
  recordId?: string;
  applicationId?: string;
  details?: Array<{ char: string; score: number }>;
  [key: string]: unknown;
}

type EngineEvaluatInstance = {
  startRecord: (params: Record<string, unknown>, done?: Function, fail?: Function) => void;
  stopRecord: () => void;
  wholeFileUpload?: (evt: { target: { files: Blob[] } }, params: Record<string, unknown>) => void;
  destroy?: () => void;
};

declare global {
  interface Window {
    EngineEvaluat?: new (opts: Record<string, unknown>) => EngineEvaluatInstance;
  }
}

function loadScript(src: string): Promise<void> {
  return (async () => {
    if (typeof document === 'undefined') {
      throw new Error('仅浏览器端支持发音评测 SDK');
    }
    const w = window as unknown as Record<string, unknown>;
    if (typeof w.EngineEvaluat !== 'undefined') {
      return;
    }
    // engine.js 为 Node 目标构建的 webpack UMD 包，在无模块系统的 <script> 环境中会裸调用
    // require("path"/"fs") 与 process：注入最小 polyfill 并打补丁后执行。
    w.process = {
      argv: [], env: {}, cwd: () => '/', platform: 'browser', versions: {},
      stdout: { write: () => {} }, stderr: { write: () => {} }, exit: () => {},
    };
    const pathPolyfill = {
      sep: '/',
      normalize: (p: string) => p,
      join: (...parts: string[]) => parts.filter(Boolean).join('/'),
      resolve: (...parts: string[]) => '/' + parts.filter(Boolean).join('/'),
      relative: () => '',
      dirname: (p: string) => (p.includes('/') ? (p.slice(0, p.lastIndexOf('/')) || '/') : '.'),
      basename: (p: string) => p.slice(p.lastIndexOf('/') + 1),
      extname: () => '',
      isAbsolute: () => false,
    };
    w.require = (m: string) => {
      if (m === 'path') return pathPolyfill;
      if (m === 'fs') {
        return { readFileSync: () => new Uint8Array(), existsSync: () => false, statSync: () => ({}) };
      }
      return {};
    };
    const response = await fetch(src);
    if (!response.ok) {
      throw new Error(`加载 engine.js 失败：HTTP ${response.status}`);
    }
    const text = await response.text();
    // Emscripten 运行时在浏览器环境可能读取 process.argv：补丁为安全访问
    const patched = text.replace('process.argv.slice(2)', '(process.argv||[]).slice(2)');
    // 顶层函数体在全局作用域执行，等价于 <script> 注入
    new Function(patched)();
  })().catch((e) => {
    throw e instanceof Error ? e : new Error(String(e));
  });
}

export class DashScopePronunciation {
  private engine: EngineEvaluatInstance | null = null;
  private warrantId = '';
  private micReadyPromise: Promise<void> | null = null;
  private resolveMic: (() => void) | null = null;
  private rejectMic: ((e: Error) => void) | null = null;

  onInit?: () => void;
  onMicReady?: () => void;
  onDone?: (result: PronunciationResult) => void;
  onFail?: (message: string) => void;
  onVolume?: (volume: number) => void;
  onNotSupport?: () => void;

  /** 加载 engine.js 并初始化引擎。 */
  async init(config: PronunciationConfig): Promise<void> {
    if (typeof window === 'undefined') {
      throw new Error('仅浏览器端支持发音评测');
    }
    if (!window.EngineEvaluat) {
      await loadScript(config.engineJsUrl || '/sdk/engine.js');
    }
    if (!window.EngineEvaluat) {
      throw new Error('EngineEvaluat 未加载，请确认 engine.js 已放入 public/sdk/');
    }
    this.warrantId = config.warrantId;
    this.micReadyPromise = new Promise<void>((resolve, reject) => {
      this.resolveMic = resolve;
      this.rejectMic = reject;
    });
    this.engine = new window.EngineEvaluat({
      applicationId: config.applicationId,
      userId: config.userId,
      warrantId: config.warrantId,
      engineFirstInitDone: () => this.onInit?.call(this),
      // 引擎在 startRecord 时直接用 getUserMedia 异步返回的 stream 建音频图，
      // 必须等授权回调后才能开始录音，否则 stream 为 null 报 createMediaStreamSource 错误。
      micAllowCallback: () => {
        this.resolveMic?.();
        this.resolveMic = null;
        this.onMicReady?.call(this);
      },
      micForbidCallback: () => {
        const err = new Error('麦克风权限被拒绝');
        this.rejectMic?.(err);
        this.rejectMic = null;
        this.onFail?.('麦克风权限被拒绝');
      },
      engineBackResultDone: (msg: string) => {
        try {
          this.onDone?.(JSON.parse(msg));
        } catch (e) {
          this.onFail?.('解析评测结果失败');
        }
      },
      engineBackResultFail: (msg: string) => this.onFail?.(msg),
      micVolumeCallback: (volume: number) => this.onVolume?.(volume),
      JSSDKNotSupport: () => this.onNotSupport?.(),
      noNetwork: () => this.onFail?.('暂无网络'),
    });
  }

  /** 开始录音评测。params: { coreType, refText, precision?, evalTime? } */
  async startRecord(params: { coreType: string; refText: string; precision?: number; evalTime?: number }): Promise<void> {
    if (!this.engine) {
      throw new Error('请先调用 init()');
    }
    if (this.micReadyPromise) {
      await Promise.race([
        this.micReadyPromise,
        new Promise<void>((_, reject) =>
          setTimeout(() => reject(new Error('等待麦克风授权超时')), 15000)),
      ]);
    }
    this.engine.startRecord({ ...params, warrantId: this.warrantId });
  }

  stopRecord(): void {
    this.engine?.stopRecord();
  }

  /**
   * 整段音频文件评测（服务端已授权 warrant）：
   * 传入 16kHz 16bit 单声道 PCM 裸数据 Blob（引擎内部转 ogg 后走 WebSocket 评测），
   * 结果通过 onDone 回调返回。
   */
  evaluateFile(params: { coreType: string; refText: string; warrantId?: string }, pcmBlob: Blob): void {
    if (!this.engine?.wholeFileUpload) {
      this.onFail?.('当前引擎不支持文件评测');
      return;
    }
    this.engine.wholeFileUpload({ target: { files: [pcmBlob] } }, { ...params });
  }

  destroy(): void {
    this.engine?.destroy?.();
    this.engine = null;
  }
}

/** 建议录音时长（ms）：2000 + 词数 * 600 + 1000 */
export function defaultEvalTime(refText: string): number {
  return 2000 + refText.trim().split(/\s+/).length * 600 + 1000;
}

/** Web 端可用性探测。 */
export function isPronunciationSupported(): boolean {
  return typeof window !== 'undefined' && typeof document !== 'undefined';
}
