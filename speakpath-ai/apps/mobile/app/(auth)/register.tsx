import React, { useState } from 'react';
import { Text, View, StyleSheet } from 'react-native';
import { router, Link } from 'expo-router';
import { useAuth } from '@speakpath/auth';
import { Button, Card, ErrorBanner, Input, Screen, colors, font, spacing } from '@speakpath/ui';

export default function Register() {
  const { register } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [agreed, setAgreed] = useState(true);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setError('');
    if (!agreed) {
      setError('请先同意隐私条款与音频处理授权');
      return;
    }
    setLoading(true);
    try {
      await register({
        email: email.trim(),
        password,
        displayName: displayName.trim() || undefined,
        locale: 'zh-CN',
        timezone: 'Asia/Shanghai',
        consents: { termsVersion: '2026-01', audioProcessing: true },
      });
      router.replace('/(app)/onboarding/exam');
    } catch (e: any) {
      setError(e?.message || '注册失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Screen scroll style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.logo}>创建账号</Text>
        <Text style={styles.subtitle}>开始你的 AI 口语提升之旅</Text>
      </View>
      <Card>
        <ErrorBanner message={error} />
        <Input label="昵称" value={displayName} onChangeText={setDisplayName} placeholder="Alex" />
        <Input label="邮箱" value={email} onChangeText={setEmail} placeholder="learner@example.com"
               keyboardType="email-address" />
        <Input label="密码" value={password} onChangeText={setPassword} placeholder="至少 8 位" secureTextEntry />
        <View style={styles.consentRow}>
          <Text style={styles.consent}>
            注册即表示同意《服务条款》与《隐私政策》，并授权 SpeakPath 处理练习音频用于 AI 测评与纠错（可随时撤回）。
          </Text>
        </View>
        <Button title="注册并开始" onPress={submit} loading={loading} />
        <View style={styles.row}>
          <Text style={styles.hint}>已有账号？</Text>
          <Link href="/(auth)/login" style={styles.link}>去登录</Link>
        </View>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  container: { justifyContent: 'center', flexGrow: 1 },
  header: { alignItems: 'center', marginBottom: spacing.xl },
  logo: { fontSize: font.xl, fontWeight: '800', color: colors.text },
  subtitle: { color: colors.textSecondary, marginTop: spacing.sm, fontSize: font.sm },
  consentRow: { marginBottom: spacing.md },
  consent: { color: colors.textMuted, fontSize: font.xs, lineHeight: 18 },
  row: { flexDirection: 'row', justifyContent: 'center', marginTop: spacing.lg, gap: spacing.xs },
  hint: { color: colors.textMuted, fontSize: font.sm },
  link: { color: colors.primary, fontWeight: '600', fontSize: font.sm },
});
