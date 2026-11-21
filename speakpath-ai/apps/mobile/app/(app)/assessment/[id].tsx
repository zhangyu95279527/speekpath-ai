import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { assessmentApi } from '@speakpath/assessment';
import { getAudioService } from '@speakpath/audio';
import { api, waitForJob } from '@speakpath/api';
import { idemKey } from '@speakpath/utils';
import type { QuestionDto, SessionStatusDto } from '@speakpath/types';
import { Badge, Button, Card, ErrorBanner, Input, Screen, colors, font, spacing } from '@speakpath/ui';

/**
 * 口语诊断页面：题目、倒计时、录音/文本降级、重试、进度、结束测评。
 */
export default function AssessmentScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const sessionId = id as string;

  const [question, setQuestion] = useState<QuestionDto | null>(null);
  const [status, setStatus] = useState<SessionStatusDto | null>(null);
  const [answer, setAnswer] = useState('');
  const [recording, setRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [remainingSeconds, setRemainingSeconds] = useState(45);
  const [permission, setPermission] = useState<string>('undetermined');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadStatus = useCallback(async () => {
    if (!sessionId) return;
    try {
      const envelope = await assessmentApi.status(sessionId);
      setStatus(envelope.data);
    } catch (e: any) {
      setError(e?.message || '加载测评失败');
    }
  }, [sessionId]);

  const loadFirstQuestion = useCallback(async () => {
    if (!sessionId) return;
    try {
      // 从 session 状态恢复当前轮次与题目（断网重进恢复，ASMT-009）
      const envelope = await assessmentApi.status(sessionId);
      setStatus(envelope.data);
      if (envelope.data.currentQuestion) {
        setQuestion(envelope.data.currentQuestion);
      }
    } catch (e: any) {
      setError(e?.message || '恢复测评失败');
    }
  }, [sessionId]);

  useEffect(() => {
    loadFirstQuestion();
    getAudioService().requestPermission().then(setPermission);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [loadFirstQuestion]);

  // 倒计时
  useEffect(() => {
    if (question) {
      setRemainingSeconds(question.responseTimeSeconds);
      if (timerRef.current) clearInterval(timerRef.current);
      timerRef.current = setInterval(() => {
        setRemainingSeconds((prev) => {
          if (prev <= 1) {
            if (timerRef.current) clearInterval(timerRef.current);
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [question]);

  const startRecording = async () => {
    setError('');
    try {
      const audio = getAudioService();
      const perm = await audio.requestPermission();
      setPermission(perm);
      if (perm !== 'granted') {
        setError('麦克风权限被拒绝，可使用文本模式继续');
        return;
      }
      await audio.startCapture({ mimeType: 'audio/webm' });
      setRecording(true);
      setRecordingSeconds(0);
      const start = Date.now();
      if (timerRef.current) clearInterval(timerRef.current);
      timerRef.current = setInterval(() => setRecordingSeconds(Math.round((Date.now() - start) / 1000)), 500);
    } catch (e: any) {
      setError(e?.message || '无法开始录音，请使用文本模式');
    }
  };

  const stopRecordingAndSubmit = async (textOverride?: string) => {
    if (timerRef.current) clearInterval(timerRef.current);
    setBusy(true);
    setError('');
    try {
      const audio = getAudioService();
      let audioObjectKey: string | null = null;
      let durationMs: number | undefined;
      if (recording) {
        const artifact = await audio.stopCapture();
        setRecording(false);
        durationMs = artifact.durationMs;
        // 上传到预签名 URL（私有桶）
        const presign = await api.post<{ objectKey: string; uploadUrl: string }>('/media/presign-upload', {
          fileName: 'assessment.webm', contentType: artifact.mimeType,
        });
        await fetch(presign.data.uploadUrl, {
          method: 'PUT', body: artifact.blob, headers: { 'Content-Type': artifact.mimeType },
        });
        audioObjectKey = presign.data.objectKey;
      }
      const seq = (status?.currentSequence ?? 1);
      const envelope = await assessmentApi.submitTurn(sessionId, {
        sequence: seq,
        questionId: question?.questionId,
        question: question?.question,
        section: question?.section,
        difficulty: question?.difficulty,
        answerText: textOverride ?? (audioObjectKey ? null : answer.trim() || null),
        audioObjectKey,
        durationMs,
      }, idemKey());
      const result = envelope.data;
      setStatus((prev) => prev ? {
        ...prev, status: result.status, turnCount: result.turnCount,
        currentSequence: result.turnCount + 1, canFinish: result.canFinish,
      } : prev);
      setAnswer('');
      setQuestion(result.nextQuestion);
      await loadStatus();
    } catch (e: any) {
      setError(e?.message || '提交失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  const finishAssessment = async () => {
    if (!status || status.turnCount < status.minTurns) {
      setError(`至少完成 ${status?.minTurns ?? 10} 轮才能结束测评`);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const envelope = await assessmentApi.finish(sessionId);
      const job = await waitForJob(envelope.data.jobId);
      const reportRef = job.resultRef.replace('report:', '');
      router.replace({ pathname: '/(app)/report/[id]', params: { id: reportRef } });
    } catch (e: any) {
      setError(e?.message || '生成报告失败');
    } finally {
      setBusy(false);
    }
  };

  const progress = status ? Math.min(status.turnCount / status.maxTurns, 1) : 0;

  return (
    <Screen>
      <Card>
        <View style={styles.headerRow}>
          <Badge text={`第 ${status?.turnCount ?? 0}/${status?.maxTurns ?? 15} 轮`} />
          <Badge text={`已进行 ${status?.turnCount ?? 0} 轮`} />
        </View>
        <View style={styles.progressTrack}>
          <View style={[styles.progressFill, { width: `${Math.round(progress * 100)}%` }]} />
        </View>
        {question ? (
          <>
            <Text style={styles.section}>{question.section} · 难度 {question.difficulty}/10</Text>
            <Text style={styles.question}>{question.question}</Text>
            <Text style={styles.timer}>
              {recording ? `录音中 ${recordingSeconds}s` : `建议回答 ${question.responseTimeSeconds}s · 剩余 ${remainingSeconds}s`}
            </Text>
          </>
        ) : (
          <Text style={styles.question}>
            {status && status.turnCount >= (status.maxTurns ?? 15)
              ? '已完成全部轮次，可以结束测评生成报告。'
              : '准备下一题…'}
          </Text>
        )}
      </Card>

      <ErrorBanner message={error} />

      <Card>
        <Text style={styles.label}>回答（支持文本降级模式）</Text>
        <Input
          value={answer}
          onChangeText={setAnswer}
          placeholder="输入你的英语回答，或直接录音…"
          multiline
        />
        <View style={styles.actions}>
          <Button title={recording ? '停止并提交' : '🎤 录音'}
                  variant={recording ? 'danger' : 'secondary'}
                  disabled={busy || permission !== 'granted' && recording}
                  onPress={() => (recording ? stopRecordingAndSubmit() : startRecording())} />
          <Button title="提交文本回答" disabled={busy || !answer.trim()}
                  onPress={() => stopRecordingAndSubmit(answer.trim())} />
        </View>
        <Text style={styles.hint}>
          {permission === 'denied' ? '麦克风权限被拒绝：可使用文本模式完成诊断。' : '录音将上传至私有存储，仅用于本次测评。'}
        </Text>
      </Card>

      <Button title={status?.canFinish ? '结束测评并生成报告' : `完成 ${status?.minTurns ?? 10} 轮后可结束`}
              disabled={!status?.canFinish || busy}
              variant={status?.canFinish ? 'primary' : 'ghost'}
              onPress={finishAssessment} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  headerRow: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.md },
  progressTrack: { height: 8, backgroundColor: colors.chip, borderRadius: 4, marginBottom: spacing.lg },
  progressFill: { height: 8, backgroundColor: colors.primary, borderRadius: 4 },
  section: { color: colors.textMuted, fontSize: font.xs, textTransform: 'uppercase', marginBottom: spacing.sm },
  question: { fontSize: font.lg, fontWeight: '700', color: colors.text, lineHeight: 28, marginBottom: spacing.sm },
  timer: { color: colors.textSecondary, fontSize: font.sm, marginTop: spacing.sm },
  label: { fontWeight: '600', color: colors.textSecondary, marginBottom: spacing.sm, fontSize: font.sm },
  actions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  hint: { color: colors.textMuted, fontSize: font.xs, marginTop: spacing.md },
});
