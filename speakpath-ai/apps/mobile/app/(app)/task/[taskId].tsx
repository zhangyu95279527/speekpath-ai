import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiRequest } from '@speakpath/api';
import { assessmentApi } from '@speakpath/assessment';
import { taskTypeLabel } from '@speakpath/learning';
import { createPronunciationService, getAudioService, playTrackedAudio, stopAllPlayback, type PronunciationService } from '@speakpath/audio';
import type { PronunciationAttemptDto, TaskDto } from '@speakpath/types';
import { Badge, Button, Card, ErrorBanner, Screen, SectionTitle, colors, font, spacing } from '@speakpath/ui';

interface SentenceItem { text: string; note?: string; audioUrl?: string }
interface WordItem { word: string; pattern?: string; example?: string; audioUrl?: string }

interface TaskContent {
  instructions?: string;
  exerciseType?: string;
  examCode?: string;
  targetScore?: number;
  sentences?: SentenceItem[];
  words?: WordItem[];
  /** 通用练习条目（词汇/同义替换/搭配纠错/总结复述等） */
  items?: SentenceItem[];
}

/** 播放语音：后端 TTS 音频优先；无音频时降级浏览器语音合成。 */
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

/** 最近一次评测结果卡片：展示改进建议，并支持与情景口语一致的建议语音播放。 */
function AttemptResult({ attempt, playing, loading, onToggle }: {
  attempt: PronunciationAttemptDto;
  playing: boolean;
  loading: boolean;
  onToggle: () => void;
}) {
  const suggestions = attempt.suggestions || [];
  return (
    <View style={styles.resultBox}>
      {suggestions.map((s, i) => (
        <Text key={i} style={styles.suggestion}>• {s}</Text>
      ))}
      <View style={styles.resultFooter}>
        <Text style={styles.attemptTime}>
          {new Date(attempt.createdAt).toLocaleString()}
        </Text>
        {suggestions.length > 0 ? (
          <Button title={loading ? '加载语音…' : playing ? '⏹ 停止播放' : '▶ 播放建议'}
                  variant="ghost"
                  disabled={loading}
                  onPress={onToggle} />
        ) : null}
      </View>
    </View>
  );
}

