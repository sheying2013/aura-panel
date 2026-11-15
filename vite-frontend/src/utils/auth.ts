import { useSyncExternalStore } from 'react';

import { AUTH_GENERATION_KEY, getVerifiedAuthSnapshot, readAuthSnapshot } from '@/api/network';

import { getRoleIdFromToken, getUserIdFromToken, isTokenValid } from './jwt';

/**
 * 权限工具类
 *
 * <p>这里是会话身份的唯一权威。只有 {@link getVerifiedAuthSnapshot} 认可（IDB 已提交代次
 * 与本地完整身份一致）的 token 才会被放行；localStorage 只当"可能已变化"的通知源，
 * 不能直接当身份来源。
 */

export type AuthSessionState = 'unknown' | 'verified' | 'anonymous';

export interface AuthSession {
  /**
   * unknown：已失效、正在异步复验——此时不得使用 token，也不得展示业务数据；
   * verified：IDB 已提交代次与本地身份一致，token 可用；
   * anonymous：确认没有可用会话。
   */
  state: AuthSessionState;
  /** 非凭据身份指纹（代次 + 用户 + 角色），仅用于重建页面作用域 */
  identityKey: string;
  /** 每次失效 +1；即使身份 A→B→A 也能让消费者重建作用域 */
  revision: number;
  token: string | null;
  userId: number | null;
  roleId: number | null;
  admin: boolean;
}

const UNKNOWN_IDENTITY_KEY = 'unknown';
const ANONYMOUS_IDENTITY_KEY = 'anonymous';

/** 会话变更会触碰的 localStorage 键；null 表示 clear() */
const AUTH_STORAGE_KEYS = new Set<string | null>([null, 'token', 'role_id', 'name', 'admin', AUTH_GENERATION_KEY]);

let revision = 0;
/** 上次复验通过时的原始身份（含凭据，仅驻留内存用于比对，不外传） */
let verifiedRawKey: string | null = null;
let session: AuthSession = {
  state: 'unknown',
  identityKey: UNKNOWN_IDENTITY_KEY,
  revision,
  token: null,
  userId: null,
  roleId: null,
  admin: false,
};
const listeners = new Set<() => void>();

const sameSession = (a: AuthSession, b: AuthSession): boolean =>
  a.state === b.state && a.identityKey === b.identityKey && a.revision === b.revision && a.token === b.token;

function publish(next: AuthSession): void {
  if (sameSession(session, next)) return;
  session = next;
  listeners.forEach((listener) => listener());
}

const unknownSession = (): AuthSession => ({
  state: 'unknown',
  identityKey: UNKNOWN_IDENTITY_KEY,
  revision,
  token: null,
  userId: null,
  roleId: null,
  admin: false,
});

const anonymousSession = (): AuthSession => ({
  state: 'anonymous',
  identityKey: ANONYMOUS_IDENTITY_KEY,
  revision,
  token: null,
  userId: null,
  roleId: null,
  admin: false,
});

// 存储已变化但跨标签事件尚未送达时，使用稳定的非凭据快照屏蔽旧身份。
const staleView: AuthSession = {
  state: 'unknown', identityKey: UNKNOWN_IDENTITY_KEY, revision: 0,
  token: null, userId: null, roleId: null, admin: false,
};

/** 当前会话快照；同一状态返回稳定引用（useSyncExternalStore 要求）。 */
export function getAuthSession(): AuthSession {
  return session.state === 'verified' && isAuthIdentityStale() ? staleView : session;
}

export function subscribeAuthSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** 订阅复验后的会话身份 */
export function useAuthSession(): AuthSession {
  return useSyncExternalStore(subscribeAuthSession, getAuthSession, getAuthSession);
}

const rawIdentityKey = (): string | null => {
  try {
    return JSON.stringify(readAuthSnapshot());
  } catch {
    return null;
  }
};

/**
 * 本地原始身份是否已偏离上次复验结果。
 *
 * <p>通知可能只是 focus/visibility 之类的空事件，没有变化就不要重建页面作用域。
 */
export function isAuthIdentityStale(): boolean {
  if (session.state !== 'verified') return true;
  const raw = rawIdentityKey();
  return raw === null || raw !== verifiedRawKey;
}

/**
 * 先失效：同步丢弃 token 与身份，迫使消费者清空作用域，再异步复验。
 */
