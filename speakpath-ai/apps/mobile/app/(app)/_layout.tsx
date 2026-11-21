import React from 'react';
import { Redirect, Tabs } from 'expo-router';
import { Text } from 'react-native';
import { useAuth } from '@speakpath/auth';
import { colors } from '@speakpath/ui';

function TabIcon({ label }: { label: string }) {
  return <Text style={{ fontSize: 18 }}>{label}</Text>;
}

export default function AppLayout() {
  const { status } = useAuth();
  if (status === 'loading') return null;
  if (status !== 'authenticated') return <Redirect href="/(auth)/login" />;

  return (
    <Tabs
      screenOptions={{
        headerShown: true,
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: colors.textMuted,
        headerTitleStyle: { fontWeight: '700' },
        headerStyle: { backgroundColor: colors.surface },
      }}
    >
      <Tabs.Screen name="today" options={{ title: '今日任务', tabBarIcon: () => <TabIcon label="📋" /> }} />
      <Tabs.Screen name="plan" options={{ title: '学习计划', tabBarIcon: () => <TabIcon label="🗓️" /> }} />
      <Tabs.Screen name="progress" options={{ title: '学习进度', tabBarIcon: () => <TabIcon label="📈" /> }} />
      <Tabs.Screen name="settings" options={{ title: '设置', tabBarIcon: () => <TabIcon label="⚙️" /> }} />
      <Tabs.Screen name="onboarding/exam" options={{ href: null, title: '目标设置' }} />
      <Tabs.Screen name="assessment/[id]" options={{ href: null, title: '口语诊断' }} />
      <Tabs.Screen name="report/[id]" options={{ href: null, title: '测评报告' }} />
      <Tabs.Screen name="speaking/[sessionId]" options={{ href: null, title: 'AI 口语陪练' }} />
      <Tabs.Screen name="task/[taskId]" options={{ href: null, title: '任务练习' }} />
      <Tabs.Screen name="admin/ai-models" options={{ href: null, title: '模型管理' }} />
    </Tabs>
  );
}
