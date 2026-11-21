import React, { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { FlatList, KeyboardAvoidingView, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@speakpath/auth';
import { api } from '@speakpath/api';
import { DashScopePronunciation, getAudioService, playTrackedAudio, stopAllPlayback } from '@speakpath/audio';
import { initialSpeaking, speakingReducer } from '@speakpath/speaking';
import type { FeedbackDto, SpeakingEvent } from '@speakpath/types';
import { Badge, Button, Card, ErrorBanner, Input, Screen, colors, font, spacing } from '@speakpath/ui';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  /** 用户语音消息的预签名音频 URL：点击气泡回放录音 */
  audioUrl?: string;
  /** 发音反馈建议的合成语音：点击只播放"建议"内容 */
  feedbackAudioBase64?: string;
  /** 发音反馈建议原文：历史反馈缺少音频时用于按需合成 */
  feedbackText?: string;
  /** 发音反馈的正确整句文本：缺少音频时按需合成 */
  feedbackSentenceText?: string;
  /** 发音反馈的正确整句语音：后端随反馈返回 */
  feedbackSentenceAudioBase64?: string;
  /** AI 回复语音：点击气泡回放（历史回复无音频时按文本重新合成） */
  audioBase64?: string;
  audioFormat?: string;
  replayable?: boolean;
}

/** base64 音频 → data URL。 */
function audioDataUri(base64: string, format?: string) {
  const mime = format === 'mp3' ? 'audio/mpeg' : `audio/${format || 'mp3'}`;
  return `data:${mime};base64,${base64}`;
}

/** 播放语音：有 audioBase64 用音频服务播放；否则（mock 环境）降级到浏览器原生语音合成。 */
function playAudio(audioBase64?: string, audioFormat?: string, text?: string) {
  if (audioBase64) {
    const mime = audioFormat === 'mp3' ? 'audio/mpeg' : `audio/${audioFormat || 'mp3'}`;
    getAudioService().play(`data:${mime};base64,${audioBase64}`).catch(() => {});
  } else if (text && typeof window !== 'undefined') {
    const synth = (window as any).speechSynthesis;
    const Utterance = (window as any).SpeechSynthesisUtterance;
    if (synth && Utterance) {
      const u = new Utterance(text);
      u.lang = 'en-US';
      synth.speak(u);
    }
  }
}

/** 播放远程音频 URL（预签名地址；注册到全局播放表，离开画面统一停止）。 */
function playUrl(url: string) {
  if (typeof window === 'undefined' || !url) return;
  try {
    playTrackedAudio(url);
  } catch {
    // ignore
  }
}

/** Blob → base64（去除 data URL 前缀）。 */
function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || '');
      resolve(result.includes(',') ? result.split(',')[1] : result);
    };
    reader.onerror = () => reject(new Error('read failed'));
    reader.readAsDataURL(blob);
  });
}

