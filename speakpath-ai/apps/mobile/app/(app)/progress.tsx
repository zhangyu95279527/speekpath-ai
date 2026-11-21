import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { api } from '@speakpath/api';
import type { ProgressSummaryDto } from '@speakpath/types';
import { Card, EmptyState, Loading, Screen, SectionTitle, colors, font, spacing } from '@speakpath/ui';
import { formatMinutes } from '@speakpath/utils';

interface DailyReportDto {
  reportDate: string;
  tasksTotal: number;
  tasksDone: number;
  speakingSessions: number;
  speakingMinutes: number;
  turnsCount: number;
  pronunciationAvg: number | null;
  score: number | null;
  summary: string;
  suggestions: string[];
}

/** 统计卡片内的紧凑时长：避免窄屏换行，如 "5时21分"。 */
function compactMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes}分`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h}时` : `${h}时${m}分`;
}

export default function ProgressScreen() {
  const query = useQuery({ queryKey: ['progress'], queryFn: () => api.get<ProgressSummaryDto>('/progress/summary') });
  const reportsQuery = useQuery({
    queryKey: ['progress', 'daily-reports'],
    queryFn: () => api.get<DailyReportDto[]>('/progress/daily-reports'),
  });
  const data = query.data?.data;
  const reports = reportsQuery.data?.data || [];

  if (query.isLoading) return <Loading text="加载进度…" />;

  if ((!data || data.completedReports === 0 && data.completedTasks === 0) && reports.length === 0) {
    return (
      <Screen>
        <EmptyState title="还没有学习记录" hint="完成今日任务与口语诊断后，这里会展示能力趋势与错误分布" />
      </Screen>
    );
  }

  if (!data) {
    return (
      <Screen>
        <SectionTitle>每日学习评价</SectionTitle>
        {reports.length === 0 ? (
          <EmptyState title="暂无每日评价" hint="每天 0 点后自动分析前一天的学习结果" />
        ) : (
          reports.map((r) => <DailyReportCard key={r.reportDate} report={r} />)
        )}
      </Screen>
    );
  }

  const latest = data.dimensionTrend[data.dimensionTrend.length - 1];

  return (
    <Screen>
      <View style={styles.statsRow}>
        <Card style={styles.statCard}>
          <Text style={styles.statValue} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6}>
            {data.completedTasks}
          </Text>
          <Text style={styles.statLabel}>练习任务</Text>
        </Card>
        <Card style={styles.statCard}>
          <Text style={styles.statValue} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6}>
            {compactMinutes(data.totalPracticeMinutes)}
          </Text>
          <Text style={styles.statLabel}>练习时长</Text>
        </Card>
        <Card style={styles.statCard}>
          <Text style={styles.statValue} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6}>
            {data.completedReports}
          </Text>
          <Text style={styles.statLabel}>完成测评</Text>
        </Card>
      </View>

      <SectionTitle>每日学习评价</SectionTitle>
      {reports.length === 0 ? (
        <EmptyState title="暂无每日评价" hint="每天 0 点后自动分析前一天的学习结果" />
      ) : (
        reports.map((r) => <DailyReportCard key={r.reportDate} report={r} />)
      )}

      <SectionTitle>最近测评维度</SectionTitle>
      {latest ? (
        <Card>
          <View style={styles.overallRow}>
            <Text style={styles.overallScore}>
              {latest.overallLow} – {latest.overallHigh}
            </Text>
            <Text style={styles.overallConfidence}>置信度 {Math.round(latest.confidence * 100)}%</Text>
          </View>
          {Object.entries(latest.dimensions).map(([code, score]) => (
            <View key={code} style={styles.dimRow}>
              <Text style={styles.dimCode}>{code}</Text>
              <View style={styles.dimTrack}>
                <View style={[styles.dimFill, { width: `${Math.min(100, (score / 9) * 100)}%` }]} />
              </View>
              <Text style={styles.dimScore}>{score}</Text>
            </View>
          ))}
        </Card>
      ) : null}

      <SectionTitle>{`错误分布（${data.examName || 'IELTS'} 口语纠错类别）`}</SectionTitle>
      {Object.keys(data.errorDistribution).length > 0 ? (
        <Card>
          {Object.entries(data.errorDistribution).map(([category, count]) => (
            <View key={category} style={styles.dimRow}>
              <Text style={styles.dimCode}>{data.errorDistributionLabels?.[category] || category}</Text>
              <Text style={styles.dimScore}>{count}</Text>
            </View>
          ))}
        </Card>
      ) : (
        <EmptyState title="暂无纠错数据" hint="完成 AI 口语陪练后生成" />
      )}
    </Screen>
  );
}

function DailyReportCard({ report }: { report: DailyReportDto }) {
  return (
    <Card style={styles.reportCard}>
      <View style={styles.reportHeader}>
        <Text style={styles.reportDate}>{report.reportDate}</Text>
        {report.score !== null ? <Text style={styles.reportScore}>{report.score} 分</Text> : null}
      </View>
      <Text style={styles.reportSummary}>{report.summary || '昨日暂无学习记录'}</Text>
      <View style={styles.reportStats}>
        <Text style={styles.reportStat}>口语 {formatMinutes(report.speakingMinutes)}</Text>
        <Text style={styles.reportStat}>对话 {report.turnsCount} 轮</Text>
        {report.pronunciationAvg !== null ? (
          <Text style={styles.reportStat}>发音 {report.pronunciationAvg} 分</Text>
        ) : null}
      </View>
      {(report.suggestions || []).map((s, i) => (
        <Text key={i} style={styles.reportSuggestion}>• {s}</Text>
      ))}
    </Card>
  );
}

const styles = StyleSheet.create({
  statsRow: { flexDirection: 'row', gap: spacing.sm },
  statCard: { flex: 1, minWidth: 0, alignItems: 'center', paddingVertical: spacing.md, paddingHorizontal: spacing.xs },
  statValue: { width: '100%', textAlign: 'center', fontSize: font.md, fontWeight: '800', color: colors.primary },
  statLabel: { fontSize: font.xs, color: colors.textMuted, marginTop: spacing.xs },
  overallRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.md },
  overallScore: { fontSize: font.xxl, fontWeight: '800', color: colors.text },
  overallConfidence: { color: colors.textMuted, fontSize: font.xs },
  dimRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.xs },
  dimCode: { width: 170, fontSize: font.xs, color: colors.textSecondary, fontWeight: '600' },
  dimTrack: { flex: 1, height: 8, backgroundColor: colors.chip, borderRadius: 4 },
  dimFill: { height: 8, backgroundColor: colors.primary, borderRadius: 4 },
  dimScore: { width: 40, textAlign: 'right', fontSize: font.sm, fontWeight: '700', color: colors.text },
  reportCard: { marginBottom: spacing.sm },
  reportHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  reportDate: { fontSize: font.sm, fontWeight: '700', color: colors.text },
  reportScore: { fontSize: font.md, fontWeight: '800', color: colors.primary },
  reportSummary: { fontSize: font.sm, color: colors.textSecondary, lineHeight: 20, marginTop: spacing.xs },
  reportStats: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.sm, marginBottom: spacing.xs },
  reportStat: { fontSize: font.xs, color: colors.textMuted },
  reportSuggestion: { fontSize: font.xs, color: colors.text, lineHeight: 18, marginTop: 2 },
});
