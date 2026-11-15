import axios, { AxiosResponse } from 'axios';
import { getPanelAddresses, isWebViewFunc} from '@/utils/panel';
import { runAuthCriticalSection, type AuthMutexMode } from './authMutex';



interface PanelAddress {
  name: string;
  address: string;   
  inx: boolean;
}

const setPanelAddressesFunc = (newAddress: PanelAddress[]) => {
  newAddress.forEach(item => {
    if (item.inx) {
      baseURL = `${item.address}/api/v1/`;
      axios.defaults.baseURL = baseURL;
    }
  });
}

function getWebViewPanelAddress() {
  (window as any).setAddresses = setPanelAddressesFunc
  getPanelAddresses("setAddresses");
};

let baseURL: string = '';

export const reinitializeBaseURL = () => {
  if (isWebViewFunc()) {
    getWebViewPanelAddress();
  } else {
    baseURL = import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/';
    axios.defaults.baseURL = baseURL;
  }
};

reinitializeBaseURL();


interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
}

// 新旧会话均保留原有 token/role_id 键；代次本身不包含凭据。
export const AUTH_GENERATION_KEY = 'aura_auth_generation';
export const AUTH_CHANGE_EVENT = 'aura-auth-change';
export type AuthSnapshot = { token: string | null; generation: string | null; roleId: string | null; name: string | null; admin: string | null };
/** 原始快照仅供比较或登录恢复，不代表已经认证。 */
export const readAuthSnapshot = (): AuthSnapshot => ({
  generation: window.localStorage.getItem(AUTH_GENERATION_KEY),
  token: window.localStorage.getItem('token'),
  roleId: window.localStorage.getItem('role_id'),
  name: window.localStorage.getItem('name'),
  admin: window.localStorage.getItem('admin'),
});
export const isSameAuthSnapshot = (a: AuthSnapshot, b: AuthSnapshot): boolean =>
  a.token === b.token && a.generation === b.generation && a.roleId === b.roleId && a.name === b.name && a.admin === b.admin;
const sameAuth = (a: AuthSnapshot, b: AuthSnapshot, _allowPending = false) => isSameAuthSnapshot(a, b);

/** IDB 记录只在事务提交后可见；故障、旧格式和快照不一致均不认可。 */
export async function getVerifiedAuthSnapshot(): Promise<AuthSnapshot | null> {
  try {
    const initial = readAuthSnapshot();
    if (!initial.token || !initial.generation?.startsWith('pending:v2:')) return null;
    const committed = await runAuthCriticalSection((_mode, _store, record) => record);
    const current = readAuthSnapshot();
    if (!isSameAuthSnapshot(initial, current) || !committed || typeof committed !== 'object') return null;
    const value = committed as { state?: string; snapshot?: AuthSnapshot };
    return value.state === 'active' && !!value.snapshot && isSameAuthSnapshot(initial, value.snapshot) ? initial : null;
  } catch { return null; }
}

/** 通知仅表示应丢弃缓存并重新验证，不代表提交成功。 */
export function subscribeAuthChange(listener: () => void): () => void {
  const update = () => listener();
  window.addEventListener(AUTH_CHANGE_EVENT, update);
  window.addEventListener('storage', update);
  window.addEventListener('pageshow', update);
  window.addEventListener('focus', update);
  document.addEventListener('visibilitychange', update);
  return () => {
    window.removeEventListener(AUTH_CHANGE_EVENT, update);
    window.removeEventListener('storage', update);
    window.removeEventListener('pageshow', update);
    window.removeEventListener('focus', update);
    document.removeEventListener('visibilitychange', update);
  };
}
export const isAuthSessionPending = (): boolean => !!readAuthSnapshot().generation?.startsWith('pending:');
// 旧同步辅助函数只反映标记，不代表提交证明；认证决策应使用 getVerifiedAuthSnapshot。
export const isAuthSessionInactive = (): boolean => {
  const generation = readAuthSnapshot().generation;
  return generation !== null && !generation.startsWith('ready:');
};
export const getAuthIdentityKey = (): string => {
  const { token, generation } = readAuthSnapshot();
  return JSON.stringify([token, generation]);
};
const notifyAuthChange = () => window.dispatchEvent(new Event(AUTH_CHANGE_EVENT));

export type LocalLogoutResult = 'locked' | 'local-only' | 'failed';

