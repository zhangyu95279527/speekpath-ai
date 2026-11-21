import type { ApiEnvelope, JobDto } from '@speakpath/types';

/**
 * 统一 API client：
 * - 注入认证 token 与 Idempotency-Key
 * - 401 单飞刷新（并发请求只触发一次 refresh）
 * - 统一错误映射（稳定错误码），禁止日志输出 token
 */
export class ApiError extends Error {
  code: string;
  traceId: string;
  status: number;
  fields?: { field: string; reason: string }[];

  constructor(code: string, message: string, traceId: string, status: number,
              fields?: { field: string; reason: string }[]) {
    super(message);
    this.code = code;
    this.traceId = traceId;
    this.status = status;
    this.fields = fields;
  }
}

let baseUrl = process.env.EXPO_PUBLIC_API_URL || 'http://localhost:8080/api/v1';

export function setBaseUrl(url: string) {
  baseUrl = url;
}

export function apiBaseUrl(): string {
  return baseUrl;
}

type TokenGetter = () => string | null;
let tokenGetter: TokenGetter = () => null;
export function setTokenGetter(getter: TokenGetter) {
  tokenGetter = getter;
}

type RefreshHandler = () => Promise<string | null>;
let refreshHandler: RefreshHandler | null = null;
export function setRefreshHandler(handler: RefreshHandler) {
  refreshHandler = handler;
}

let refreshPromise: Promise<string | null> | null = null;
let refresh401Promise: Promise<string | null> | null = null;

async function ensureFreshToken(): Promise<string | null> {
  const token = tokenGetter();
  if (token) return token;
  // 无 token 时尝试刷新一次（单飞），直接使用刷新返回的新 token（React 状态更新有延迟）
  if (refreshHandler) {
    if (!refreshPromise) {
      refreshPromise = refreshHandler().finally(() => {
        refreshPromise = null;
      });
    }
    return refreshPromise;
  }
  return null;
}

/** 401 触发的刷新（单飞）：并发多个 401 只刷新一次，避免同 token 重放被服务端撤销。 */
async function refreshOnce(): Promise<string | null> {
  if (!refresh401Promise) {
    refresh401Promise = (refreshHandler ? refreshHandler() : Promise.resolve(null)).finally(() => {
      refresh401Promise = null;
    });
  }
  return refresh401Promise;
}

export async function apiRequest<T>(path: string, init?: RequestInit & { skipAuth?: boolean }): Promise<ApiEnvelope<T>> {
  return doRequest<T>(path, init, false);
}

async function doRequest<T>(path: string, init: (RequestInit & { skipAuth?: boolean }) | undefined,
                            retried: boolean, tokenOverride?: string | null): Promise<ApiEnvelope<T>> {
  const skipAuth = init?.skipAuth === true;
  const token = tokenOverride !== undefined
    ? tokenOverride
    : (skipAuth ? null : await ensureFreshToken());
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (!init?.method || ['POST', 'PUT', 'PATCH'].includes(init.method.toUpperCase())) {
    const idem = (init?.headers as Record<string, string> | undefined)?.['Idempotency-Key'];
    if (!idem && init?.method !== 'GET') {
      headers['Idempotency-Key'] = genKey();
    }
  }
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers, credentials: 'include' });
  // 访问令牌过期：刷新一次后用新 token 重试（auth 接口本身不重试，避免循环）
  if (response.status === 401 && !skipAuth && !retried && !path.startsWith('/auth/')) {
    const newToken = await refreshOnce();
    if (newToken) {
      return doRequest<T>(path, init, true, newToken);
    }
  }
  const body = (await response.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!response.ok || !body) {
    const code = body?.code || `HTTP_${response.status}`;
    const fields = (body as any)?.data?.fields;
    throw new ApiError(code, body?.message || `Request failed (${response.status})`, body?.traceId || '', response.status, fields);
  }
  return body;
}

// 便捷方法
export const api = {
  get: <T>(path: string) => apiRequest<T>(path),
  post: <T>(path: string, data?: unknown, headers?: Record<string, string>) =>
    apiRequest<T>(path, { method: 'POST', body: data === undefined ? undefined : JSON.stringify(data), headers }),
  patch: <T>(path: string, data?: unknown) =>
    apiRequest<T>(path, { method: 'PATCH', body: data === undefined ? undefined : JSON.stringify(data) }),
  put: <T>(path: string, data?: unknown) =>
    apiRequest<T>(path, { method: 'PUT', body: data === undefined ? undefined : JSON.stringify(data) }),
  del: <T>(path: string) => apiRequest<T>(path, { method: 'DELETE' }),
};

let keyCounter = 0;
function genKey(): string {
  keyCounter += 1;
  return `${Date.now().toString(36)}-${keyCounter.toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 轮询 job 直到 SUCCEEDED/FAILED */
export async function waitForJob(jobId: string, intervalMs = 1200, maxTries = 60): Promise<JobDto> {
  for (let i = 0; i < maxTries; i++) {
    const envelope = await api.get<JobDto>(`/jobs/${jobId}`);
    const job = envelope.data;
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED') {
      return job;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new ApiError('TIMEOUT', `Job ${jobId} did not finish in time`, '', 408);
}
