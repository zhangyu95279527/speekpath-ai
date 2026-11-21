// =====================================================================
// SpeakPath AI 共享 DTO、枚举与事件类型（与后端 API 契约对齐）
// =====================================================================

export interface ApiEnvelope<T> {
  code: string;
  message?: string;
  data: T;
  traceId: string;
  timestamp: string;
}

// ---- 认证 ----
export interface UserDto {
  id: string;
  email: string;
  displayName: string;
  locale: string;
  timezone: string;
  status?: string;
  roles?: string[];
  createdAt?: string;
  preferences?: UserPreferencesDto;
}

export interface UserPreferencesDto {
  dailyMinutes: number;
  restDays: number[];
  targetExam: string;
  targetScore: number | null;
  examDate: string;
  preferenceJson?: Record<string, unknown>;
}

export interface AuthResponse {
  userId: string;
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: UserDto;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
  locale?: string;
  timezone?: string;
  consents?: { termsVersion?: string; audioProcessing?: boolean };
}

export interface LoginRequest {
  email: string;
  password: string;
}

// ---- 考试目录 ----
export interface ExamDto {
  examCode: string;
  name: string;
  sections: string[];
  publishedVersion: number;
  minTurns: number;
  defaultTurns: number;
  maxTurns: number;
  scoreRange: { min: number; max: number };
}

// ---- 测评 ----
export interface QuestionDto {
  questionId: string;
  sequence: number;
  question: string;
  section: string;
  difficulty: number;
  responseTimeSeconds: number;
}

export interface CreateAssessmentResponse {
  sessionId: string;
  status: string;
  examCode: string;
  minTurns: number;
  defaultTurns: number;
  maxTurns: number;
  expiresAt: string;
  firstQuestion: QuestionDto;
}

export interface SubmitTurnRequest {
  sequence: number;
  questionId?: string;
  question?: string;
  section?: string;
  difficulty?: number;
  answerText?: string | null;
  audioObjectKey?: string | null;
  durationMs?: number;
  clientMetadata?: Record<string, unknown>;
}

export interface SubmitTurnResponse {
  turnId: string;
  status: string;
  turnCount: number;
  canFinish: boolean;
  nextQuestion: QuestionDto | null;
  maxTurnsReached?: boolean;
  duplicate?: boolean;
}

export interface SessionStatusDto {
  sessionId: string;
  status: string;
  turnCount: number;
  minTurns: number;
  maxTurns: number;
  currentSequence: number;
  canFinish: boolean;
  examCode: string;
  expiresAt: string;
  currentQuestion?: QuestionDto | null;
}

export interface DimensionDto {
  code: string;
  label: string;
  score: number;
  confidence: number;
  evidence: { quote: string; note: string }[];
  recommendations: string[];
}

export interface ReportDto {
  reportId?: string;
  sessionId?: string;
  rubricVersion: string;
  overallEstimate: { low: number; high: number };
  confidence: number;
  dimensions: DimensionDto[];
  limitations: string[];
  topicRelevance?: {
    averageRelevance: number;
    checkedTurns: number;
    offTopicCount: number;
    offTopicTurns: Array<{ sequence: number; relevance: number; reason: string; guidance: string }>;
  };
}

// ---- 任务与计划 ----
export interface JobDto {
  jobId: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  progress: number;
  resultRef: string;
}

export interface TaskDto {
  taskId: string;
  taskDate: string;
  taskType: 'WARMUP' | 'SKILL' | 'SCENARIO' | 'CORRECTION' | 'QUIZ' | 'ASSESSMENT';
  title: string;
  durationMin: number;
  priority: number;
  status: 'TODO' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED' | 'RESCHEDULED' | 'LOCKED';
  locked: boolean;
  content: Record<string, unknown>;
}

export interface ActivePlanDto {
  hasPlan: boolean;
  planId?: string;
  startDate?: string;
  endDate?: string;
  activeVersion?: number;
  dailyMinutes?: number;
  restDays?: number[];
  examCode?: string;
  targetScore?: number;
  weeklyAssessment?: boolean;
  difficultyLevel?: number;
  focusAreas?: string[];
}

export interface ScoreTrendPointDto {
  date: string;
  score: number;
  sessionId?: string;
}

export interface ScoreTrendDto {
  examCode: string;
  examName: string;
  maxScore: number;
  targetScore: number | null;
  currentScore: number | null;
  gap: number | null;
  points: ScoreTrendPointDto[];
}

export interface CreatePlanRequest {
  reportId: string;
  startDate: string;
  endDate: string;
  dailyMinutes: number;
  restDays: number[];
  timezone?: string;
  lockedPreferences?: Record<string, unknown>;
}

export interface PatchTaskRequest {
  action: 'COMPLETE' | 'SKIP' | 'RESCHEDULE' | 'LOCK' | 'UNLOCK';
  targetDate?: string;
  reason?: string;
}

