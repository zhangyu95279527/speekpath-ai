import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@speakpath/api';
import type { AiModelConfigDto } from '@speakpath/types';
import { Badge, Button, Card, EmptyState, ErrorBanner, Input, Screen, SectionTitle, colors, font, spacing } from '@speakpath/ui';

/**
 * 模型管理后台（AI_ADMIN/SYS_ADMIN）：配置、试连、灰度激活、回滚、审计。
 */
export default function AiModelsAdmin() {
  const queryClient = useQueryClient();
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  const modelsQuery = useQuery({ queryKey: ['ai-models'], queryFn: () => api.get<AiModelConfigDto[]>('/admin/ai-models') });
  const auditQuery = useQuery({ queryKey: ['ai-audit'], queryFn: () => api.get<any[]>('/admin/audit-logs') });
  const models = modelsQuery.data?.data || [];

  const runAction = async (id: string, action: 'test' | 'activate' | 'rollback', reason?: string) => {
    setBusyId(id);
    setError('');
    try {
      const envelope = await api.post<Record<string, unknown>>(
        `/admin/ai-models/${id}/${action}`, reason ? { reason } : {});
      const result = envelope.data as Record<string, unknown>;
      if (action === 'test' && result.passed === false) {
        setError(`试连失败：${String(result.error || 'unknown')}`);
      }
      queryClient.invalidateQueries({ queryKey: ['ai-models'] });
      queryClient.invalidateQueries({ queryKey: ['ai-audit'] });
    } catch (e: any) {
      setError(e?.message || `${action} 失败`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <Screen>
      <ErrorBanner message={error} />
      <SectionTitle>AI 模型配置（能力路由 · 灰度 · 回退）</SectionTitle>
      {models.length === 0 ? (
        <EmptyState title="暂无模型配置" />
      ) : (
        models.map((model) => (
          <Card key={model.configId} style={styles.modelCard}>
            <View style={styles.modelHeader}>
              <Badge text={model.capability} color={colors.primaryLight} />
              <Badge text={model.status} color={model.status === 'ACTIVE' ? colors.success : colors.warning} />
              <Text style={styles.version}>v{model.version}</Text>
            </View>
            <Text style={styles.modelName}>{model.providerCode} / {model.modelCode}</Text>
            <View style={styles.modelMeta}>
              <Text style={styles.metaText}>优先级 {model.priority} · 灰度 {model.trafficPercent}% · 超时 {model.timeoutMs}ms</Text>
              <Text style={styles.metaText}>
                密钥 {model.secretRef || '无'} {model.secretConfigured ? '（已配置）' : '（未配置）'}
              </Text>
            </View>
            <View style={styles.actions}>
              <Button title="试连" variant="ghost" loading={busyId === model.configId}
                      onPress={() => runAction(model.configId, 'test')} />
              <Button title="激活" variant="secondary" loading={busyId === model.configId}
                      onPress={() => runAction(model.configId, 'activate', 'admin activate')} />
              <Button title="回滚" variant="ghost" loading={busyId === model.configId}
                      onPress={() => runAction(model.configId, 'rollback', 'admin rollback')} />
            </View>
          </Card>
        ))
      )}

      <SectionTitle>管理审计（最近变更）</SectionTitle>
      {(auditQuery.data?.data || []).length === 0 ? (
        <EmptyState title="暂无审计记录" />
      ) : (
        (auditQuery.data?.data || []).map((log: any, idx: number) => (
          <Card key={idx} style={styles.auditCard}>
            <Text style={styles.auditAction}>{log.action} · {log.targetType}:{log.targetId}</Text>
            <Text style={styles.auditMeta}>{log.reason} · {new Date(log.createdAt).toLocaleString()}</Text>
          </Card>
        ))
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  modelCard: { padding: spacing.md },
  modelHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.sm },
  version: { marginLeft: 'auto', color: colors.textMuted, fontSize: font.xs },
  modelName: { fontWeight: '700', color: colors.text, fontSize: font.sm, marginBottom: spacing.xs },
  modelMeta: { gap: 2, marginBottom: spacing.sm },
  metaText: { color: colors.textMuted, fontSize: font.xs },
  actions: { flexDirection: 'row', gap: spacing.sm },
  auditCard: { padding: spacing.md },
  auditAction: { fontWeight: '600', fontSize: font.sm, color: colors.text },
  auditMeta: { color: colors.textMuted, fontSize: font.xs, marginTop: spacing.xs },
});
