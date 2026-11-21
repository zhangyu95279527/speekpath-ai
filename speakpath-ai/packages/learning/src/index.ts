import type { ActivePlanDto, CreatePlanRequest, PatchTaskRequest, TaskDto } from '@speakpath/types';
import { api } from '@speakpath/api';

/**
 * 计划与任务 API 封装。
 */
export const planApi = {
  create: (input: CreatePlanRequest) => api.post<{ jobId: string; status: string; planId: string }>('/plans', input),

  active: () => api.get<ActivePlanDto>('/plans/active'),

  tasks: (date?: string) => api.get<TaskDto[]>(`/tasks${date ? `?date=${date}` : ''}`),

  upcoming: (days = 7) => api.get<TaskDto[]>(`/tasks/upcoming?days=${days}`),

  patchTask: (taskId: string, input: PatchTaskRequest) => api.patch(`/tasks/${taskId}`, input),

  recalculate: (trigger = 'USER_REQUEST') => api.post<{ jobId: string; status: string }>('/plans/recalculate', { trigger }),

  replan: (input: { examCode: string; targetScore: number; dailyMinutes: number }) =>
    api.post<{ jobId: string; status: string; difficultyLevel?: number; focusAreas?: string[] }>('/plans/replan', input),
};

export function taskTypeLabel(type: string): string {
  switch (type) {
    case 'WARMUP': return '热身复习';
    case 'SKILL': return '专项技能';
    case 'SCENARIO': return '情景口语';
    case 'CORRECTION': return '纠错复述';
    case 'QUIZ': return '小测总结';
    case 'ASSESSMENT': return '水平评测';
    default: return type;
  }
}