// ---- 实时口语 ----
export interface SpeakingSessionDto {
  sessionId: string;
  status: string;
  provider: string;
  model: string;
  transport: string;
  maxDurationSeconds: number;
}

export interface CredentialDto {
  provider: string;
  ephemeralToken: string;
  expiresAt: string;
  rtcConfig: { mode: string; transport?: string };
}

export interface SpeakingEvent {
  eventId: string;
  schemaVersion: string;
  type: string;
  sessionId?: string;
  occurredAt: string;
  payload: Record<string, any>;
}

export interface CorrectionDto {
  category: string;
  severity: string;
  original: string;
  corrected: string;
  explanation: string;
  confidence: number;
}

export interface FeedbackDto {
  status?: string;
  summary: string;
  corrections: CorrectionDto[];
  shadowingTasks: { text: string; focus: string }[];
  retryAfterSeconds?: number;
}

// ---- 跟读评测 ----
export interface PronunciationScores {
  overall?: number;
  accuracy?: number;
  integrity?: number;
  fluency?: number;
  rank?: number;
  details?: Array<{ char: string; score: number }>;
  [key: string]: unknown;
}

export interface PronunciationAttemptDto {
  attemptId: string;
  itemIndex: number;
  refText: string;
  result: PronunciationScores;
  suggestions: string[];
  createdAt: string;
}

// ---- 进度 ----
export interface ProgressSummaryDto {
  completedTasks: number;
  totalPracticeMinutes: number;
  completedReports: number;
  dimensionTrend: {
    sessionId: string;
    createdAt: string;
    overallLow: number;
    overallHigh: number;
    confidence: number;
    dimensions: Record<string, number>;
  }[];
  errorDistribution: Record<string, number>;
  errorDistributionLabels?: Record<string, string>;
  examCode?: string;
  examName?: string;
  last7DaysCompletion: { date: string; completed: number }[];
}

// ---- 管理后台 ----
export interface AiModelConfigDto {
  configId: string;
  capability: string;
  providerCode: string;
  modelCode: string;
  baseUrl: string | null;
  secretRef: string | null;
  secretConfigured: boolean;
  priority: number;
  enabled: boolean;
  timeoutMs: number;
  maxRetries: number;
  region: string | null;
  dataPolicy: string;
  trafficPercent: number;
  version: number;
  status: string;
  parameters: Record<string, unknown>;
}

export interface PresignUploadDto {
  objectKey: string;
  uploadUrl: string;
  expiresInSeconds: number;
}

// ---- 媒体 ----
export interface AudioArtifact {
  uri: string;
  mimeType: string;
  durationMs: number;
  sampleRate?: number;
  channels?: number;
  blob?: Blob;
}

export type PermissionState = 'granted' | 'denied' | 'undetermined';

export interface AudioDevice {
  deviceId: string;
  label: string;
  kind: string;
}

export interface CaptureOptions {
  mimeType?: string;
  sampleRate?: number;
  channelCount?: number;
}

export interface AudioService {
  requestPermission(): Promise<PermissionState>;
  listInputDevices(): Promise<AudioDevice[]>;
  startCapture(options?: CaptureOptions): Promise<void>;
  stopCapture(): Promise<AudioArtifact>;
  play(uri: string): Promise<void>;
  stopPlayback(): Promise<void>;
  dispose(): Promise<void>;
}

// ---- Speaking 状态机 ----
export type SpeakingState =
  | 'idle'
  | 'requesting_permission'
  | 'creating_session'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'finishing'
  | 'processing_feedback'
  | 'ended'
  | 'failed';

export const SPEAKING_TRANSITIONS: Record<SpeakingState, SpeakingState[]> = {
  // 会话已存在（由入口页创建）时可直接从 creating_session 进入连接阶段
  idle: ['requesting_permission', 'creating_session'],
  requesting_permission: ['creating_session', 'failed', 'idle'],
  creating_session: ['connecting', 'failed'],
  connecting: ['connected', 'reconnecting', 'failed'],
  connected: ['reconnecting', 'finishing', 'failed'],
  reconnecting: ['connected', 'finishing', 'failed'],
  finishing: ['processing_feedback', 'ended', 'failed'],
  processing_feedback: ['ended', 'failed'],
  ended: ['idle'],
  failed: ['idle', 'creating_session'],
};

// ---- 枚举 ----
export const TASK_TYPES = ['WARMUP', 'SKILL', 'SCENARIO', 'CORRECTION', 'QUIZ'] as const;
export const AI_CAPABILITIES = [
  'REALTIME', 'ASR', 'TTS', 'PRONUNCIATION', 'ASSESSMENT', 'PLANNING', 'CORRECTION', 'TUTOR',
] as const;

export function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