export function invalidateAuthSession(): void {
  verifiedRawKey = null;
  revision += 1;
  publish(unknownSession());
}

/**
 * 异步复验会话身份；并发调用以最后一次为准，旧结果按 ticket 丢弃。
 *
 * @returns 复验后的会话（被更新的复验取代时返回当前状态）
 */
export async function revalidateAuthSession(): Promise<AuthSession> {
  const ticket = ++revision;
  publish(unknownSession());

  let snapshot: Awaited<ReturnType<typeof getVerifiedAuthSnapshot>> = null;
  try {
    snapshot = await getVerifiedAuthSnapshot();
  } catch {
    snapshot = null;
  }
  if (ticket !== revision) return session;

  // 验证完成与消费之间的本地身份也必须一致，不能把变化后的快照标记为已验证。
  if (snapshot && rawIdentityKey() !== JSON.stringify(snapshot)) {
    verifiedRawKey = null;
    publish(unknownSession());
    return session;
  }
  const token = snapshot?.token ?? null;
  if (!token || !isTokenValid(token)) {
    verifiedRawKey = null;
    publish(anonymousSession());
    return session;
  }

  const parsedUserId = getUserIdFromToken(token);
  // sub 解析失败会得到 NaN：按未知用户处理，避免作用域里出现 NaN 指纹
  const userId = parsedUserId !== null && Number.isFinite(parsedUserId) ? parsedUserId : null;
  // 角色以已签名 token 为准；只有老 token 缺 claim 时才退回已提交快照。
  const roleId = getRoleIdFromToken(token) ?? (snapshot?.roleId ? Number(snapshot.roleId) : null);
  verifiedRawKey = JSON.stringify(snapshot);
  publish({
    state: 'verified',
    identityKey: JSON.stringify([snapshot?.generation ?? null, userId, roleId]),
    revision,
    token,
    userId,
    roleId,
    admin: roleId === 0,
  });
  return session;
}

/** 身份变更会触碰的 localStorage 键（供 storage 事件过滤） */
export function isAuthStorageKey(key: string | null): boolean {
  return AUTH_STORAGE_KEYS.has(key);
}

/**
 * 获取当前用户的token
 *
 * <p>只有复验通过后才会返回；未提交或已注销的代次一律返回 null。
 */
export function getToken(): string | null {
  if (isAuthIdentityStale()) return null;
  return session.state === 'verified' ? session.token : null;
}

/** 复验通过的用户 ID */
export function getVerifiedUserId(): number | null {
  return !isAuthIdentityStale() && session.state === 'verified' ? session.userId : null;
}

/** 复验通过的身份指纹；身份未知时为 null */
export function getVerifiedIdentityKey(): string | null {
  return !isAuthIdentityStale() && session.state === 'verified' ? session.identityKey : null;
}

/** 复验通过且是管理员 */
export function isVerifiedAdmin(): boolean {
  return !isAuthIdentityStale() && session.state === 'verified' && session.admin;
}

/**
 * 获取当前用户的角色ID
 * @returns 角色ID
 */
export function getCurrentUserRoleId(): number | null {
  const token = getToken();
  if (!token || !isTokenValid(token)) {
    return null;
  }
  return getRoleIdFromToken(token);
}

/**
 * 判断当前用户是否是管理员
 * @returns 是否是管理员
 */
export function isAdmin(): boolean {
  const roleId = getCurrentUserRoleId();
  return roleId === 0;
}

/**
 * 判断当前用户是否有指定角色
 * @param targetRoleId 目标角色ID
 * @returns 是否有指定角色
 */
export function hasRole(targetRoleId: number): boolean {
  const roleId = getCurrentUserRoleId();
  return roleId === targetRoleId;
}

/**
 * 判断当前用户是否已登录且token有效
 * @returns 是否已登录
 */
export function isLoggedIn(): boolean {
  const token = getToken();
  return token ? isTokenValid(token) : false;
}

/**
 * 权限检查装饰器函数
 * @param fn 要执行的函数
 * @param errorMsg 权限不足时的错误提示
 * @returns 包装后的函数
 */
export function requireAdmin<T extends (...args: any[]) => any>(
  fn: T, 
  errorMsg: string = '权限不足，仅管理员可操作'
): T {
  return ((...args: Parameters<T>) => {
    if (!isAdmin()) {
      console.warn(errorMsg);
      return false;
    }
    return fn(...args);
  }) as T;
}