/**
 * 本地退出。
 *
 * <p>登录、退出和 401 清理统一由同一 IndexedDB 事务互斥，普通 HTTP 也使用该协议。
 * IndexedDB 不可用时拒绝写入。它不是跨存储事务；旧版本标签页不参与互斥。
 */
export async function clearLocalSessionForLogout(afterCommit?: () => void, expected?: ReturnType<typeof readAuthSnapshot>): Promise<LocalLogoutResult> {
  try {
    const started = expected ?? readAuthSnapshot();
    const clear = (_mode: AuthMutexMode, store: IDBObjectStore, committed: unknown): LocalLogoutResult => {
      const record = committed as { state?: string; snapshot?: AuthSnapshot } | null;
      if (!sameAuth(started, readAuthSnapshot()) || record?.state !== 'active' || !record.snapshot || !sameAuth(started, record.snapshot)) return 'failed';
      const bytes = new Uint8Array(16);
      crypto.getRandomValues(bytes);
      const generation = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
      const storage = window.localStorage;
      storage.setItem(AUTH_GENERATION_KEY, 'logout:' + generation);
      for (const key of ['token', 'role_id', 'name', 'admin']) storage.removeItem(key);
      store.put({ state: 'inactive' }, 'committed-auth');
      notifyAuthChange();
      return 'locked';
    };
    const result = await runAuthCriticalSection(clear, { onLateCommit: notifyAuthChange });
    if (result === 'locked') {
      notifyAuthChange();
      try { afterCommit?.(); } catch { /* 本地提交已完成，远端结果未确认。 */ }
    }
    return result;
  } catch {
    // 可能部分写入，通知界面重新读取，但不能声称退出已完成。
    notifyAuthChange();
    return 'failed';
  }
}

// 仅驻留当前标签页内存，既不持久化凭据，也不修改其他标签的会话。
const REAUTH_NOTICE_EVENT = 'aura-reauth-notice';
let rejectedAuth: AuthSnapshot | null = null;
let noticeId = 0;

export function getReauthenticationNoticeId(): number {
  return rejectedAuth?.token && sameAuth(rejectedAuth, readAuthSnapshot()) ? noticeId : 0;
}

export function subscribeReauthenticationNotice(listener: () => void): () => void {
  const update = () => {
    if (rejectedAuth && !getReauthenticationNoticeId()) rejectedAuth = null;
    listener();
  };
  window.addEventListener(REAUTH_NOTICE_EVENT, update);
  window.addEventListener(AUTH_CHANGE_EVENT, update);
  window.addEventListener('storage', update);
  window.addEventListener('pageshow', update);
  window.addEventListener('focus', update);
  return () => {
    window.removeEventListener(REAUTH_NOTICE_EVENT, update);
    window.removeEventListener(AUTH_CHANGE_EVENT, update);
    window.removeEventListener('storage', update);
    window.removeEventListener('pageshow', update);
    window.removeEventListener('focus', update);
  };
}

function showReauthenticationNotice(used: AuthSnapshot): void {
  if (!used.token || !sameAuth(used, readAuthSnapshot())) return;
  if (rejectedAuth && sameAuth(rejectedAuth, used)) return;
  rejectedAuth = used;
  noticeId += 1;
  window.dispatchEvent(new Event(REAUTH_NOTICE_EVENT));
}

// 绑定显示时的提示编号，避免旧按钮误操作已经替换的新提示。
export function beginReauthentication(expectedId: number): boolean {
  if (!expectedId || expectedId !== noticeId) return false;
  const current = expectedId === getReauthenticationNoticeId();
  rejectedAuth = null;
  window.dispatchEvent(new Event(REAUTH_NOTICE_EVENT));
  return current;
}

export class SessionLockUnavailableError extends Error {
  constructor() {
    super('认证存储不可用或等待超时，登录结果未确认，可能已提交；请重新核验当前会话或重新登录。');
    this.name = 'SessionLockUnavailableError';
  }
}

export class LoginSessionChangedError extends Error {
  constructor() {
    super('登录期间会话已变化，登录信息未保存，请重新登录。');
    this.name = 'LoginSessionChangedError';
  }
}

