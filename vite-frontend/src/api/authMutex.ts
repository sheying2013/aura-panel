/**
 * 所有新版标签统一使用同一 IndexedDB readwrite 事务串行化认证写入。
 * IDB 未开启、失败、阻塞、超时或取消时直接失败，绝不在无互斥状态执行写入。
 * 这是互斥，不是 IDB/localStorage 跨库事务：崩溃或 localStorage 写失败不会回滚已写入的键；
 * 旧版本标签、开发者工具和不遵守本协议的写入者不受保护。
 */
export type AuthMutexMode = 'indexeddb';

export interface AuthCriticalSectionOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
  /** 等待已超时但事务后来提交时，仅通知读取方重新校验，不代表原操作成功。 */
  onLateCommit?: () => void;
  /** 兼容旧测试/调用方参数；绝不使用 Web Locks，以免两种锁互不排斥。 */
  locks?: LockManager | null;
  idbFactory?: IDBFactory | null;
}

export const AUTH_MUTEX_WAIT_MS = 2000;
const AUTH_MUTEX_DB = 'aura-auth-mutex';
const AUTH_MUTEX_STORE = 'auth';
const AUTH_MUTEX_FENCE = 'session';
const ABORTED_MESSAGE = '会话锁等待已取消';
const UNAVAILABLE_MESSAGE = '认证互斥存储不可用或等待超时';

function resolveIdbFactory(): IDBFactory | undefined {
  try {
    return typeof indexedDB === 'undefined' ? undefined : indexedDB;
  } catch {
    return undefined;
  }
}

/** action 必须同步执行；在该事务的 fence success 回调内执行且只执行一次。 */
function withIndexedDbLease<T>(
  factory: IDBFactory,
  action: (mode: AuthMutexMode, store: IDBObjectStore, committed: unknown) => T,
  waitMs: number,
  signal?: AbortSignal,
  onLateCommit?: () => void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let db: IDBDatabase | null = null;
    let tx: IDBTransaction | null = null;
    let actionRan = false;
    let value: T | undefined;

    const cleanup = () => {
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
      signal?.removeEventListener('abort', onAbort);
      const opened = db;
      db = null;
      if (opened) {
        try { opened.close(); } catch { /* 释放句柄尽力而为 */ }
      }
    };
    const fail = (error: unknown) => {
      if (settled) return;
      settled = true;
      if (tx) {
        try { tx.abort(); } catch { /* 已结束 */ }
      }
      cleanup();
      reject(error);
    };
    function onAbort() { fail(new Error(ABORTED_MESSAGE)); }

    if (signal?.aborted) { onAbort(); return; }
    signal?.addEventListener('abort', onAbort, { once: true });
    timer = setTimeout(() => fail(new Error(UNAVAILABLE_MESSAGE)), waitMs);

    let request: IDBOpenDBRequest;
    try {
      request = factory.open(AUTH_MUTEX_DB, 1);
    } catch {
      fail(new Error(UNAVAILABLE_MESSAGE));
      return;
    }
    request.onupgradeneeded = () => {
      if (settled) {
        try { request.transaction?.abort(); } catch { /* 已结束 */ }
        return;
      }
      try {
        if (!request.result.objectStoreNames.contains(AUTH_MUTEX_STORE)) {
          request.result.createObjectStore(AUTH_MUTEX_STORE);
        }
      } catch {
        fail(new Error(UNAVAILABLE_MESSAGE));
        try { request.transaction?.abort(); } catch { /* 已结束 */ }
      }
    };
    request.onerror = () => fail(new Error(UNAVAILABLE_MESSAGE));
    request.onblocked = () => fail(new Error(UNAVAILABLE_MESSAGE));
    request.onsuccess = () => {
      if (settled) {
        try { request.result.close(); } catch { /* 迟到句柄 */ }
        return;
      }
      db = request.result;
      db.onversionchange = () => { try { db?.close(); } catch { /* 已关闭 */ } };
      try {
        tx = db.transaction(AUTH_MUTEX_STORE, 'readwrite');
        tx.onabort = () => fail(new Error(UNAVAILABLE_MESSAGE));
        tx.onerror = () => fail(new Error(UNAVAILABLE_MESSAGE));
        tx.oncomplete = () => {
          if (settled) {
            if (actionRan) { try { onLateCommit?.(); } catch { /* 仅失效通知 */ } }
            return;
          }
          if (!actionRan) { fail(new Error(UNAVAILABLE_MESSAGE)); return; }
          settled = true;
          cleanup();
          resolve(value as T);
        };
        const fence = tx.objectStore(AUTH_MUTEX_STORE).put(Date.now(), AUTH_MUTEX_FENCE);
        fence.onerror = () => fail(new Error(UNAVAILABLE_MESSAGE));
        fence.onsuccess = () => {
          if (settled) {
            try { tx?.abort(); } catch { /* 已结束 */ }
            return;
          }
          if (signal?.aborted) { onAbort(); return; }
          try {
            const store = tx!.objectStore(AUTH_MUTEX_STORE);
            const current = store.get('committed-auth');
            current.onerror = () => fail(new Error(UNAVAILABLE_MESSAGE));
            current.onsuccess = () => {
              if (settled) return;
              if (signal?.aborted) { onAbort(); return; }
              try {
                value = action('indexeddb', store, current.result);
                actionRan = true;
              } catch (error) { fail(error); }
            };
          } catch (error) { fail(error); }
        };
      } catch {
        fail(new Error(UNAVAILABLE_MESSAGE));
      }
    };
  });
}

/** 单一 IDB 协议；缺失或异常拒绝写入，不执行 action。 */
export async function runAuthCriticalSection<T>(
  action: (mode: AuthMutexMode, store: IDBObjectStore, committed: unknown) => T,
  options: AuthCriticalSectionOptions = {},
): Promise<T> {
  if (options.signal?.aborted) throw new Error(ABORTED_MESSAGE);
  const factory = options.idbFactory === undefined ? resolveIdbFactory() : options.idbFactory ?? undefined;
  if (!factory) throw new Error(UNAVAILABLE_MESSAGE);
  return withIndexedDbLease(factory, action, options.timeoutMs ?? AUTH_MUTEX_WAIT_MS, options.signal, options.onLateCommit);
}
