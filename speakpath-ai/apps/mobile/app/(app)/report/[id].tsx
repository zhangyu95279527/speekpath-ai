import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { assessmentApi } from '@speakpath/assessment';
import { planApi } from '@speakpath/learning';
import { waitForJob } from '@speakpath/api';
import { addDays, todayStr } from '@speakpath/utils';
import type { ReportDto } from '@speakpath/types';
import { Badge, Button, Card, ErrorBanner, Loading, Screen, SectionTitle, colors, font, radius, spacing } from '@speakpath/ui';
import { useState } from 'react';

export default function ReportScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');

  const reportQuery = useQuery({
    queryKey: ['report', id],
    queryFn: () => assessmentApi.report(id as string),
    enabled: !!id, // 路由参数首帧可能未就绪
  });
  const report: ReportDto | undefined = reportQuery.data?.data;

  const generatePlan = async () => {
    setCreating(true);
    setError('');
    try {
      const envelope = await planApi.create({
        reportId: id as string,
        startDate: todayStr(),
        endDate: addDays(todayStr(), 60),
        dailyMinutes: 30,
        restDays: [0],
        timezone: 'Asia/Shanghai',
      });
      await waitForJob(envelope.data.jobId);
      router.replace('/(app)/today');
    } catch (e: any) {
      setError(e?.message || '生成计划失败');
    } finally {
      setCreating(false);
    }
  };

  if (reportQuery.isLoading) return <Loading text="正在加载报告…" />;

  if (!report) {
    return (
      <Screen>
        <ErrorBanner message={String((reportQuery.error as any)?.message || '')} />
        <EmptyReport />
      </Screen>
    );
  }

  return (
    <Screen>
      <ErrorBanner message={error} />
      <Card style={styles.overall}>
        <Text style={styles.overallLabel}>综合估算</Text>
        <Text style={styles.overallScore}>
          {report.overallEstimate.low} – {report.overallEstimate.high}
        </Text>
        <Text style={styles.overallConfidence}>置信度 {Math.round(report.confidence * 100)}%</Text>
        <Text style={styles.disclaimer}>AI 估算仅用于学习参考，不是官方考试成绩。</Text>
      </Card>

      <SectionTitle>维度分析</SectionTitle>
      {report.dimensions.map((dim) => (
        <Card key={dim.code} style={styles.dimCard}>
          <View style={styles.dimHeader}>
            <Text style={styles.dimLabel}>{dim.label}</Text>
            <Badge text={`${dim.score} 分`} color={colors.primary} />
            <Badge text={`${Math.round(dim.confidence * 100)}%`} />
          </View>
          {dim.evidence?.length ? (
            <View style={styles.evidence}>
              <Text style={styles.evidenceQuote}>“{dim.evidence[0].quote}”</Text>
              <Text style={styles.evidenceNote}>{dim.evidence[0].note}</Text>
            </View>
          ) : null}
          {dim.recommendations?.length ? (
            <Text style={styles.recommendation}>建议：{dim.recommendations[0]}</Text>
          ) : null}
        </Card>
      ))}

      {report.topicRelevance ? (
        <>
          <SectionTitle>切题度</SectionTitle>
          <Card style={styles.dimCard}>
            <View style={styles.dimHeader}>
              <Text style={styles.dimLabel}>平均切题度</Text>
              <Badge text={`${Math.round(report.topicRelevance.averageRelevance * 100)}%`} color={colors.primary} />
            </View>
            <Text style={styles.recommendation}>
              共检查 {report.topicRelevance.checkedTurns} 轮，离题 {report.topicRelevance.offTopicCount} 轮
            </Text>
            {report.topicRelevance.offTopicTurns.map((t) => (
              <View key={t.sequence} style={styles.evidence}>
                <Text style={styles.evidenceQuote}>第 {t.sequence} 轮：{t.reason || '回答偏离了题目'}</Text>
              </View>
            ))}
          </Card>
        </>
      ) : null}

      <SectionTitle>接下来</SectionTitle>
      <Card>
        <Text style={styles.nextText}>
          基于本次诊断生成每日学习计划（热身复习 / 专项技能 / 情景口语 / 纠错复述 / 小测总结）。
        </Text>
        <Button title="生成学习计划" onPress={generatePlan} loading={creating} />
      </Card>
    </Screen>
  );
}

function EmptyReport() {
  return (
    <Card style={{ alignItems: 'center', padding: spacing.xxl }}>
      <Text style={{ fontSize: font.md, color: colors.textSecondary }}>报告暂不可用</Text>
      <Button title="返回" variant="ghost" onPress={() => router.back()} />
    </Card>
  );
}

const styles = StyleSheet.create({
  overall: { alignItems: 'center', backgroundColor: colors.primary, borderColor: colors.primary },
  overallLabel: { color: 'rgba(255,255,255,0.85)', fontSize: font.sm },
  overallScore: { color: '#FFFFFF', fontSize: font.huge, fontWeight: '800', marginVertical: spacing.sm },
  overallConfidence: { color: 'rgba(255,255,255,0.9)', fontSize: font.sm },
  disclaimer: { color: 'rgba(255,255,255,0.7)', fontSize: font.xs, marginTop: spacing.md, textAlign: 'center' },
  dimCard: { padding: spacing.md },
  dimHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.sm },
  dimLabel: { fontSize: font.md, fontWeight: '700', color: colors.text, flex: 1 },
  evidence: { backgroundColor: colors.chip, borderRadius: radius, padding: spacing.md, marginBottom: spacing.sm },
  evidenceQuote: { fontStyle: 'italic', color: colors.textSecondary, fontSize: font.sm },
  evidenceNote: { color: colors.textMuted, fontSize: font.xs, marginTop: spacing.xs },
  recommendation: { color: colors.textSecondary, fontSize: font.sm },
  nextText: { color: colors.textSecondary, fontSize: font.sm, marginBottom: spacing.md },
});