export async function saveLoginSession(data: { token: string; role_id: number; name: string }, started: AuthSnapshot): Promise<void> {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const generation = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  const write = (store: IDBObjectStore) => {
    if (!sameAuth(started, readAuthSnapshot(), true)) throw new LoginSessionChangedError();
    window.localStorage.setItem(AUTH_GENERATION_KEY, 'pending:v2:' + generation);
    notifyAuthChange();
    window.localStorage.setItem('token', data.token);
    window.localStorage.setItem('role_id', data.role_id.toString());
    window.localStorage.setItem('name', data.name);
    window.localStorage.setItem('admin', (data.role_id === 0).toString());
    // 精确登记预期值，而不是把并发写入者可能替换的值登记为有效。
    const proposed: AuthSnapshot = { token: data.token, generation: 'pending:v2:' + generation,
      roleId: data.role_id.toString(), name: data.name, admin: (data.role_id === 0).toString() };
    if (!isSameAuthSnapshot(proposed, readAuthSnapshot())) throw new LoginSessionChangedError();
    // 为兼容缺少 crypto.subtle 的 HTTP 环境，IDB 也持久保存 token。
    store.put({ state: 'active', snapshot: proposed }, 'committed-auth');
  };
  // 所有新版标签统一使用 IndexedDB 事务；不可用时不执行认证写入。
  try {
    await runAuthCriticalSection((_mode, store) => {
      write(store);
    }, { onLateCommit: notifyAuthChange });
    notifyAuthChange();
    const committed = await getVerifiedAuthSnapshot();
    if (!committed || committed.generation !== 'pending:v2:' + generation || committed.token !== data.token ||
        committed.roleId !== data.role_id.toString() || committed.name !== data.name || committed.admin !== (data.role_id === 0).toString()) {
      throw new LoginSessionChangedError();
    }
  } catch (error) {
    if (error instanceof LoginSessionChangedError) throw error;
    // 等待失败并不证明事务没有提交；调用方必须重新校验状态。
    throw new SessionLockUnavailableError();
  }
}

// 只在 IDB 互斥段内清除；先写非活动墓碑，避免中断后残留 token 继续被当作登录态。
function handleTokenExpired() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const generation = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  window.localStorage.setItem(AUTH_GENERATION_KEY, 'logout:' + generation);
  notifyAuthChange();
  window.localStorage.removeItem('token');
  window.localStorage.removeItem('role_id');
  window.localStorage.removeItem('name');
  window.localStorage.removeItem('admin');
  notifyAuthChange();
}

// 检查响应是否为token失效
// round51：服务端现在会因「改密/登出/账号停用」返回 401，且文案不止三条；
// 只按固定文案匹配会让这些失效场景留在页面上反复失败，因此统一按 401 处理。
function isTokenExpired(response: ApiResponse) {
  return !!response && response.code === 401;
}

/** 可取消的只读请求选项：卸载/超时时真正中断底层的 XHR，不再让它挂着 */
interface CancellableRequestOptions {
  signal?: AbortSignal;
}

/** 读取被取消：给调用方一个明确的终态，不让 Promise 停在 pending */
const CANCELED_CODE = -2;

const canceledResult = <T,>(): ApiResponse<T> => ({
  code: CANCELED_CODE,
  msg: '读取已取消',
  data: null as T
});

/** 请求身份已变化：丢弃旧响应，不修改当前会话。 */
const staleIdentityResult = <T,>(): ApiResponse<T> => ({
  code: -3,
  msg: '请求身份已变化，请重新读取',
  data: null as T
});

/**
 * 401 的终态处理。
 *
 * <p>旧实现遇到 401 只清本地会话就 return，Promise 永远不 resolve —— 调用方停在 loading，
 * 看门狗也无从结束。这里一律给出明确结果。
 *
 * <p>仅在互斥段内、且 token 与代次都匹配时清会话；完全拿不到互斥时保留本地状态并返回 401。
 */
async function tokenExpiredResult<T>(used: AuthSnapshot, msg?: string, signal?: AbortSignal): Promise<ApiResponse<T>> {
  if (signal?.aborted) return canceledResult<T>();
  try {
    const outcome = await runAuthCriticalSection((_mode, store, committed) => {
      if (signal?.aborted) return 'canceled' as const;
      const record = committed as { state?: string; snapshot?: AuthSnapshot } | null;
      if (!sameAuth(used, readAuthSnapshot()) || record?.state !== 'active' || !record.snapshot || !sameAuth(used, record.snapshot)) return 'stale' as const;
      if (!used.token) return 'unsynchronized' as const;
      handleTokenExpired();
      store.put({ state: 'inactive' }, 'committed-auth');
      return 'cleared' as const;
    }, { signal, onLateCommit: notifyAuthChange });
    if (outcome === 'canceled') return canceledResult<T>();
    if (outcome === 'stale') return staleIdentityResult<T>();
    if (outcome === 'unsynchronized') showReauthenticationNotice(used);
    if (outcome === 'cleared') {
      notifyAuthChange();
      if (window.location.pathname !== '/') window.location.href = '/';
    }
  } catch {
    // 互斥请求失败时同样保留状态，并在快照仍匹配时提示用户。
    if (signal?.aborted) return canceledResult<T>();
    if (!sameAuth(used, readAuthSnapshot())) return staleIdentityResult<T>();
    showReauthenticationNotice(used);
  }
  return { code: 401, msg: msg || '登录已失效，请重新登录', data: null as T };
}

