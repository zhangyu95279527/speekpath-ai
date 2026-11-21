import React, { useState } from 'react';
import { Text, View, StyleSheet } from 'react-native';
import { router, Link } from 'expo-router';
import { useAuth } from '@speakpath/auth';
import { Button, Card, ErrorBanner, Input, Screen, colors, font, spacing } from '@speakpath/ui';

export default function Login() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setError('');
    setLoading(true);
    try {
      await login(email.trim(), password);
      router.replace('/(app)/today');
    } catch (e: any) {
      setError(e?.message || '登录失败，请检查邮箱与密码');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Screen scroll style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.logo}>SpeakPath AI</Text>
        <Text style={styles.subtitle}>AI 英语口语测评与自适应训练</Text>
      </View>
      <Card>
        <ErrorBanner message={error} />
        <Input label="邮箱" value={email} onChangeText={setEmail} placeholder="learner@example.com"
               keyboardType="email-address" autoCapitalize="none" />
        <Input label="密码" value={password} onChangeText={setPassword} placeholder="至少 8 位"
               secureTextEntry />
        <Button title="登录" onPress={submit} loading={loading} style={{ marginTop: spacing.sm }} />
        <View style={styles.row}>
          <Text style={styles.hint}>还没有账号？</Text>
          <Link href="/(auth)/register" style={styles.link}>立即注册</Link>
        </View>
      </Card>
      <Text style={styles.note}>提示：AI 评分仅供学习参考，不是官方考试成绩。</Text>
    </Screen>
  );
}

const styles = StyleSheet.create({
  container: { justifyContent: 'center', flexGrow: 1 },
  header: { alignItems: 'center', marginBottom: spacing.xl },
  logo: { fontSize: font.huge, fontWeight: '800', color: colors.primary },
  subtitle: { color: colors.textSecondary, marginTop: spacing.sm, fontSize: font.sm },
  row: { flexDirection: 'row', justifyContent: 'center', marginTop: spacing.lg, gap: spacing.xs },
  hint: { color: colors.textMuted, fontSize: font.sm },
  link: { color: colors.primary, fontWeight: '600', fontSize: font.sm },
  note: { textAlign: 'center', color: colors.textMuted, fontSize: font.xs, marginTop: spacing.xl },
});