export default function TaskDetail() {
  const { taskId } = useLocalSearchParams<{ taskId: string }>();
  const queryClient = useQueryClient();
  const taskQuery = useQuery({
    queryKey: ['task', taskId],
    queryFn: () => api.get<TaskDto>(`/tasks/${taskId}`),
    enabled: !!taskId, // 路由参数首帧可能未就绪，避免请求 /tasks/undefined
  });
  const attemptsQuery = useQuery({
    queryKey: ['pron-attempts', taskId],
    queryFn: () => api.get<PronunciationAttemptDto[]>(`/tasks/${taskId}/pronunciation`),
    enabled: !!taskId,
  });

  const [playing, setPlaying] = useState('');
  const [feedbackPlayingId, setFeedbackPlayingId] = useState<string | null>(null);
  const [feedbackLoadingId, setFeedbackLoadingId] = useState<string | null>(null);
  const [practicingIndex, setPracticingIndex] = useState<number | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [assessmentStarting, setAssessmentStarting] = useState(false);
  const [error, setError] = useState('');
  const abortRef = useRef<AbortController | null>(null);
  const pronSvcRef = useRef<PronunciationService | null>(null);
  const feedbackAudioCache = useRef<Map<string, string>>(new Map());

  // 离开页面时取消进行中的请求、释放评测引擎并停止播放
  useEffect(() => () => {
    abortRef.current?.abort();
    pronSvcRef.current?.destroy?.();
    pronSvcRef.current = null;
    stopAllPlayback();
  }, []);

  // 页面失焦（返回上级、切换标签、跳转评测等）时立即停止语音，避免后台继续播放
  useFocusEffect(
    useCallback(() => () => {
      stopAllPlayback();
      setPlaying('');
      setFeedbackPlayingId(null);
    }, [])
  );

  const attempts = attemptsQuery.data?.data || [];
  const attemptsByIndex = new Map<number, PronunciationAttemptDto[]>();
  for (const a of attempts) {
    const list = attemptsByIndex.get(a.itemIndex) || [];
    list.push(a);
    attemptsByIndex.set(a.itemIndex, list);
  }

  const speak = useCallback(async (text: string) => {
    setPlaying(text);
    setError('');
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const env = await apiRequest<{ audioBase64?: string; format?: string }>('/speech/tts', {
        method: 'POST',
        body: JSON.stringify({ text }),
        signal: controller.signal,
      });
      playAudio(env.data.audioBase64, env.data.format, text);
    } catch (e: any) {
      if (controller.signal.aborted) return; // 主动取消，忽略
      setError(e?.message || '播放失败');
    } finally {
      setPlaying('');
    }
  }, []);

  /** 播放示范：优先任务创建时预生成的音频，缺失回退现合成。 */
  const playSample = useCallback(async (text: string, audioUrl?: string) => {
    if (audioUrl) {
      setPlaying(text);
      try {
        await getAudioService().play(audioUrl);
      } catch {
        await speak(text);
      }
      setPlaying('');
      return;
    }
    await speak(text);
  }, [speak]);

  /** 评价建议语音播放/停止切换：与情景口语的建议播放业务一致。 */
  const toggleFeedbackAudio = useCallback(async (attempt: PronunciationAttemptDto) => {
    const attemptId = attempt.attemptId;
    if (feedbackPlayingId === attemptId) {
      stopAllPlayback();
      setFeedbackPlayingId(null);
      return;
    }
    const text = (attempt.suggestions || []).join('。');
    if (!text || feedbackLoadingId) return;
    setFeedbackLoadingId(attemptId);
    try {
      let source = feedbackAudioCache.current.get(attemptId);
      if (!source) {
        const env = await apiRequest<{ audioBase64?: string; format?: string }>('/speech/tts', {
          method: 'POST',
          body: JSON.stringify({ text, voice: 'loongbella_v3.1' }),
        });
        if (!env.data.audioBase64) {
          setFeedbackPlayingId(null);
          return;
        }
        const mime = env.data.format === 'mp3' ? 'audio/mpeg' : `audio/${env.data.format || 'mp3'}`;
        source = `data:${mime};base64,${env.data.audioBase64}`;
        feedbackAudioCache.current.set(attemptId, source);
      }
      if (!source) return;
      stopAllPlayback();
      playTrackedAudio(source, () => setFeedbackPlayingId(null));
      setFeedbackPlayingId(attemptId);
    } catch (e: any) {
      setFeedbackPlayingId(null);
      setError(e?.message || '建议语音播放失败');
    } finally {
      setFeedbackLoadingId(null);
    }
  }, [feedbackPlayingId, feedbackLoadingId]);

  /** 开始跟读评测：服务端签发 warrant → 引擎录音评测 → 结果提交后端保存。 */
  const startPractice = useCallback(async (idx: number, refText: string) => {
    if (!taskId) return;
    setError('');
    setPracticingIndex(idx);
    try {
      const env = await api.post<{ result?: any }>('/speech/pronunciation', { referenceText: refText });
      const r = env.data.result || {};
      const svc = createPronunciationService();
      if (!svc) {
        throw new Error('当前环境不支持发音评测');
      }
      pronSvcRef.current?.destroy?.();
      pronSvcRef.current = svc;
      svc.onDone = async (res) => {
        pronSvcRef.current?.destroy?.();
        pronSvcRef.current = null;
        setPracticingIndex(null);
        try {
          await api.post(`/tasks/${taskId}/pronunciation`, { itemIndex: idx, refText, result: res });
          queryClient.invalidateQueries({ queryKey: ['pron-attempts', taskId] });
        } catch (e: any) {
          setError(e?.message || '保存评测结果失败');
        }
      };
      svc.onFail = (msg) => {
        pronSvcRef.current?.destroy?.();
        pronSvcRef.current = null;
        setPracticingIndex(null);
        setError(`评测失败：${msg}`);
      };
      await svc.init({
        applicationId: r.appid, userId: r.userId, warrantId: r.warrantId,
        appKey: r.appid, secretKey: '',
      });
      await svc.startRecord({ coreType: r.coreType || 'en.sent.score', refText });
    } catch (e: any) {
      setPracticingIndex(null);
      setError(e?.message || '无法开始评测，请检查麦克风权限');
    }
  }, [taskId, queryClient]);

  const stopPractice = useCallback(() => {
    pronSvcRef.current?.stopRecord();
  }, []);

  const toggleHistory = (idx: number) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx); else next.add(idx);
      return next;
    });
  };

  const task = taskQuery.data?.data;

  const backButton = (
    <View style={styles.backRow}>
      <Button title="← 返回每日任务" variant="ghost" onPress={() => router.replace('/(app)/today')} />
    </View>
  );

  if (!task) {
    return (
      <Screen>
        {backButton}
        <ErrorBanner message={taskQuery.error ? String((taskQuery.error as any)?.message) : ''} />
        <Text style={styles.loading}>加载中…</Text>
      </Screen>
    );
  }

  const content = (task.content ?? {}) as TaskContent;
  const exerciseType = content.exerciseType || '';

  const startAssessment = async () => {
    setAssessmentStarting(true);
    setError('');
    try {
      const env = await assessmentApi.create(content.examCode || 'IELTS_SPEAKING',
              content.targetScore ?? null, 'AUDIO');
      router.push(`/(app)/assessment/${env.data.sessionId}`);
    } catch (e: any) {
      setError(e?.message || '无法开始水平评测');
    } finally {
      setAssessmentStarting(false);
    }
  };

  const renderPractice = (idx: number, refText: string) => {
    const list = attemptsByIndex.get(idx) || [];
    const latest = list[0];
    const practicing = practicingIndex === idx;
    return (
      <View>
        <View style={styles.practiceRow}>
          <Button title={practicing ? '停止评测' : '🎤 跟读评测'}
                  variant={practicing ? 'danger' : 'secondary'}
                  disabled={practicingIndex !== null && !practicing}
                  onPress={() => (practicing ? stopPractice() : startPractice(idx, refText))} />
          {list.length > 0 ? (
            <Button title={expanded.has(idx) ? `收起历史（${list.length}）` : `已练 ${list.length} 次`}
                    variant="ghost"
                    onPress={() => toggleHistory(idx)} />
          ) : null}
        </View>
        {latest ? (
          <AttemptResult attempt={latest}
                         playing={feedbackPlayingId === latest.attemptId}
                         loading={feedbackLoadingId === latest.attemptId}
                         onToggle={() => toggleFeedbackAudio(latest)} />
        ) : null}
        {expanded.has(idx) && list.length > 1 ? (
          <View style={styles.historyBox}>
            {list.slice(1).map((a) => (
              <AttemptResult key={a.attemptId} attempt={a}
                             playing={feedbackPlayingId === a.attemptId}
                             loading={feedbackLoadingId === a.attemptId}
                             onToggle={() => toggleFeedbackAudio(a)} />
            ))}
          </View>
        ) : null}
      </View>
    );
  };

  return (
    <Screen>
      {backButton}
      <ErrorBanner message={error} />
      <Card>
        <View style={styles.header}>
          <Badge text={taskTypeLabel(task.taskType)} />
          <Text style={styles.duration}>{task.durationMin} 分钟</Text>
        </View>
        <Text style={styles.title}>{task.title}</Text>
        {content.instructions ? (
          <Text style={styles.instructions}>{content.instructions}</Text>
        ) : null}
      </Card>

      {task.taskType === 'ASSESSMENT' ? (
        <Card>
          <SectionTitle>水平评测</SectionTitle>
          <Text style={styles.instructions}>
            完成一次口语水平评测。系统会结合本次成绩与近期的口语纠错数据，自动调整接下来 7 天的练习内容与难度。
          </Text>
          <View style={styles.assessmentAction}>
            <Button title="开始水平评测" loading={assessmentStarting} onPress={startAssessment} />
          </View>
        </Card>
      ) : null}

      {exerciseType === 'shadowing' && content.sentences ? (
        <View>
          <SectionTitle>跟读句子</SectionTitle>
          <Text style={styles.levelHint}>由易到难 · 难度逐句递增</Text>
          <ScrollView style={styles.list}>
            {(content.sentences as SentenceItem[]).map((s, idx) => (
              <Card key={idx} style={styles.itemCard}>
                <View style={styles.itemHeader}>
                  <Badge text={`${idx + 1}`} />
                  <Button title={playing === s.text ? '播放中…' : '播放示范'}
                          variant="secondary"
                          disabled={playing !== '' || practicingIndex !== null}
                          onPress={() => playSample(s.text, s.audioUrl)} />
                </View>
                <Text style={styles.itemText}>{s.text}</Text>
                {s.note ? <Text style={styles.itemNote}>{s.note}</Text> : null}
                {renderPractice(idx, s.text)}
              </Card>
            ))}
          </ScrollView>
        </View>
      ) : null}

      {exerciseType === 'stress' && content.words ? (
        <View>
          <SectionTitle>重音训练词</SectionTitle>
          <ScrollView style={styles.list}>
            {(content.words as WordItem[]).map((w, idx) => {
              const refText = w.example || w.word;
              return (
                <Card key={idx} style={styles.itemCard}>
                  <View style={styles.itemHeader}>
                    <Badge text={w.word} />
                    <Button title={playing === refText ? '播放中…' : '播放示范'}
                            variant="secondary"
                            disabled={playing !== '' || practicingIndex !== null}
                            onPress={() => playSample(refText, w.audioUrl)} />
                  </View>
                  {w.pattern ? <Text style={styles.pattern}>{w.pattern}</Text> : null}
                  {w.example ? <Text style={styles.itemText}>{w.example}</Text> : null}
                  {renderPractice(idx, refText)}
                </Card>
              );
            })}
          </ScrollView>
        </View>
      ) : null}

      {exerciseType !== 'shadowing' && exerciseType !== 'stress' && content.items ? (
        <View>
          <SectionTitle>练习内容</SectionTitle>
          <Text style={styles.levelHint}>由易到难 · 难度逐句递增</Text>
          <ScrollView style={styles.list}>
            {(content.items as SentenceItem[]).map((s, idx) => (
              <Card key={idx} style={styles.itemCard}>
                <View style={styles.itemHeader}>
                  <Badge text={`${idx + 1}`} />
                  <Button title={playing === s.text ? '播放中…' : '播放示范'}
                          variant="secondary"
                          disabled={playing !== '' || practicingIndex !== null}
                          onPress={() => playSample(s.text, s.audioUrl)} />
                </View>
                <Text style={styles.itemText}>{s.text}</Text>
                {s.note ? <Text style={styles.itemNote}>{s.note}</Text> : null}
                {renderPractice(idx, s.text)}
              </Card>
            ))}
          </ScrollView>
        </View>
      ) : null}

      {!exerciseType && task.taskType !== 'ASSESSMENT' && !content.sentences && !content.words && !content.items ? (
        <Card>
          <Text style={styles.instructions}>
            任务内容正在生成中，请稍后返回“今日任务”刷新查看。
          </Text>
        </Card>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  backRow: { alignItems: 'flex-start', marginBottom: spacing.xs },
  loading: { color: colors.textSecondary, textAlign: 'center', marginTop: spacing.lg },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  duration: { color: colors.textMuted, fontSize: font.xs },
  title: { fontSize: font.lg, fontWeight: '800', color: colors.text, marginBottom: spacing.sm },
  instructions: { color: colors.textSecondary, fontSize: font.sm, lineHeight: 20 },
  levelHint: { color: colors.textMuted, fontSize: font.xs, marginBottom: spacing.xs },
  assessmentAction: { marginTop: spacing.sm },
  list: { maxHeight: 460 },
  itemCard: { padding: spacing.md },
  itemHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  itemText: { fontSize: font.md, color: colors.text, lineHeight: 24, marginTop: spacing.xs },
  itemNote: { color: colors.textMuted, fontSize: font.xs, marginTop: spacing.xs },
  pattern: { color: colors.primary, fontSize: font.sm, fontWeight: '700', marginBottom: spacing.xs },
  practiceRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  resultBox: { marginTop: spacing.sm, backgroundColor: colors.chip, borderRadius: 12, padding: spacing.sm },
  resultFooter: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: spacing.xs },
  suggestion: { fontSize: font.xs, color: colors.text, lineHeight: 18 },
  attemptTime: { fontSize: font.xs, color: colors.textMuted },
  historyBox: { marginTop: spacing.xs },
});