function isCanceledRequest(error: any): boolean {
  return axios.isCancel(error) || error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError';
}

const PUBLIC_POST_PATHS = new Set(['/user/login', '/captcha/check', '/captcha/generate', '/captcha/verify', '/config/get']);

async function request<T>(method: 'get' | 'post', path: string, data: any, options: CancellableRequestOptions = {}): Promise<ApiResponse<T>> {
  if (options.signal?.aborted) return canceledResult<T>();
  if (baseURL === '') return { code: -1, msg: ' - 请先设置面板地址', data: null as T };
  const publicRequest = method === 'post' && PUBLIC_POST_PATHS.has(path);
  const initial = readAuthSnapshot();
  const used = publicRequest ? initial : await getVerifiedAuthSnapshot();
  if (!used || !sameAuth(used, readAuthSnapshot(), true)) return staleIdentityResult<T>();
  if (options.signal?.aborted) return canceledResult<T>();
  const current = async () => {
    if (publicRequest) return sameAuth(used, readAuthSnapshot(), true);
    const verified = await getVerifiedAuthSnapshot();
    return !!verified && sameAuth(used, verified);
  };
  try {
    const headers = publicRequest ? {} : { Authorization: used.token };
    const response: AxiosResponse<ApiResponse<T>> = method === 'get'
      ? await axios.get(path, { params: data, timeout: 30000, signal: options.signal, headers })
      : await axios.post(path, data, { timeout: 30000, signal: options.signal, headers });
    if (options.signal?.aborted) return canceledResult<T>();
    if (!(await current())) return staleIdentityResult<T>();
    if (options.signal?.aborted) return canceledResult<T>();
    if (response.status && (response.status < 200 || response.status >= 300)) {
      if (response.status === 401 && !publicRequest) return tokenExpiredResult<T>(used, response.data?.msg, options.signal);
      return { code: response.status === 403 ? 403 : -1, msg: response.data?.msg || 'HTTP ' + response.status, data: null as T };
    }
    if (!publicRequest && isTokenExpired(response.data)) return tokenExpiredResult<T>(used, response.data?.msg, options.signal);
    return response.data;
  } catch (error: any) {
    if (options.signal?.aborted || isCanceledRequest(error)) return canceledResult<T>();
    if (!(await current())) return staleIdentityResult<T>();
    if (options.signal?.aborted) return canceledResult<T>();
    const status = error?.response?.status;
    const body = error?.response?.data;
    const msg = body && typeof body === 'object' ? body.msg : undefined;
    if (status === 401) {
      if (!publicRequest) return tokenExpiredResult<T>(used, msg, options.signal);
      return { code: 401, msg: msg || '登录请求失败', data: null as T };
    }
    if (status === 403) return { code: 403, msg: msg || '当前账号无该项查看权限', data: null as T };
    return { code: -1, msg: msg || error?.message || '网络请求失败', data: null as T };
  }
}

/** 锁外使用捕获的旧 token 发送注销，不处理响应、不清理任何新会话。 */
export function sendLogoutForToken(token: string): void {
  if (!baseURL || !token) return;
  try { void axios.post('/user/logout', {}, { timeout: 30000, headers: { Authorization: token } }).catch(() => {}); }
  catch { /* 服务端注销未确认 */ }
}

const Network = {
  get: <T = any>(path = '', data: any = {}): Promise<ApiResponse<T>> => request<T>('get', path, data),
  post: <T = any>(path = '', data: any = {}): Promise<ApiResponse<T>> => request<T>('post', path, data),
  postCancellable: <T = any>(path = '', data: any = {}, options: CancellableRequestOptions = {}): Promise<ApiResponse<T>> => request<T>('post', path, data, options),
};

export default Network;