/** 录音 Blob（webm/m4a）→ 16kHz 16bit 单声道 PCM 裸数据 Blob（发音评测引擎要求的输入格式）。 */
async function blobToPcm16k(blob: Blob): Promise<Blob> {
  const buf = await blob.arrayBuffer();
  const Ctx = (window as any).AudioContext || (window as any).webkitAudioContext;
  const ctx = new Ctx();
  try {
    const audioBuffer = await ctx.decodeAudioData(buf.slice(0));
    const offline = new OfflineAudioContext(1, Math.ceil(audioBuffer.duration * 16000), 16000);
    const src = offline.createBufferSource();
    src.buffer = audioBuffer;
    src.connect(offline.destination);
    src.start();
    const rendered = await offline.startRendering();
    const ch = rendered.getChannelData(0);
    const pcm = new Int16Array(ch.length);
    for (let i = 0; i < ch.length; i++) {
      const s = Math.max(-1, Math.min(1, ch[i]));
      pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
    return new Blob([pcm.buffer], { type: 'application/octet-stream' });
  } finally {
    ctx.close();
  }
}

/** 发音反馈气泡文本（分数全为 0 时隐藏分数行）。 */
function pronunciationFeedbackText(p: {
  overall?: number;
  accuracy?: number;
  fluency?: number;
  integrity?: number;
  lowScoreWords?: Array<{ word: string; score: number }>;
  suggestions?: string[];
  sentenceText?: string;
}): string {
  const low = (p.lowScoreWords || []).map((w) => `${w.word}(${w.score}分)`).join('、');
  const tips = (p.suggestions || []).join(' ');
  const hasScores = [p.overall, p.accuracy, p.fluency, p.integrity].some((s) => Number(s || 0) > 0);
  const scoreText = `总分 ${p.overall}｜准确度 ${p.accuracy}｜流利度 ${p.fluency}｜完整度 ${p.integrity}`;
  return (hasScores ? `🎙 发音反馈：${scoreText}` : '🎙 发音反馈')
    + (low ? `\n注意单词：${low}` : '')
    + (tips ? `\n建议：${tips}` : '');
}

/**
 * AI 口语陪练房间：REST 请求/响应模式（无 WebSocket），文字降级模式。
 * 开场白随历史轮次恢复；结束会话后查看课后反馈。
 */
export default function SpeakingRoom() {
  const { sessionId } = useLocalSearchParams<{ sessionId: string }>();
  const { accessToken } = useAuth();
  const [ctx, dispatch] = useReducer(speakingReducer, initialSpeaking);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [feedback, setFeedback] = useState<FeedbackDto | null>(null);
  const [error, setError] = useState('');
  const [assistantState, setAssistantState] = useState('idle');
  const [recording, setRecording] = useState(false);
  const [quotaRemaining, setQuotaRemaining] = useState<number | null>(null);
  const [openingReady, setOpeningReady] = useState(false);
  const [openingTimedOut, setOpeningTimedOut] = useState(false);
  const [playingFeedbackId, setPlayingFeedbackId] = useState<string | null>(null);
  const [feedbackLoadingId, setFeedbackLoadingId] = useState<string | null>(null);
  const [playingMessageId, setPlayingMessageId] = useState<string | null>(null);
  const [readOnly, setReadOnly] = useState(false);
  const recorderRef = useRef<any>(null);
  const chunksRef = useRef<Blob[]>([]);
  const micStartTsRef = useRef(0);
  const openingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastUserTurnIdRef = useRef('');
  const lastUserMsgIdRef = useRef('');
  const lastAssistantMsgIdRef = useRef('');
  const listRef = useRef<FlatList<Message> | null>(null);

  const connect = useCallback(() => {
    if (!sessionId || !accessToken) return;
    // 会话已由入口页创建：按状态机从 creating_session 进入连接阶段
    dispatch({ type: 'TRANSITION', target: 'creating_session' });
    dispatch({ type: 'TRANSITION', target: 'connecting' });

    const markConnected = () => {
      dispatch({ type: 'TRANSITION', target: 'connected' });
      // 开场白由历史轮次恢复；超时兜底（如历史尚未落库）
      openingTimerRef.current = setTimeout(() => setOpeningTimedOut(true), 60000);
    };

    // 额度检查：今日额度用完则进入只读回看模式（仅查看/回听历史对话）
    api.get<{ remainingMinutes?: number }>('/speaking/quota')
      .then((q) => {
        const remaining = typeof q.data?.remainingMinutes === 'number' ? q.data.remainingMinutes : null;
        setQuotaRemaining(remaining);
        if (remaining !== null && remaining <= 0) {
          setReadOnly(true);
          return;
        }
        markConnected();
      })
      .catch(() => markConnected());
  }, [sessionId, accessToken]);

  const handleEvent = (msg: SpeakingEvent) => {
    switch (msg.type) {
      case 'session.started':
        setError('');
        break;
      case 'assistant.state':
        setAssistantState(msg.payload.state || 'idle');
        break;
      case 'transcript.partial':
        setMessages((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === 'assistant' && last.id === 'partial') {
            return [...prev.slice(0, -1), { id: 'partial', role: 'assistant', text: msg.payload.text }];
          }
          return [...prev, { id: 'partial', role: 'assistant', text: msg.payload.text }];
        });
        break;
      case 'transcript.final':
        lastAssistantMsgIdRef.current = msg.eventId;
        setMessages((prev) => [
          ...prev.filter((m) => m.id !== 'partial'),
          { id: msg.eventId, role: 'assistant', text: msg.payload.text, replayable: true },
        ]);
        break;
      case 'correction': {
        const p = msg.payload;
        const correctionText = `纠错：${p.original} → ${p.corrected}`;
        setMessages((prev) => [...prev, { id: msg.eventId, role: 'assistant', text: correctionText }]);
        break;
      }
      case 'audio.ready': {
        const p = msg.payload;
        playAudio(p.audioBase64, p.audioFormat, p.spokenText);
        break;
      }
      case 'topic_relevance': {
        const p = msg.payload;
        if (p.onTopic === false) {
          const tip = p.guidance || `注意：你似乎偏离了话题「${p.topic}」`;
          setMessages((prev) => [...prev, { id: msg.eventId, role: 'assistant', text: `⚠️ ${tip}` }]);
        }
        break;
      }
      case 'turn.completed':
        if (msg.payload.userTurnId) {
          lastUserTurnIdRef.current = String(msg.payload.userTurnId);
        }
        if (msg.payload.userAudioUrl && lastUserMsgIdRef.current) {
          // 用户语音消息：附上可点击回放的音频 URL
          const msgId = lastUserMsgIdRef.current;
          setMessages((prev) => prev.map((m) => (m.id === msgId ? { ...m, audioUrl: String(msg.payload.userAudioUrl) } : m)));
        }
        // AI 回复语音随响应返回：挂到对应气泡并自动播放，之后可点击气泡重播
        if (msg.payload.audioBase64) {
          const base64 = String(msg.payload.audioBase64);
          const format = String(msg.payload.audioFormat || 'mp3');
          const msgId = lastAssistantMsgIdRef.current;
          if (msgId) {
            setMessages((prev) => prev.map((m) => (m.id === msgId
              ? { ...m, audioBase64: base64, audioFormat: format, replayable: true } : m)));
          }
          stopAllPlayback();
          setPlayingFeedbackId(null);
          if (msgId) {
            playTrackedAudio(audioDataUri(base64, format), () => setPlayingMessageId(null));
            setPlayingMessageId(msgId);
          } else {
            playAudio(base64, format, String(msg.payload.spokenText || ''));
          }
        }
        break;
      case 'pronunciation': {
        // 发音评测反馈：分数 + 低分词 + 建议（正确整句语音先播，建议语音后播）
        setMessages((prev) => [...prev, {
          id: msg.eventId,
          role: 'assistant',
          text: pronunciationFeedbackText(msg.payload),
          feedbackAudioBase64: msg.payload.suggestionsAudioBase64 || undefined,
          feedbackText: (msg.payload.suggestions || []).join('。'),
          feedbackSentenceText: msg.payload.sentenceText || undefined,
          feedbackSentenceAudioBase64: msg.payload.sentenceAudioBase64 || undefined,
        }]);
        break;
      }
      case 'assistant.opening': {
        // AI 开场白：首次进入时自动播放，之后可点击气泡重播
        const p = msg.payload;
        const msgId = `opening-${p.turnId}`;
        setOpeningReady(true);
        setMessages((prev) => (prev.some((m) => m.id === msgId)
          ? prev
          : [...prev, {
            id: msgId, role: 'assistant', text: p.text, replayable: true,
            audioBase64: p.audioBase64 || undefined,
            audioFormat: p.audioFormat || undefined,
            audioUrl: p.audioUrl || undefined,
          }]));
        stopAllPlayback();
        if (p.audioBase64) {
          playTrackedAudio(audioDataUri(String(p.audioBase64), String(p.audioFormat || 'mp3')),
            () => setPlayingMessageId(null));
          setPlayingMessageId(msgId);
        } else if (p.audioUrl) {
          playTrackedAudio(String(p.audioUrl), () => setPlayingMessageId(null));
          setPlayingMessageId(msgId);
        }
        break;
      }
      case 'session.warning':
        setError(`${msg.payload.code}: ${msg.payload.message || ''}`);
        break;
      case 'session.ended':
        setError('');
        break;
      default:
        break;
    }
  };

  /** 发送用户消息（文字/语音/发音评测），同步处理返回的事件。 */
  const postMessage = async (payload: Record<string, unknown>) => {
    if (!sessionId) return;
    try {
      const res = await api.post<{ events?: SpeakingEvent[] }>(
        `/speaking/sessions/${sessionId}/messages`, payload);
      (res.data?.events || []).forEach(handleEvent);
    } catch (e: any) {
      setError(e?.message || '发送失败，请重试');
      dispatch({ type: 'TRANSITION', target: 'connected' });
    }
  };

  useEffect(() => {
    connect();
    return () => {
      if (openingTimerRef.current) clearTimeout(openingTimerRef.current);
    };
  }, [connect]);

  // 离开口语练习画面时停止所有播放
  useEffect(() => () => {
    stopAllPlayback();
  }, []);

  // 页面失焦（返回、切换标签、跳转其他页面）时立即停止历史对话与发音反馈的音频
  useFocusEffect(
    useCallback(() => () => {
      if (openingTimerRef.current) clearTimeout(openingTimerRef.current);
      stopAllPlayback();
      setPlayingFeedbackId(null);
      setPlayingMessageId(null);
    }, [])
  );

  // 续接上次对话：进入房间先恢复历史消息列表（开场白用同一 id，与 WS 重放去重）
  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    api.get<Array<{ turnId: string; sequenceNo: number; role: string; transcript: string; audioUrl?: string; metadata?: { source?: string; pronunciation?: Parameters<typeof pronunciationFeedbackText>[0] } }>>(
      `/speaking/sessions/${sessionId}/turns`)
      .then((envelope) => {
        if (cancelled) return;
        const items: Message[] = [];
        for (const t of envelope.data || []) {
          if (t.transcript) {
            items.push({
              id: t.metadata?.source === 'opening' ? `opening-${t.turnId}` : `turn-${t.turnId}`,
              role: (t.role === 'USER' ? 'user' : 'assistant') as 'user' | 'assistant',
              text: t.transcript,
              audioUrl: t.audioUrl || undefined,
              replayable: t.role !== 'USER',
            });
          }
          // 历史语音轮次的发音反馈：从 metadata 恢复反馈气泡
          if (t.role === 'USER' && t.metadata?.pronunciation) {
            items.push({
              id: `pron-${t.turnId}`,
              role: 'assistant',
              text: pronunciationFeedbackText(t.metadata.pronunciation),
              feedbackText: (t.metadata.pronunciation.suggestions || []).join('。'),
              feedbackSentenceText: t.metadata.pronunciation.sentenceText || undefined,
            });
          }
        }
        if (items.length === 0) return;
        // 仅在没有历史聊天记录（全新会话）时自动播放开场白
        const opening = items.find((m) => m.id.startsWith('opening-') && m.audioUrl);
        const hasChatHistory = items.some((m) => !m.id.startsWith('opening-'));
        if (opening?.audioUrl && !hasChatHistory && sessionId) {
          setTimeout(() => {
            if (cancelled) return;
            stopAllPlayback();
            playTrackedAudio(opening.audioUrl!, () => setPlayingMessageId(null));
            setPlayingMessageId(opening.id);
          }, 300);
        }
        // 续接的对话已有轮次（含无开场白的旧会话）：无需等待开场白事件，立即解锁输入
        setOpeningReady(true);
        setMessages((prev) => {
          const existing = new Set(prev.map((m) => m.id));
          return [...items.filter((m) => !existing.has(m.id)), ...prev];
        });
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  const send = () => {
    const text = input.trim();
    if (!text || readOnly) return;
    setMessages((prev) => [...prev, { id: `u-${Date.now()}`, role: 'user', text }]);
    setInput('');
    postMessage({ type: 'user.text', text });
  };

  /** 开始录音（Web MediaRecorder）。 */
  const startRecording = async () => {
    const MediaRecorderCtor = (window as any).MediaRecorder;
    if (!MediaRecorderCtor || !navigator.mediaDevices?.getUserMedia) {
      setError('当前环境不支持录音，请使用文字输入');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mime = MediaRecorderCtor.isTypeSupported && MediaRecorderCtor.isTypeSupported('audio/webm')
        ? 'audio/webm' : '';
      const rec = new MediaRecorderCtor(stream, mime ? { mimeType: mime } : undefined);
      chunksRef.current = [];
      rec.ondataavailable = (e: any) => {
        if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
      };
      rec.onstop = async () => {
        stream.getTracks().forEach((t: any) => t.stop());
        const blobType = rec.mimeType || 'audio/webm';
        const blob = new Blob(chunksRef.current, { type: blobType });
        const durationMs = Date.now() - micStartTsRef.current;
        if (blob.size === 0) return;
        const msgId = `u-${Date.now()}`;
        lastUserMsgIdRef.current = msgId;
        setMessages((prev) => [...prev, { id: msgId, role: 'user', text: '🎤 语音消息' }]);
        try {
          const base64 = await blobToBase64(blob);
          const envelope = await api.post<{ objectKey: string }>('/media/upload', {
            audioBase64: base64,
            fileName: `recording-${Date.now()}.${blobType.includes('webm') ? 'webm' : 'm4a'}`,
            contentType: blobType,
          });
          await postMessage({
            type: 'user.audio',
            audioObjectKey: envelope.data.objectKey,
            audioFormat: blobType.includes('webm') ? 'webm' : 'mp4',
            durationMs,
          });
          // 发音评测（异步，失败不打断对话）
          evaluatePronunciation(blob, envelope.data.objectKey);
        } catch (e: any) {
          setError(e?.message || '录音上传失败');
        }
      };
      rec.start();
      recorderRef.current = rec;
      micStartTsRef.current = Date.now();
      setRecording(true);
      setError('');
    } catch {
      setError('无法访问麦克风，请在浏览器设置中允许后重试');
    }
  };

  const stopRecording = () => {
    try {
      recorderRef.current?.stop();
    } catch {
      // ignore
    }
    recorderRef.current = null;
    setRecording(false);
  };

  /**
   * 发音评测：服务端签发 warrant（无参考文本时用 ASR 转写做 refText），
   * 客户端把录音转 16kHz PCM 交给评测引擎打分，结果经 WS 回传 → 服务端推送发音反馈事件。
   * 评测失败静默跳过，不打断对话。
   */
  const evaluatePronunciation = async (blob: Blob, objectKey: string) => {
    try {
      if (typeof window === 'undefined') return;
      const pr = await api.post<{ result?: { warrantId: string; appid: string; userId: string; coreType?: string; refText?: string } }>(
        '/speech/pronunciation', { audioObjectKey: objectKey });
      const res = pr.data?.result;
      if (!res?.warrantId || !res?.appid) return; // mock 或未配置评测服务
      const pcm = await blobToPcm16k(blob);
      const engine = new DashScopePronunciation();
      await new Promise<void>((resolve) => {
        const finish = () => {
          engine.destroy();
          resolve();
        };
        engine.onDone = (result) => {
          postMessage({
            type: 'user.pronunciation',
            turnId: lastUserTurnIdRef.current,
            result,
          });
          finish();
        };
        engine.onFail = finish;
        engine.init({
          applicationId: res.appid,
          userId: res.userId,
          warrantId: res.warrantId,
        }).then(() => {
          engine.evaluateFile({
            coreType: res.coreType || 'en.sent.score',
            refText: res.refText || '',
            warrantId: res.warrantId,
          }, pcm);
        }).catch(finish);
      });
    } catch {
      // 发音评测失败不打断对话流程
    }
  };

  /** 建议语音播放/停止切换：先播正确整句发音，再播建议；历史反馈缺音频时按文本合成。 */
  const toggleFeedbackAudio = async (item: Message) => {
    const msgId = item.id;
    if (playingFeedbackId === msgId) {
      stopAllPlayback();
      setPlayingFeedbackId(null);
      return;
    }
    if (feedbackLoadingId) return;
    stopAllPlayback();
    setPlayingMessageId(null);
    const finish = () => setPlayingFeedbackId(null);
    const sources: string[] = [];

    // 1) 正确整句语音：优先用后端返回的音频，历史反馈缺失时按整句文本合成
    let sentence = item.feedbackSentenceAudioBase64;
    if (!sentence && (item.feedbackSentenceText || '').trim()) {
      setFeedbackLoadingId(msgId);
      try {
        const env = await api.post<{ audioBase64?: string; format?: string }>('/speech/tts', {
          text: item.feedbackSentenceText,
        });
        sentence = env.data?.audioBase64;
        if (sentence) {
          setMessages((prev) => prev.map((m) => (m.id === msgId
            ? { ...m, feedbackSentenceAudioBase64: sentence } : m)));
        }
      } catch (e: any) {
        setError(e?.message || '正确发音语音生成失败');
      } finally {
        setFeedbackLoadingId(null);
      }
    }
    if (sentence) sources.push(audioDataUri(sentence, 'mp3'));

    // 2) 建议语音：缺失时按建议文本合成（中英混合音色）
    let suggestion = item.feedbackAudioBase64;
    if (!suggestion) {
      const text = (item.feedbackText || '').trim();
      if (text) {
        setFeedbackLoadingId(msgId);
        try {
          const env = await api.post<{ audioBase64?: string; format?: string }>('/speech/tts', {
            text, voice: 'loongbella_v3.1',
          });
          suggestion = env.data?.audioBase64;
          if (suggestion) {
            setMessages((prev) => prev.map((m) => (m.id === msgId
              ? { ...m, feedbackAudioBase64: suggestion } : m)));
          }
        } catch (e: any) {
          setError(e?.message || '建议语音播放失败');
        } finally {
          setFeedbackLoadingId(null);
        }
      }
    }
    if (suggestion) sources.push(audioDataUri(suggestion, 'mp3'));

    if (sources.length === 0) return;
    setPlayingFeedbackId(msgId);
    let idx = 0;
    const playNext = () => {
      if (idx >= sources.length) {
        finish();
        return;
      }
      try {
        playTrackedAudio(sources[idx], () => {
          idx += 1;
          playNext();
        });
      } catch {
        finish();
      }
    };
    playNext();
  };

  /** 点击 AI 回复气泡播放/停止：优先使用回复音频，历史回复按文本重新合成。 */
  const toggleMessageAudio = async (item: Message) => {
    if (playingMessageId === item.id) {
      stopAllPlayback();
      setPlayingMessageId(null);
      return;
    }
    stopAllPlayback();
    setPlayingFeedbackId(null);
    const finish = () => setPlayingMessageId(null);
    const source = item.audioBase64
      ? audioDataUri(item.audioBase64, item.audioFormat)
      : item.audioUrl;
    if (source) {
      try {
        playTrackedAudio(source, finish);
        setPlayingMessageId(item.id);
      } catch {
        setPlayingMessageId(null);
      }
      return;
    }
    try {
      const env = await api.post<{ audioBase64?: string; format?: string }>('/speech/tts', { text: item.text });
      const base64 = env.data?.audioBase64;
      if (!base64) return;
      const format = env.data?.format || 'mp3';
      setMessages((prev) => prev.map((m) => (m.id === item.id
        ? { ...m, audioBase64: base64, audioFormat: format, replayable: true } : m)));
      playTrackedAudio(audioDataUri(base64, format), finish);
      setPlayingMessageId(item.id);
    } catch {
      setPlayingMessageId(null);
    }
  };

  /**
   * 重新开始：结束当前会话（计费、不生成课后反馈），立即进入同话题的新会话。
   */
  const restart = async () => {
    if (!sessionId) return;
    stopRecording();
    stopAllPlayback();
    try {
      const envelope = await api.post<{ sessionId: string }>(`/speaking/sessions/${sessionId}/restart`);
      // 重置房间状态并切换到新会话
      setMessages([]);
      setFeedback(null);
      setError('');
      setInput('');
      setAssistantState('idle');
      setOpeningReady(false);
      setOpeningTimedOut(false);
      setQuotaRemaining(null);
      lastUserTurnIdRef.current = '';
      lastUserMsgIdRef.current = '';
      lastAssistantMsgIdRef.current = '';
      setPlayingFeedbackId(null);
      setFeedbackLoadingId(null);
      setPlayingMessageId(null);
      setReadOnly(false);
      dispatch({ type: 'TRANSITION', target: 'creating_session' });
      router.replace({ pathname: '/(app)/speaking/[sessionId]', params: { sessionId: envelope.data.sessionId } });
    } catch (e: any) {
      setError(e?.message || '重新开始失败');
    }
  };

  const stateLabel: Record<string, string> = {
    listening: '正在听你说…', thinking: '思考中…', speaking: '正在回答…', idle: '准备就绪',
  };

  if (ctx.state === 'ended' || feedback) {
    return (
      <Screen>
        <Card>
          <Text style={styles.feedbackTitle}>课后反馈</Text>
          <Text style={styles.summary}>{feedback?.summary}</Text>
        </Card>
        {(feedback?.corrections || []).map((c, idx) => (
          <Card key={idx} style={styles.correction}>
            <Badge text={c.category} />
            <Text style={styles.original}>原句：{c.original}</Text>
            <Text style={styles.corrected}>纠正：{c.corrected}</Text>
            <Text style={styles.explain}>{c.explanation}</Text>
          </Card>
        ))}
        <Card style={styles.quotaCard}>
          {quotaRemaining !== null && quotaRemaining > 0 ? (
            <Text style={styles.quotaText}>今日口语额度剩余 {quotaRemaining} 分钟，可以继续情景对话</Text>
          ) : null}
          {quotaRemaining !== null && quotaRemaining <= 0 ? (
            <Text style={styles.quotaExhausted}>今日口语额度已用完，明天再来吧</Text>
          ) : null}
        </Card>
        <Button title="继续情景对话" onPress={() => router.replace('/(app)/today')}
                disabled={quotaRemaining !== null && quotaRemaining <= 0} />
        <Button title="返回今日任务" variant="secondary" onPress={() => router.replace('/(app)/today')}
                style={{ marginTop: spacing.sm }} />
      </Screen>
    );
  }

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll={false}>
        {!readOnly ? (
          <View style={styles.stateBar}>
            <Badge text={stateLabel[assistantState] || '连接中…'} color={ctx.state === 'connected' ? colors.success : colors.warning} />
            <Badge text={ctx.state} />
            {ctx.state === 'connected' && !openingReady && !openingTimedOut ? (
              <Badge text="AI 正在准备开场白…" color={colors.warning} />
            ) : null}
          </View>
        ) : (
          <Card style={styles.quotaCard}>
            <Text style={styles.quotaExhausted}>今日口语额度已用完，可回看和回听之前的对话内容</Text>
          </Card>
        )}
        <ErrorBanner message={error} />
        <FlatList
          ref={listRef}
          data={messages}
          keyExtractor={(item) => item.id}
          style={{ flex: 1 }}
          contentContainerStyle={styles.list}
          onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: true })}
          renderItem={({ item }) => {
            const canReplay = item.role === 'assistant' && item.replayable;
            const canPlayUser = item.role === 'user' && !!item.audioUrl;
            const playable = canReplay || canPlayUser;
            const suffix = canReplay
              ? (playingMessageId === item.id ? ' ⏹' : ' ▶')
              : (canPlayUser ? ' ▶' : '');
            return (
              <Pressable
                disabled={!playable}
                onPress={() => {
                  if (!playable) return;
                  if (canPlayUser) playUrl(item.audioUrl!);
                  else toggleMessageAudio(item);
                }}
                style={[styles.bubble, item.role === 'user' ? styles.userBubble : styles.assistantBubble]}
              >
                <Text style={[styles.bubbleText, item.role === 'user' ? styles.userText : styles.assistantText]}>
                  {item.text}{suffix}
                </Text>
                {item.feedbackAudioBase64 || item.feedbackText ? (
                  <Pressable onPress={() => toggleFeedbackAudio(item)}
                             style={({ pressed }) => [styles.playSuggestionBtn, pressed && { opacity: 0.7 }]}>
                    <Text style={styles.playSuggestionText}>
                      {feedbackLoadingId === item.id ? '加载语音…'
                        : playingFeedbackId === item.id ? '⏹ 停止播放' : '▶ 播放建议'}
                    </Text>
                  </Pressable>
                ) : null}
              </Pressable>
            );
          }}
        />
        <View style={styles.inputRow}>
          <Button title={recording ? '⏹ 停止录音' : '🎤 录音'} variant="secondary"
                  onPress={recording ? stopRecording : startRecording}
                  disabled={ctx.state !== 'connected' || !(openingReady || openingTimedOut)} />
          <View style={{ flex: 1 }}>
            <Input
              value={input}
              onChangeText={setInput}
              placeholder={readOnly ? '只读回看模式' : '输入英语继续对话'}
            />
          </View>
          <Button title="发送" onPress={send}
                  disabled={ctx.state !== 'connected' || recording || !(openingReady || openingTimedOut)} />
        </View>
        <View style={styles.actionRow}>
          <Button title="重新开始" variant="secondary" onPress={restart}
                  disabled={ctx.state !== 'connected'} style={{ flex: 1 }} />
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  stateBar: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  list: { paddingVertical: spacing.sm },
  bubble: { maxWidth: '85%', borderRadius: 16, padding: spacing.md, marginBottom: spacing.sm },
  userBubble: { alignSelf: 'flex-end', backgroundColor: colors.primary },
  assistantBubble: { alignSelf: 'flex-start', backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border },
  bubbleText: { fontSize: font.md, lineHeight: 22 },
  userText: { color: '#FFFFFF' },
  assistantText: { color: colors.text },
  playSuggestionBtn: {
    marginTop: spacing.sm,
    alignSelf: 'flex-start',
    borderWidth: 1,
    borderColor: colors.primary,
    borderRadius: 12,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  playSuggestionText: { color: colors.primary, fontSize: font.xs, fontWeight: '600' },
  inputRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, marginTop: spacing.sm },
  actionRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  feedbackTitle: { fontSize: font.xl, fontWeight: '800', color: colors.text, marginBottom: spacing.sm },
  summary: { color: colors.textSecondary, lineHeight: 22 },
  correction: { padding: spacing.md },
  original: { color: colors.danger, marginTop: spacing.sm, fontSize: font.sm },
  corrected: { color: colors.success, marginTop: spacing.xs, fontSize: font.sm },
  explain: { color: colors.textMuted, marginTop: spacing.xs, fontSize: font.xs },
  quotaCard: { padding: spacing.md },
  quotaText: { color: colors.success, fontSize: font.sm },
  quotaExhausted: { color: colors.warning, fontSize: font.sm },
});
