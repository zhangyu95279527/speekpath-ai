import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '@speakpath/api';
import type { ExamDto } from '@speakpath/types';
import { Button, Card, Chip, ErrorBanner, Screen, colors, font, spacing } from '@speakpath/ui';

export default function ExamOnboarding() {
  const [examCode, setExamCode] = useState('IELTS_SPEAKING');
  const [targetScore, setTargetScore] = useState<number | null>(6.5);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);

  const examsQuery = useQuery({ queryKey: ['exams'], queryFn: () => api.get<ExamDto[]>('/exams') });
  const exams = examsQuery.data?.data || [];
  const current = exams.find((e) => e.examCode === examCode);
  const scoreOptions = current
    ? Array.from({ length: Math.floor(current.scoreRange.max - current.scoreRange.min) * 2 + 1 },
        (_, i) => Math.round((current.scoreRange.min + i * 0.5) * 10) / 10)
    : [];

  const start = async () => {
    setError('');
    setCreating(true);
    try {
      const envelope = await api.post<{ sessionId: string; firstQuestion: any }>('/assessment/sessions', {
        examCode, targetScore, inputMode: 'AUDIO', locale: 'en-US',
      });
      router.replace({ pathname: '/(app)/assessment/[id]', params: { id: envelope.data.sessionId } });
    } catch (e: any) {
      setError(e?.message || '创建测评失败');
    } finally {
      setCreating(false);
    }
  };

  return (
    <Screen>
      <Card>
        <Text style={styles.title}>选择目标考试</Text>
        <Text style={styles.desc}>完成 10–15 轮自适应口语诊断，生成你的能力画像</Text>
        <ErrorBanner message={error} />
        <View style={styles.examRow}>
          {exams.map((exam) => (
            <Chip key={exam.examCode} label={exam.name} active={examCode === exam.examCode}
                  onPress={() => { setExamCode(exam.examCode); setTargetScore(null); }} />
          ))}
        </View>
        <Text style={styles.label}>目标分数（可选，用于差距分析）</Text>
        <View style={styles.scoreRow}>
          {scoreOptions.map((score) => (
            <Chip key={score} label={String(score)} active={targetScore === score}
                  onPress={() => setTargetScore(score)} />
          ))}
        </View>
        <Text style={styles.note}>
          诊断共 {current?.defaultTurns ?? 12} 轮（最少 {current?.minTurns ?? 10}、最多 {current?.maxTurns ?? 15} 轮），
          支持录音或文本回答。建议使用安静环境。
        </Text>
        <Button title="开始诊断" onPress={start} loading={creating} style={{ marginTop: spacing.sm }} />
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: font.xl, fontWeight: '800', color: colors.text },
  desc: { color: colors.textSecondary, marginTop: spacing.xs, marginBottom: spacing.md, fontSize: font.sm },
  examRow: { flexDirection: 'row', flexWrap: 'wrap', marginBottom: spacing.md },
  label: { fontWeight: '600', color: colors.textSecondary, marginBottom: spacing.sm, fontSize: font.sm },
  scoreRow: { flexDirection: 'row', flexWrap: 'wrap', marginBottom: spacing.md },
  note: { color: colors.textMuted, fontSize: font.xs, lineHeight: 18, marginBottom: spacing.sm },
});
