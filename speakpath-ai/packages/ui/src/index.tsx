import React from 'react';
import {
  ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View,
  ViewStyle, TextStyle, ScrollView,
} from 'react-native';

/**
 * 共享 UI：设计 token（颜色/字号/间距/圆角）与基础组件，三端一致。
 */

export const colors = {
  primary: '#5B5BD6',
  primaryDark: '#4646B0',
  primaryLight: '#EEEEFB',
  background: '#F6F7FB',
  surface: '#FFFFFF',
  text: '#1A1D2B',
  textSecondary: '#5C6270',
  textMuted: '#9AA0AE',
  border: '#E4E7EF',
  success: '#2FA36B',
  warning: '#D98E2B',
  danger: '#D64545',
  accent: '#7C5BEA',
  score: '#5B5BD6',
  chip: '#EDF0F7',
};

export const spacing = {
  xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32,
};

export const radius = {
  sm: 8, md: 12, lg: 16, full: 999,
};

export const font = {
  xs: 12, sm: 14, md: 16, lg: 18, xl: 22, xxl: 28, huge: 36,
};

export function dimStyle(code: string): ViewStyle {
  return { backgroundColor: colors.background };
}

// ---- 组件 ----

export function Screen({ children, scroll = true, style }: {
  children: React.ReactNode; scroll?: boolean; style?: ViewStyle;
}) {
  if (!scroll) {
    return <View style={[styles.screen, style]}>{children}</View>;
  }
  return (
    <ScrollView style={styles.screen} contentContainerStyle={[styles.screenContent, style]}>
      {children}
    </ScrollView>
  );
}

export function Card({ children, style }: { children: React.ReactNode; style?: ViewStyle }) {
  return <View style={[styles.card, style]}>{children}</View>;
}

export function Button({ title, onPress, variant = 'primary', disabled, loading, style }: {
  title: string;
  onPress?: () => void;
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  disabled?: boolean;
  loading?: boolean;
  style?: ViewStyle;
}) {
  const bg = variant === 'primary' ? colors.primary
    : variant === 'danger' ? colors.danger
      : variant === 'secondary' ? colors.primaryLight : 'transparent';
  const fg = variant === 'ghost' ? colors.primary : variant === 'secondary' ? colors.primaryDark : '#FFFFFF';
  return (
    <Pressable
      accessibilityRole="button"
      onPress={disabled || loading ? undefined : onPress}
      style={({ pressed }) => [
        styles.button, { backgroundColor: bg, opacity: disabled ? 0.45 : pressed ? 0.85 : 1 }, style,
      ]}
    >
      {loading ? <ActivityIndicator color={fg} /> : <Text style={[styles.buttonText, { color: fg }]}>{title}</Text>}
    </Pressable>
  );
}

export function Input({ label, value, onChangeText, placeholder, secureTextEntry, keyboardType, multiline, style, error }: {
  label?: string;
  value: string;
  onChangeText: (text: string) => void;
  placeholder?: string;
  secureTextEntry?: boolean;
  keyboardType?: 'default' | 'email-address' | 'numeric' | 'phone-pad';
  multiline?: boolean;
  style?: ViewStyle;
  error?: string;
}) {
  return (
    <View style={[styles.field, style]}>
      {label ? <Text style={styles.label}>{label}</Text> : null}
      <TextInput
        accessibilityLabel={label || placeholder}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={colors.textMuted}
        secureTextEntry={secureTextEntry}
        keyboardType={keyboardType}
        multiline={multiline}
        style={[styles.input, multiline ? { minHeight: 96, textAlignVertical: 'top' } : null]}
      />
      {error ? <Text style={styles.errorText}>{error}</Text> : null}
    </View>
  );
}

export function Chip({ label, active, onPress }: { label: string; active?: boolean; onPress?: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={[styles.chip, active ? { backgroundColor: colors.primary, borderColor: colors.primary } : null]}
    >
      <Text style={[styles.chipText, active ? { color: '#FFFFFF' } : null]}>{label}</Text>
    </Pressable>
  );
}

export function SectionTitle({ children }: { children: React.ReactNode }) {
  return <Text style={styles.sectionTitle}>{children}</Text>;
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <Card style={{ alignItems: 'center', padding: spacing.xxl }}>
      <Text style={styles.emptyTitle}>{title}</Text>
      {hint ? <Text style={styles.emptyHint}>{hint}</Text> : null}
    </Card>
  );
}

export function ErrorBanner({ message }: { message: string }) {
  if (!message) return null;
  return (
    <View style={styles.errorBanner}>
      <Text style={styles.errorBannerText}>{message}</Text>
    </View>
  );
}

export function Loading({ text }: { text?: string }) {
  return (
    <View style={styles.loading}>
      <ActivityIndicator size="large" color={colors.primary} />
      {text ? <Text style={styles.loadingText}>{text}</Text> : null}
    </View>
  );
}

export function Badge({ text, color }: { text: string; color?: string }) {
  return (
    <View style={[styles.badge, color ? { backgroundColor: color } : null]}>
      <Text style={styles.badgeText}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  screenContent: { padding: spacing.lg, paddingBottom: spacing.xxl },
  card: {
    backgroundColor: colors.surface, borderRadius: radius.lg, padding: spacing.lg,
    borderWidth: StyleSheet.hairlineWidth, borderColor: colors.border, marginBottom: spacing.md,
  },
  button: {
    borderRadius: radius.md, paddingVertical: 14, paddingHorizontal: spacing.lg,
    alignItems: 'center', justifyContent: 'center', minHeight: 48,
  },
  buttonText: { fontSize: font.md, fontWeight: '600' },
  field: { marginBottom: spacing.md },
  label: { fontSize: font.sm, color: colors.textSecondary, marginBottom: spacing.xs, fontWeight: '600' },
  input: {
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border,
    borderRadius: radius.md, paddingHorizontal: spacing.md, paddingVertical: 12,
    fontSize: font.md, color: colors.text,
  },
  errorText: { color: colors.danger, fontSize: font.xs, marginTop: spacing.xs },
  chip: {
    borderWidth: 1, borderColor: colors.border, backgroundColor: colors.chip,
    borderRadius: radius.full, paddingHorizontal: spacing.md, paddingVertical: 8, marginRight: spacing.sm, marginBottom: spacing.sm,
  },
  chipText: { color: colors.textSecondary, fontSize: font.sm, fontWeight: '500' },
  sectionTitle: { fontSize: font.lg, fontWeight: '700', color: colors.text, marginBottom: spacing.md, marginTop: spacing.sm },
  emptyTitle: { fontSize: font.md, fontWeight: '600', color: colors.textSecondary },
  emptyHint: { fontSize: font.sm, color: colors.textMuted, marginTop: spacing.sm, textAlign: 'center' },
  errorBanner: {
    backgroundColor: '#FDECEC', borderWidth: 1, borderColor: '#F5C6C6',
    borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.md,
  },
  errorBannerText: { color: colors.danger, fontSize: font.sm },
  loading: { alignItems: 'center', justifyContent: 'center', padding: spacing.xxl },
  loadingText: { color: colors.textSecondary, marginTop: spacing.md, fontSize: font.sm },
  badge: {
    backgroundColor: colors.primaryLight, borderRadius: radius.full,
    paddingHorizontal: spacing.sm, paddingVertical: 2,
  },
  badgeText: { color: colors.primaryDark, fontSize: font.xs, fontWeight: '600' },
});
