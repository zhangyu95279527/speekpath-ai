import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@speakpath/api';
import { planApi, taskTypeLabel } from '@speakpath/learning';
import type { TaskDto } from '@speakpath/types';
import { Badge, Button, Card, EmptyState, ErrorBanner, Screen, SectionTitle, colors, font, spacing } from '@speakpath/ui';
import { formatMinutes, todayStr } from '@speakpath/utils';

interface ReadinessDto {
  date: string;
  ready: boolean;
  generating: boolean;
  totalCount: number;
  doneCount: number;
  pendingCount: number;
  materialTotal: number;
  materialDone: number;
  progress: number;
}

export default function Today() {
  const queryClient = useQueryClient();
  const date = todayStr();
  const tasksQuery = useQuery({ queryKey: ['tasks', date], queryFn: () => planApi.tasks(date) });
  const planQuery = useQuery({ queryKey: ['plan'], queryFn: () => planApi.active() });

  // 任务内容生成进度：未就绪时每 2 秒轮询一次，就绪后停止并刷新任务列表
  const readinessQuery = useQuery({
    queryKey: ['tasks-readiness', date],
    queryFn: () => api.get<ReadinessDto>(`/tasks/readiness?date=${date}`),
    refetchInterval: (query) => (query.state.data?.data?.ready ? false : 2000),
  });
  const readiness = readinessQuery.data?.data;
  const generating = !!readiness && !readiness.ready;

  React.useEffect(() => {
    if (readiness?.ready) {
      queryClient.invalidateQueries({ queryKey: ['tasks', date] });
    }
  }, [readiness?.ready, date, queryClient]);

  const tasks = tasksQuery.data?.data || [];
  const totalMinutes = tasks.reduce((sum, t) => sum + t.durationMin, 0);

  const [startError, setStartError] = React.useState('');

  const startSpeaking = async (task?: TaskDto) => {
    setStartError('');
    try {
      const resume = await api.get<{ resumed: boolean; sessionId?: string; reviewSessionId?: string; quotaRemainingMinutes?: number }>(
        `/speaking/sessions/resumable${task ? `?taskId=${task.taskId}` : ''}`);
      const d = resume.data;
      let sessionId: string | undefined;
      if (d.resumed && d.sessionId) {
        // 续接上次对话
        sessionId = d.sessionId;
      } else if ((d.quotaRemainingMinutes ?? 1) > 0) {
        // 额度未用完：新建会话继续练习
        const envelope = await api.post<{ sessionId: string }>('/speaking/sessions', {
          taskId: task?.taskId ?? null,
          mode: 'FREE_CONVERSATION',
          locale: 'en-US',
          maxDurationSeconds: 900,
        });
        sessionId = envelope.data.sessionId;
      } else if (d.reviewSessionId) {
        // 额度已用完：进入回看最近的对话（只读）
        sessionId = d.reviewSessionId;
      } else {
        setStartError('今日口语额度已用完，明天再来吧');
        return;
      }
      router.push({ pathname: '/(app)/speaking/[sessionId]', params: { sessionId } });
    } catch (e: any) {
      setStartError(e?.message || '进入口语陪练失败');
    }
  };

  return (
    <Screen>
      <ErrorBanner message={tasksQuery.error ? String((tasksQuery.error as any)?.message) : ''} />
      <ErrorBanner message={startError} />

      <Card style={styles.hero}>
        <Text style={styles.heroTitle}>今日训练</Text>
        <Text style={styles.heroMeta}>
          {generating
            ? '任务内容生成中…'
            : `共 ${tasks.length} 项 · 预计 ${formatMinutes(totalMinutes)}`}
        </Text>
        <View style={styles.progressTrack}>
          <View style={[styles.progressFill, { width: generating ? '15%' : '100%' }]} />
        </View>
        <Button title="开始 AI 口语陪练" onPress={() => startSpeaking()} style={{ marginTop: spacing.md }} />
      </Card>

      {generating ? (
        <Card style={styles.generatingCard}>
          <View style={styles.generatingRow}>
            <ActivityIndicator color={colors.primary} />
            <Text style={styles.generatingTitle}>任务生成中…</Text>
          </View>
          <Text style={styles.generatingHint}>
            AI 正在为今天的练习准备内容
            {readiness && readiness.materialTotal > 0
              ? `（内容 ${readiness.materialDone}/${readiness.materialTotal}）`
              : ''}
            ，完成后自动显示
          </Text>
          <View style={styles.progressTrackLight}>
            <View style={[styles.progressFillPrimary, { width: `${Math.max(4, readiness?.progress ?? 0)}%` }]} />
          </View>
        </Card>
      ) : null}

      {!planQuery.data?.data?.hasPlan && (
        <Card style={styles.cta}>
          <Text style={styles.ctaTitle}>还没有学习计划</Text>
          <Text style={styles.ctaText}>完成一次口语诊断后即可生成个性化学习计划</Text>
          <Button title="去做口语诊断" variant="secondary" onPress={() => router.push('/(app)/onboarding/exam')} />
        </Card>
      )}

      <SectionTitle>任务清单</SectionTitle>
      {!generating && tasks.length > 0 ? (
        <Text style={styles.progressTip}>练习内容由易到难排列，单词、语法、时态、句式难度逐句递增</Text>
      ) : null}
      {generating ? (
        <EmptyState title="正在生成今日任务内容" hint="AI 补全练习材料需要一点时间，请稍候" />
      ) : tasks.length === 0 ? (
        <EmptyState title="今天没有任务" hint="先完成口语诊断并生成学习计划" />
      ) : (
        tasks.map((task) => (
          <Card key={task.taskId} style={styles.taskCard}>
            <View style={styles.taskHeader}>
              <Badge text={taskTypeLabel(task.taskType)} />
              {task.taskType !== 'SCENARIO' ? <Badge text="难度递增" /> : null}
              {task.locked ? <Badge text="已锁定" /> : null}
              <Text style={styles.taskMinutes}>{task.durationMin} 分钟</Text>
            </View>
            <Text style={styles.taskTitle}>{task.title}</Text>
            {task.content?.instructions ? (
              <Text style={styles.taskHint} numberOfLines={2}>
                {String(task.content.instructions)}
              </Text>
            ) : null}
            <View style={styles.taskActions}>
              <Button title="开始练习"
                      onPress={() => task.taskType === 'SCENARIO'
                        ? startSpeaking(task)
                        : router.push({ pathname: '/(app)/task/[taskId]', params: { taskId: task.taskId } })} />
            </View>
          </Card>
        ))
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  hero: { backgroundColor: colors.primary, borderColor: colors.primary },
  heroTitle: { color: '#FFFFFF', fontSize: font.lg, fontWeight: '700' },
  heroMeta: { color: 'rgba(255,255,255,0.85)', marginTop: spacing.xs, fontSize: font.sm },
  progressTrack: { height: 8, backgroundColor: 'rgba(255,255,255,0.25)', borderRadius: 4, marginTop: spacing.md },
  progressFill: { height: 8, backgroundColor: '#FFFFFF', borderRadius: 4 },
  cta: { borderColor: colors.primaryLight },
  ctaTitle: { fontWeight: '700', fontSize: font.md, color: colors.text },
  ctaText: { color: colors.textSecondary, fontSize: font.sm, marginVertical: spacing.sm },
  generatingCard: { borderColor: colors.primaryLight },
  generatingRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  generatingTitle: { fontWeight: '700', fontSize: font.md, color: colors.text },
  generatingHint: { color: colors.textSecondary, fontSize: font.sm, marginTop: spacing.sm, marginBottom: spacing.sm },
  progressTrackLight: { height: 6, backgroundColor: colors.chip, borderRadius: 3 },
  progressFillPrimary: { height: 6, backgroundColor: colors.primary, borderRadius: 3 },
  taskCard: { padding: spacing.md },
  progressTip: { color: colors.textMuted, fontSize: font.xs, marginBottom: spacing.sm },
  taskHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.sm },
  taskMinutes: { marginLeft: 'auto', color: colors.textMuted, fontSize: font.xs },
  taskTitle: { fontSize: font.md, fontWeight: '600', color: colors.text, marginBottom: spacing.sm },
  taskHint: { color: colors.textSecondary, fontSize: font.xs, marginBottom: spacing.sm },
  taskActions: { flexDirection: 'row', gap: spacing.sm },
});
