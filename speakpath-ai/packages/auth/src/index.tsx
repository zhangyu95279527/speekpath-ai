import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { AuthResponse, LoginRequest, RegisterRequest, UserDto } from '@speakpath/types';
import { api, setTokenGetter, setRefreshHandler } from '@speakpath/api';

/**
 * 认证上下文：token 存储（Web localStorage / Native AsyncStorage 由宿主注入）、
 * 登录/注册/登出、单飞刷新。refresh token 不落 localStorage 的强约束由宿主注入的
 * secure storage 实现；Web 演示环境使用内存 + 后端 HttpOnly Cookie 兜底。
 */

type StorageLike = {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
};

const memoryStorage: StorageLike = {
  getItem: async (key) => (globalThis as any).__speakpathMemory?.[key] ?? null,
  setItem: async (key, value) => {
    (globalThis as any).__speakpathMemory = { ...((globalThis as any).__speakpathMemory || {}), [key]: value };
  },
  removeItem: async (key) => {
    const mem = { ...((globalThis as any).__speakpathMemory || {}) };
    delete mem[key];
    (globalThis as any).__speakpathMemory = mem;
  },
};

let storage: StorageLike = memoryStorage;
export function setAuthStorage(s: StorageLike) {
  storage = s;
}

const AUTH_KEY = 'speakpath.auth.v1';

interface AuthState {
  user: UserDto | null;
  accessToken: string | null;
  refreshToken: string | null;
  status: 'loading' | 'authenticated' | 'anonymous';
}

interface AuthContextValue extends AuthState {
  login(email: string, password: string): Promise<void>;
  register(input: RegisterRequest): Promise<void>;
  logout(): Promise<void>;
  /** 刷新会话；成功返回新 accessToken（供 api 层 401 重试直接使用），失败返回 null。 */
  refresh(): Promise<string | null>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({
    user: null, accessToken: null, refreshToken: null, status: 'loading',
  });
  // 同步镜像最新状态：tokenGetter/refreshHandler 在任何渲染时序下都读到最新值
  const stateRef = useRef<AuthState>(state);

  const persist = useCallback(async (next: AuthState) => {
    stateRef.current = next;
    setState(next);
    if (next.accessToken && next.refreshToken) {
      await storage.setItem(AUTH_KEY, JSON.stringify({
        user: next.user, accessToken: next.accessToken, refreshToken: next.refreshToken,
      }));
    } else {
      await storage.removeItem(AUTH_KEY);
    }
  }, []);

  const applyAuth = useCallback((auth: AuthResponse): AuthState => ({
    user: auth.user, accessToken: auth.accessToken, refreshToken: auth.refreshToken, status: 'authenticated',
  }), []);

  // 恢复会话
  useEffect(() => {
    (async () => {
      try {
        const raw = await storage.getItem(AUTH_KEY);
        if (raw) {
          const saved = JSON.parse(raw);
          stateRef.current = { ...saved, status: 'authenticated' };
          setState(stateRef.current);
        } else {
          setState((prev) => ({ ...prev, status: 'anonymous' }));
        }
      } catch {
        setState((prev) => ({ ...prev, status: 'anonymous' }));
      }
    })();
  }, []);

  // 注入 api client 的 token 获取器（读取 ref，避免渲染时序导致的旧闭包）
  useEffect(() => {
    setTokenGetter(() => stateRef.current.accessToken);
  }, []);

  const refresh = useCallback(async (): Promise<string | null> => {
    const refreshToken = stateRef.current.refreshToken;
    if (!refreshToken) return null;
    try {
      const envelope = await api.post<AuthResponse>('/auth/refresh', { refreshToken }, { 'Idempotency-Key': 'refresh' });
      await persist(applyAuth(envelope.data));
      return envelope.data.accessToken;
    } catch (e: any) {
      // 仅令牌确实失效（401）时清除会话；网络抖动等临时错误保留会话，稍后重试
      if (e?.code === 'UNAUTHENTICATED' || e?.status === 401) {
        await persist({ user: null, accessToken: null, refreshToken: null, status: 'anonymous' });
      }
      return null;
    }
  }, [persist, applyAuth]);

  useEffect(() => {
    setRefreshHandler(refresh);
  }, [refresh]);

  const login = useCallback(async (email: string, password: string) => {
    const envelope = await api.post<AuthResponse>('/auth/login', { email, password } satisfies LoginRequest);
    await persist(applyAuth(envelope.data));
  }, [persist, applyAuth]);

  const register = useCallback(async (input: RegisterRequest) => {
    const envelope = await api.post<AuthResponse>('/auth/register', input);
    await persist(applyAuth(envelope.data));
  }, [persist, applyAuth]);

  const logout = useCallback(async () => {
    try {
      if (state.refreshToken) {
        await api.post('/auth/logout', { refreshToken: state.refreshToken });
      }
    } finally {
      await persist({ user: null, accessToken: null, refreshToken: null, status: 'anonymous' });
    }
  }, [state.refreshToken, persist]);

  const value = useMemo<AuthContextValue>(() => ({
    ...state, login, register, logout, refresh,
  }), [state, login, register, logout, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
