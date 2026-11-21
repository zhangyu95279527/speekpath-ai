import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '@speakpath/api';
import { useAuth } from '@speakpath/auth';
import type { UserDto } from '@speakpath/types';
import { Button, Card, Screen, SectionTitle, colors, font, spacing } from '@speakpath/ui';

export default function SettingsScreen() {
  const { logout, user } = useAuth();
  const meQuery = useQuery({ queryKey: ['me'], queryFn: () => api.get<UserDto>('/users/me') });
  const me = meQuery.data?.data || user;

  const doLogout = async () => {
    await logout();
    router.replace('/(auth)/login');
  };

  return (
    <Screen>
      <Card>
        <Text style={styles.name}>{me?.displayName || me?.email}</Text>
        <Text style={styles.meta}>{me?.email}</Text>
        {me?.preferences ? (
          <View style={styles.prefRow}>
            <Text style={styles.pref}>目标考试：{me.preferences.targetExam || '未设置'}</Text>
            <Text style={styles.pref}>目标分数：{me.preferences.targetScore ?? '未设置'}</Text>
            <Text style={styles.pref}>每日时间：{me.preferences.dailyMinutes} 分钟</Text>
            <Text style={styles.pref}>时区：{me?.timezone}</Text>
          </View>
        ) : null}
      </Card>

      <SectionTitle>学习设置</SectionTitle>
      <Card>
        <Button title="修改目标与偏好" variant="secondary"
                onPress={() => router.push('/(app)/onboarding/exam')} />
        {me?.roles?.includes('AI_ADMIN') || me?.roles?.includes('SYS_ADMIN') ? (
          <View style={{ marginTop: spacing.sm }}>
            <Button title="AI 模型管理后台" variant="secondary"
                    onPress={() => router.push('/(app)/admin/ai-models')} />
          </View>
        ) : null}
      </Card>

      <SectionTitle>隐私与数据</SectionTitle>
      <Card>
        <Text style={styles.privacy}>
          你的练习音频仅用于 AI 测评与纠错，存储于私有对象存储；可随时申请导出或删除。
          AI 评分仅供学习参考，不是官方考试成绩。
        </Text>
      </Card>

      <SectionTitle>账号</SectionTitle>
      <Card>
        <Button title="退出登录" variant="danger" onPress={doLogout} />
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  name: { fontSize: font.xl, fontWeight: '800', color: colors.text },
  meta: { color: colors.textMuted, fontSize: font.sm, marginTop: spacing.xs },
  prefRow: { marginTop: spacing.md, gap: spacing.xs },
  pref: { color: colors.textSecondary, fontSize: font.sm },
  privacy: { color: colors.textSecondary, fontSize: font.sm, lineHeight: 20 },
});
