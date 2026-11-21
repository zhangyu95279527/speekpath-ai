import React, { useEffect, useState } from 'react';
import { Modal, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { router } from 'expo-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { planApi, taskTypeLabel } from '@speakpath/learning';
import { api, waitForJob } from '@speakpath/api';
import type { ScoreTrendDto, TaskDto } from '@speakpath/types';
import { Badge, Button, Card, EmptyState, ErrorBanner, Screen, SectionTitle, colors, font, spacing } from '@speakpath/ui';
import { formatDate } from '@speakpath/utils';

const EXAMS = [
  { code: 'IELTS_SPEAKING', label: 'IELTS', max: 9 },
  { code: 'TOEFL_SPEAKING', label: 'TOEFL', max: 30 },
];
const MINUTE_OPTIONS = [15, 30, 45, 60];

/** 自定义折线图：当前分与目标分的差距、历次水平评测的进步/退步。 */
function ScoreLineChart({ points, target, current }: { points: { date: string; score: number }[]; target: number | null; current: number | null }) {
  const [width, setWidth] = useState(0);
  const height = 130;
  const pad = 12;
  const values = [...points.map((p) => p.score), ...(target == null ? [] : [target]), ...(current == null ? [] : [current])];
  const low = Math.min(...values);
  const high = Math.max(...values);
  const span = Math.max(0.5, high - low);
  const lo = Math.max(0, low - span * 0.2);
  const hi = high + span * 0.2;
  const x = (i: number) => (points.length <= 1 ? width / 2 : pad + (i * (width - pad * 2)) / (points.length - 1));
  const y = (v: number) => height - pad - ((v - lo) / (hi - lo || 1)) * (height - pad * 2);

  return (
    <View style={{ height }} onLayout={(e) => setWidth(e.nativeEvent.layout.width)}>
      {target != null && width > 0 ? (
        <View style={[styles.targetLine, { top: y(target) }]} />
      ) : null}
      {width > 0 && points.map((p, i) => {
        if (i === 0) return null;
        const x1 = x(i - 1);
        const y1 = y(points[i - 1].score);
        const x2 = x(i);
        const y2 = y(p.score);
        const dx = x2 - x1;
        const dy = y2 - y1;
        const len = Math.sqrt(dx * dx + dy * dy);
        const angle = (Math.atan2(dy, dx) * 180) / Math.PI;
        return (
          <View
            key={`seg-${p.date}-${i}`}
            style={{
              position: 'absolute', left: (x1 + x2) / 2 - len / 2, top: (y1 + y2) / 2 - 1,
              width: len, height: 2, backgroundColor: colors.primary, transform: [{ rotate: `${angle}deg` }],
            }}
          />
        );
      })}
      {width > 0 && points.map((p, i) => (
        <View key={`dot-${p.date}-${i}`} style={{ position: 'absolute', left: x(i) - 3, top: y(p.score) - 3, ...styles.dot }} />
      ))}
    </View>
  );
}

export default function PlanScreen() {
  const queryClient = useQueryClient();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showReplan, setShowReplan] = useState(false);
  const [examCode, setExamCode] = useState('IELTS_SPEAKING');
  const [targetScore, setTargetScore] = useState('7');
  const [dailyMinutes, setDailyMinutes] = useState(30);

  const planQuery = useQuery({ queryKey: ['plan'], queryFn: () => planApi.active() });
  const upcomingQuery = useQuery({ queryKey: ['upcoming'], queryFn: () => planApi.upcoming(14) });
  const trendQuery = useQuery({
    queryKey: ['progress', 'score-trend'],
    queryFn: () => api.get<ScoreTrendDto>('/progress/score-trend'),
  });

  const plan = planQuery.data?.data;
  const tasks = upcomingQuery.data?.data || [];
  const trend = trendQuery.data?.data;
  const exam = EXAMS.find((e) => e.code === examCode) || EXAMS[0];

  useEffect(() => {
    if (!plan?.hasPlan) return;
    if (plan.examCode) setExamCode(plan.examCode);
    if (plan.targetScore != null) setTargetScore(String(plan.targetScore));
    if (plan.dailyMinutes) setDailyMinutes(plan.dailyMinutes);
  }, [plan?.planId]);

  const byDate = new Map<string, TaskDto[]>();
  for (const task of tasks) {
    const list = byDate.get(task.taskDate) || [];
    list.push(task);
    byDate.set(task.taskDate, list);
  }

  const openReplan = () => {
    setError('');
    setShowReplan(true);
  };

  const submitReplan = async () => {
    const score = Number(targetScore);
    if (!Number.isFinite(score) || score <= 0 || score > exam.max) {
      setError(`${exam.label} 目标分数需在 0–${exam.max} 之间`);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const envelope = await planApi.replan({ examCode, targetScore: score, dailyMinutes });
      await waitForJob(envelope.data.jobId);
      setShowReplan(false);
      queryClient.invalidateQueries({ queryKey: ['plan'] });
      queryClient.invalidateQueries({ queryKey: ['upcoming'] });
      queryClient.invalidateQueries({ queryKey: ['progress'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    } catch (e: any) {
      setError(e?.message || '重新规划失败');
    } finally {
      setBusy(false);
    }
  };

  if (!plan?.hasPlan) {
    return (
      <Screen>
        <EmptyState title="还没有学习计划" hint="完成口语诊断报告后即可生成个性化学习计划" />
      </Screen>
    );
  }

  const current = trend?.currentScore ?? null;
  const target = trend?.targetScore ?? plan.targetScore ?? null;
  const gap = trend?.gap ?? (target != null && current != null ? Number((target - current).toFixed(1)) : null);

  return (
    <Screen>
      <ErrorBanner message={error} />
      <Card>
        <View style={styles.planHeader}>
          <Text style={styles.planTitle}>{exam.label} 学习计划</Text>
          <Badge text={`v${plan.activeVersion}`} />
        </View>
        <Text style={styles.planMeta}>
          {formatDate(plan.startDate || '')} – {formatDate(plan.endDate || '')} · 每日 {plan.dailyMinutes} 分钟 · 每周日休息
        </Text>
        <Text style={styles.planHint}>每 7 天自动安排一次水平评测，并依据评测与口语纠错数据调整下一阶段难度。</Text>
        <Button title="重新规划" variant="secondary" onPress={openReplan} />
      </Card>

      <Card>
        <View style={styles.planHeader}>
          <Text style={styles.planTitle}>成绩与目标</Text>
          <Badge text={trend?.examName || exam.label} />
        </View>
        <View style={styles.scoreRow}>
          <View style={styles.scoreItem}>
            <Text style={styles.scoreLabel}>当前分</Text>
            <Text style={styles.scoreValue}>{current == null ? '—' : current.toFixed(1)}</Text>
          </View>
          <View style={styles.scoreItem}>
            <Text style={styles.scoreLabel}>目标分</Text>
            <Text style={styles.scoreValue}>{target == null ? '—' : Number(target).toFixed(1)}</Text>
          </View>
          <View style={styles.scoreItem}>
            <Text style={styles.scoreLabel}>差距</Text>
            <Text style={[styles.scoreValue, { color: gap != null && gap <= 0 ? colors.success : colors.danger }]}>
              {gap == null ? '—' : gap <= 0 ? '已达标' : `还差 ${gap.toFixed(1)}`}
            </Text>
          </View>
        </View>
        {(trend?.points?.length || 0) > 0 ? (
          <>
            <ScoreLineChart points={trend!.points} target={target} current={current} />
            <View style={styles.chartFooter}>
              <Text style={styles.chartDate}>{formatDate(trend!.points[0].date)}</Text>
              <Text style={styles.chartLegend}>实线：综合分 · 虚线：目标分</Text>
              <Text style={styles.chartDate}>{formatDate(trend!.points[trend!.points.length - 1].date)}</Text>
            </View>
          </>
        ) : (
          <Text style={styles.planHint}>完成一次水平评测后，这里会显示成绩进步或退步曲线。</Text>
        )}
        <Text style={styles.planHint}>
          当前难度：{plan.difficultyLevel ?? 3}/5
          {plan.focusAreas && plan.focusAreas.length > 0 ? ` · 重点提升：${plan.focusAreas.join('、')}` : ''}
        </Text>
      </Card>

      <SectionTitle>接下来两周（按日期）</SectionTitle>
      {Array.from(byDate.entries()).slice(0, 14).map(([date, dayTasks]) => (
        <Card key={date} style={styles.dayCard}>
          <View style={styles.dayHeader}>
            <Text style={styles.dayTitle}>{formatDate(date)}</Text>
            <Text style={styles.dayMinutes}>{dayTasks.reduce((s, t) => s + t.durationMin, 0)} 分钟</Text>
          </View>
          {dayTasks.map((task) => (
            <View key={task.taskId} style={styles.taskRow}>
              <Badge text={taskTypeLabel(task.taskType)} />
              <Text style={styles.taskTitle} numberOfLines={2}>{task.title}</Text>
              <Text style={styles.taskDur}>{task.durationMin}m</Text>
              {task.locked ? <Badge text="锁" /> : null}
              {task.taskType !== 'SCENARIO' ? (
                <Text style={styles.taskAction}
                      onPress={() => router.push({ pathname: '/(app)/task/[taskId]', params: { taskId: task.taskId } })}>
                  开始练习
                </Text>
              ) : null}
            </View>
          ))}
        </Card>
      ))}

      <Modal visible={showReplan} transparent animationType="fade" onRequestClose={() => setShowReplan(false)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>重新规划</Text>
            <Text style={styles.modalLabel}>目标考试</Text>
            <View style={styles.optionRow}>
              {EXAMS.map((item) => (
                <Pressable key={item.code} onPress={() => {
                  setExamCode(item.code);
                  setTargetScore(item.code === 'TOEFL_SPEAKING' ? '26' : '7');
                }} style={[styles.option, exam.code === item.code && styles.optionActive]}>
                  <Text style={[styles.optionText, exam.code === item.code && styles.optionTextActive]}>{item.label}</Text>
                </Pressable>
              ))}
            </View>

            <Text style={styles.modalLabel}>目标分数（0–{exam.max}）</Text>
            <TextInput
              style={styles.input}
              value={targetScore}
              onChangeText={setTargetScore}
              keyboardType="decimal-pad"
              placeholder={exam.code === 'TOEFL_SPEAKING' ? '26' : '7.0'}
            />

            <Text style={styles.modalLabel}>每日学习时间</Text>
            <View style={styles.optionRow}>
              {MINUTE_OPTIONS.map((m) => (
                <Pressable key={m} onPress={() => setDailyMinutes(m)} style={[styles.option, dailyMinutes === m && styles.optionActive]}>
                  <Text style={[styles.optionText, dailyMinutes === m && styles.optionTextActive]}>{m} 分钟</Text>
                </Pressable>
              ))}
            </View>

            <ErrorBanner message={error} />
            <View style={styles.modalActions}>
              <Button title="取消" variant="secondary" onPress={() => setShowReplan(false)} disabled={busy} />
              <Button title="开始规划" onPress={submitReplan} loading={busy} />
            </View>
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

const styles = StyleSheet.create({
  planHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.sm },
  planTitle: { fontSize: font.lg, fontWeight: '700', color: colors.text },
  planMeta: { color: colors.textSecondary, fontSize: font.sm, marginBottom: spacing.xs },
  planHint: { color: colors.textMuted, fontSize: font.xs, lineHeight: 18, marginBottom: spacing.sm },
  scoreRow: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  scoreItem: { flex: 1 },
  scoreLabel: { color: colors.textMuted, fontSize: font.xs, marginBottom: 2 },
  scoreValue: { color: colors.text, fontSize: font.md, fontWeight: '700' },
  targetLine: { position: 'absolute', left: 0, right: 0, borderTopWidth: 1, borderStyle: 'dashed', borderColor: colors.textMuted },
  dot: { width: 6, height: 6, borderRadius: 3, backgroundColor: colors.primary },
  chartFooter: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: spacing.xs },
  chartDate: { color: colors.textMuted, fontSize: font.xs },
  chartLegend: { color: colors.textMuted, fontSize: font.xs, flexShrink: 1, textAlign: 'center' },
  dayCard: { padding: spacing.md },
  dayHeader: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: spacing.sm },
  dayTitle: { fontWeight: '700', color: colors.text, fontSize: font.sm },
  dayMinutes: { color: colors.textMuted, fontSize: font.xs },
  taskRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, paddingVertical: spacing.xs },
  taskTitle: { flex: 1, minWidth: 0, fontSize: font.sm, lineHeight: 18, color: colors.text },
  taskDur: { color: colors.textMuted, fontSize: font.xs, lineHeight: 18 },
  taskAction: { color: colors.primary, fontSize: font.xs, fontWeight: '600', lineHeight: 18 },
  modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'center', padding: spacing.lg },
  modalCard: { backgroundColor: colors.surface, borderRadius: 16, padding: spacing.lg, gap: spacing.xs },
  modalTitle: { fontSize: font.lg, fontWeight: '700', color: colors.text, marginBottom: spacing.xs },
  modalLabel: { fontSize: font.xs, color: colors.textSecondary, marginTop: spacing.sm, marginBottom: 4 },
  optionRow: { flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' },
  option: { paddingVertical: 6, paddingHorizontal: 12, borderRadius: 999, borderWidth: 1, borderColor: colors.border },
  optionActive: { borderColor: colors.primary, backgroundColor: '#EEEEFB' },
  optionText: { color: colors.textSecondary, fontSize: font.sm },
  optionTextActive: { color: colors.primary, fontWeight: '600' },
  input: { borderWidth: 1, borderColor: colors.border, borderRadius: 10, paddingHorizontal: spacing.sm, paddingVertical: 8, fontSize: font.md, color: colors.text },
  modalActions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
});
