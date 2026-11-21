import type {
  AudioArtifact, AudioDevice, AudioService, CaptureOptions, PermissionState,
} from '@speakpath/types';

/**
 * AudioService 平台适配（规格书 §4.5 / §8.1）：
 * - Web：getUserMedia + MediaRecorder（真实录音）
 * - Native：依赖 expo-audio 由宿主注入；未注入时降级（返回 denied 提示使用文本模式）
 */

export class WebAudioService implements AudioService {
  private stream: MediaStream | null = null;
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private startedAt = 0;
  private audioEl: HTMLAudioElement | null = null;

  async requestPermission(): Promise<PermissionState> {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      return 'denied';
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach((t) => t.stop());
      return 'granted';
    } catch (e: any) {
      return e?.name === 'NotAllowedError' ? 'denied' : 'undetermined';
    }
  }

  async listInputDevices(): Promise<AudioDevice[]> {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.enumerateDevices) {
      return [];
    }
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices
      .filter((d) => d.kind === 'audioinput')
      .map((d) => ({ deviceId: d.deviceId, label: d.label || `麦克风 ${d.deviceId.slice(0, 6)}`, kind: d.kind }));
  }

  async startCapture(options: CaptureOptions = {}): Promise<void> {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      throw new Error('此环境不支持录音，请使用文本模式');
    }
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { sampleRate: options.sampleRate || 48000, channelCount: options.channelCount || 1 },
    });
    const mimeType = options.mimeType || pickMimeType();
    this.chunks = [];
    this.recorder = new MediaRecorder(this.stream, { mimeType });
    this.recorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) this.chunks.push(e.data);
    };
    this.startedAt = Date.now();
    this.recorder.start(250);
  }

  async stopCapture(): Promise<AudioArtifact> {
    if (!this.recorder) throw new Error('未在录音');
    const durationMs = Date.now() - this.startedAt;
    const stopped = new Promise<void>((resolve) => {
      this.recorder!.onstop = () => resolve();
    });
    this.recorder.stop();
    await stopped;
    const blob = new Blob(this.chunks, { type: this.recorder.mimeType || 'audio/webm' });
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.recorder = null;
    return {
      uri: URL.createObjectURL(blob),
      mimeType: blob.type,
      durationMs,
      blob,
    };
  }

  async play(uri: string): Promise<void> {
    if (typeof Audio === 'undefined') throw new Error('不支持播放');
    this.stopPlayback();
    this.audioEl = new Audio(uri);
    await this.audioEl.play();
  }

  async stopPlayback(): Promise<void> {
    if (this.audioEl) {
      this.audioEl.pause();
      this.audioEl = null;
    }
  }

  async dispose(): Promise<void> {
    if (this.recorder && this.recorder.state !== 'inactive') {
      this.recorder.stop();
    }
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stopPlayback();
  }
}

function pickMimeType(): string {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];
  if (typeof MediaRecorder === 'undefined') return 'audio/webm';
  for (const type of candidates) {
    try {
      if (MediaRecorder.isTypeSupported(type)) return type;
    } catch {
      // ignore
    }
  }
  return '';
}

/** 原生端占位实现：提示使用文本降级模式 */
export class NativeAudioServiceStub implements AudioService {
  async requestPermission(): Promise<PermissionState> {
    return 'undetermined';
  }

  async listInputDevices(): Promise<AudioDevice[]> {
    return [];
  }

  async startCapture(): Promise<void> {
    throw new Error('原生录音需注入 expo-audio 实现；当前可用文本模式继续学习');
  }

  async stopCapture(): Promise<AudioArtifact> {
    throw new Error('未在录音');
  }

  async play(): Promise<void> {
    throw new Error('原生播放需注入 expo-audio 实现');
  }

  async stopPlayback(): Promise<void> {}

  async dispose(): Promise<void> {}
}

let service: AudioService | null = null;

export function getAudioService(): AudioService {
  if (!service) {
    const isWeb = typeof window !== 'undefined' && typeof document !== 'undefined';
    service = isWeb ? new WebAudioService() : new NativeAudioServiceStub();
  }
  return service;
}

export function setAudioService(s: AudioService) {
  service = s;
}

/** 页面级远程音频实例注册表：离开页面时统一停止所有播放。 */
let trackedAudio: HTMLAudioElement[] = [];

/**
 * 播放远程音频 URL（注册到全局表，离开页面可统一停止）；
 * 播放新音频前自动停止之前的播放。onEnded 在播放结束/出错时回调。
 */
export function playTrackedAudio(url: string, onEnded?: () => void): HTMLAudioElement {
  if (typeof Audio === 'undefined') {
    throw new Error('不支持播放');
  }
  const audio = new Audio(url);
  trackedAudio.forEach((a) => a.pause());
  trackedAudio = [audio];
  const cleanup = () => {
    trackedAudio = trackedAudio.filter((a) => a !== audio);
    onEnded?.();
  };
  audio.onended = cleanup;
  audio.onerror = cleanup;
  audio.play().catch(() => cleanup());
  return audio;
}

/** 停止所有播放（音频服务 + 页面级远程音频），离开练习画面时调用。 */
export function stopAllPlayback(): void {
  getAudioService().stopPlayback().catch(() => {});
  trackedAudio.forEach((a) => a.pause());
  trackedAudio = [];
}

export {
  DashScopePronunciation,
  defaultEvalTime,
  isPronunciationSupported,
} from './dashscope-pronunciation';
export type {
  PronunciationConfig,
  PronunciationResult,
} from './dashscope-pronunciation';
export {
  NATIVE_PRON_MODULE_NAME,
  NativePronunciation,
  createPronunciationService,
  getNativePronunciationModule,
  isNativePronunciationAvailable,
  normalizeNativeResult,
} from './native-pronunciation';
export type {
  NativePronunciationConfig,
  NativePronunciationEvents,
  NativePronunciationStartConfig,
  PronunciationService,
  SingSoundNativeModule,
} from './native-pronunciation';
