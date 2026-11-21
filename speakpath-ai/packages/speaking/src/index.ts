import { SPEAKING_TRANSITIONS, uuid, type SpeakingState } from '@speakpath/types';

/**
 * Speaking 状态机（规格书 §8.3）：所有状态变更通过 reducer/action，
 * 避免多个组件各自维护矛盾的布尔值。
 */
export interface SpeakingAction {
  type: 'TRANSITION';
  target: SpeakingState;
}

export interface SpeakingContext {
  state: SpeakingState;
  sessionId: string | null;
  error: string | null;
}

export const initialSpeaking: SpeakingContext = {
  state: 'idle',
  sessionId: null,
  error: null,
};

export function speakingReducer(ctx: SpeakingContext, action: SpeakingAction): SpeakingContext {
  if (action.type !== 'TRANSITION') return ctx;
  const target = action.target;
  if (ctx.state === target) return ctx;
  const allowed = SPEAKING_TRANSITIONS[ctx.state];
  if (!allowed.includes(target)) {
    return { ...ctx, error: `非法状态转移: ${ctx.state} -> ${target}` };
  }
  return { ...ctx, state: target, error: null };
}

/** 客户端事件去重：按 eventId 去重（服务端至少一次投递） */
export class EventDeduper {
  private seen = new Set<string>();
  private lastEventId: string | null = null;

  accept(eventId: string): boolean {
    if (this.seen.has(eventId)) return false;
    this.seen.add(eventId);
    this.lastEventId = eventId;
    // 防止无限增长
    if (this.seen.size > 500) {
      const arr = Array.from(this.seen);
      arr.slice(0, 250).forEach((id) => this.seen.delete(id));
    }
    return true;
  }

  getLastEventId(): string | null {
    return this.lastEventId;
  }
}

export function newEventId(): string {
  return uuid();
}
