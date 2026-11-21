import type { CreateAssessmentResponse, QuestionDto, ReportDto, SessionStatusDto } from '@speakpath/types';
import { api } from '@speakpath/api';

/**
 * 测评流程状态与 API 封装（诊断会话）。
 */
export interface AssessmentFlowState {
  session: CreateAssessmentResponse | null;
  currentQuestion: QuestionDto | null;
  status: SessionStatusDto | null;
}

export const assessmentApi = {
  create: (examCode: string, targetScore: number | null, inputMode: 'AUDIO' | 'TEXT' = 'TEXT') =>
    api.post<CreateAssessmentResponse>('/assessment/sessions', {
      examCode, targetScore, inputMode, locale: 'en-US',
    }),

  status: (sessionId: string) => api.get<SessionStatusDto>(`/assessment/sessions/${sessionId}`),

  submitTurn: (sessionId: string, input: {
    sequence: number;
    questionId?: string;
    question?: string;
    section?: string;
    difficulty?: number;
    answerText?: string | null;
    audioObjectKey?: string | null;
    durationMs?: number;
  }, idemKey: string) => api.post(
    `/assessment/sessions/${sessionId}/turns`, input, { 'Idempotency-Key': idemKey },
  ),

  finish: (sessionId: string) => api.post<{ jobId: string; status: string }>(`/assessment/sessions/${sessionId}/finish`),

  report: (reportId: string) => api.get<ReportDto>(`/assessment/reports/${reportId}`),

  reportBySession: (sessionId: string) => api.get<ReportDto>(`/assessment/sessions/${sessionId}/report`),
};
