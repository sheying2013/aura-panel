// 跨标签认证互斥（IndexedDB 租约）与普通 HTTP 登录/退出回归。
//
// 运行：node --import ./tests/register.mjs --test ./tests/cross-tab-auth-mutex.test.ts
//
// <p>这里的 IndexedDB 是测试替身：同一 database + objectStore 上的 readwrite 事务按顺序授予，
// 后一个 fence 回调只在前一个事务结束后触发 —— 与浏览器对重叠 scope 事务的串行化一致。
// 它只能验证「临界区不重叠」，不能验证真实浏览器的进程调度、配额、崩溃恢复。
// 旧版本标签页不参与租约这一点无法在同进程内复现，只在 network.ts 的注释里声明。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, test } from 'node:test';

interface FakeRequest {
  onsuccess: (() => void) | null;
  onerror: (() => void) | null;
  result?: unknown;
  error?: unknown;
}

interface FakeOptions {
  grantDelayMs?: number;
  openFailure?: 'throw' | 'error' | 'blocked' | 'never';
  abortAfterAction?: boolean;
}

function createFakeIndexedDb(options: FakeOptions = {}) {
  const stats = { opens: 0, grants: 0, completes: 0, aborts: 0, closes: 0 };
  const queue: Array<(done: () => void) => void> = [];
  let pumping = false;
  let committed: unknown;

  // 事务逐个授予：上一个事务结束（或放弃）后才放行下一个。
  const pump = () => {
    if (pumping) return;
    pumping = true;
    const next = () => {
      const job = queue.shift();
      if (!job) {
        pumping = false;
        return;
      }
      stats.grants += 1;
      job(() => {
        pumping = false;
        pump();
      });
    };
    setTimeout(next, options.grantDelayMs ?? 0);
  };

  const createDb = () => ({
    objectStoreNames: { contains: () => true },
    onversionchange: null as null | (() => void),
    close: () => { stats.closes += 1; },
    transaction: () => {
      let aborted = false;
      let staged: unknown;
      let dirty = false;
      const tx = {
        oncomplete: null as null | (() => void),
        onabort: null as null | (() => void),
        onerror: null as null | (() => void),
        abort: () => {
          if (aborted) return;
          aborted = true;
          stats.aborts += 1;
          setTimeout(() => tx.onabort?.(), 0);
        },
        objectStore: () => ({
          get: () => {
            const request: FakeRequest = { onsuccess: null, onerror: null };
            queueMicrotask(() => { if (!aborted) { request.result = committed; request.onsuccess?.(); } });
            return request;
          },
          put: (value: unknown, key: string) => {
            const fence: FakeRequest = { onsuccess: null, onerror: null };
            if (key === 'committed-auth') { staged = value; dirty = true; return fence; }
            queue.push(done => {
              if (aborted) {
                fence.onerror?.();
                done();
                return;
              }
              fence.onsuccess?.();
              setTimeout(() => {
                if (!aborted && options.abortAfterAction) tx.abort();
                else if (!aborted) {
                  if (dirty) committed = staged;
                  stats.completes += 1;
                  tx.oncomplete?.();
                }
                done();
              }, 0);
            });
            pump();
            return fence;
          },
        }),
      };
      return tx;
    },
  });

  const open = (): FakeRequest => {
    stats.opens += 1;
    if (options.openFailure === 'throw') throw new Error('SecurityError: indexedDB 被禁用');
    const request: FakeRequest = { onsuccess: null, onerror: null };
    setTimeout(() => {
      if (options.openFailure === 'error') {
        request.error = new Error('open failed');
        request.onerror?.();
        return;
      }
      if (options.openFailure === 'blocked') {
        (request as unknown as { onblocked?: () => void }).onblocked?.();
        return;
      }
      if (options.openFailure === 'never') return;
      request.result = createDb();
      request.onsuccess?.();
    }, 0);
    return request;
  };

  return { factory: { open, seed: (value: unknown) => { committed = value; } } as unknown as IDBFactory, stats };
}

const values = new Map<string, string>();
const writes: Array<[string, string, "set" | "remove"]> = [];
const storage = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => { values.set(key, value); writes.push([key, value, "set"]); },
  removeItem: (key: string) => { values.delete(key); writes.push([key, '', 'remove']); },
  clear: () => { values.clear(); },
};
const events = new EventTarget();
const locationStub = { pathname: '/dashboard', href: 'http://panel.local/dashboard' };
const windowStub = {
  localStorage: storage,
  location: locationStub,
  addEventListener: events.addEventListener.bind(events),
  removeEventListener: events.removeEventListener.bind(events),
  dispatchEvent: (event: Event) => events.dispatchEvent(event),
};
(globalThis as unknown as { window: unknown }).window = windowStub;

const { default: axios } = await import('axios');
const network = await import('../src/api/network');
const { runAuthCriticalSection } = await import('../src/api/authMutex');
const Network = network.default as unknown as {
  postCancellable: <T = unknown>(path?: string, data?: unknown, options?: { signal?: AbortSignal }) => Promise<{ code: number; msg: string; data: T | null }>;
};

const locksDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
const idbDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB');
const originalAxiosPost = axios.post;
let logoutCalls = 0;

function useLocks(locks: unknown) {
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks } });
}

function useIndexedDb(factory: unknown) {
  Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: factory });
}

function seed(token: string | null, generation: string | null) {
  values.clear();
  writes.length = 0;
  if (token !== null) values.set('token', token);
  if (generation !== null) values.set(network.AUTH_GENERATION_KEY, generation);
  const current = network.readAuthSnapshot();
  const factory = globalThis.indexedDB as unknown as { seed?: (value: unknown) => void } | undefined;
  factory?.seed?.(token && generation?.startsWith('pending:v2:') ? { state: 'active', snapshot: current } : undefined);
  locationStub.pathname = '/dashboard';
  locationStub.href = 'http://panel.local/dashboard';
}

const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

beforeEach(() => {
  logoutCalls = 0;
  (axios as unknown as { post: unknown }).post = () => {
    logoutCalls += 1;
    return Promise.resolve({ data: { code: 0, msg: 'ok', data: null }, status: 200 });
  };
});

afterEach(() => {
  (axios as unknown as { post: unknown }).post = originalAxiosPost;
  if (locksDescriptor) Object.defineProperty(globalThis, 'navigator', locksDescriptor);
  else delete (globalThis as unknown as Record<string, unknown>).navigator;
  if (idbDescriptor) Object.defineProperty(globalThis, 'indexedDB', idbDescriptor);
  else delete (globalThis as unknown as Record<string, unknown>).indexedDB;
  values.clear();
  writes.length = 0;
});

test('无 Web Locks：退出先取得租约，随后登录因代次已变而放弃', async () => {
  const idb = createFakeIndexedDb();
  useLocks(undefined);
  useIndexedDb(idb.factory);
  seed('old', 'pending:v2:old');
  const start = network.readAuthSnapshot();
  const logout = network.clearLocalSessionForLogout(undefined, start);
  const save = network.saveLoginSession({ token: 'new', role_id: 0, name: 'new' }, start);

  assert.equal(await logout, 'locked');
  await assert.rejects(save, network.LoginSessionChangedError);
  assert.equal(values.has('token'), false, '登录放弃后不得留下新 token');
  assert.match(values.get(network.AUTH_GENERATION_KEY) ?? '', /^logout:/);
  assert.deepEqual(writes, [
    [network.AUTH_GENERATION_KEY, values.get(network.AUTH_GENERATION_KEY), 'set'],
    ['token', '', 'remove'],
    ['role_id', '', 'remove'],
    ['name', '', 'remove'],
    ['admin', '', 'remove'],
  ], '只允许退出写墓碑及删除四个认证键；旧登录不得执行任何存储写入');
  assert.ok(idb.stats.grants >= 2, '登录和退出须通过 IDB 事务');
  assert.equal(logoutCalls, 0, '普通 HTTP 不发服务端注销');
});

test('无 Web Locks：登录先取得租约，随后退出放弃且不清新会话', async () => {
  const idb = createFakeIndexedDb();
  useLocks(undefined);
  useIndexedDb(idb.factory);
  seed('old', 'pending:v2:old');
  const start = network.readAuthSnapshot();
  const save = network.saveLoginSession({ token: 'new', role_id: 0, name: 'new' }, start);
  const logout = network.clearLocalSessionForLogout(undefined, start);

  await save;
  assert.equal(await logout, 'failed');
  assert.equal(values.get('token'), 'new');
  assert.match(values.get(network.AUTH_GENERATION_KEY) ?? '', /^pending:v2:/);
  assert.ok(!writes.some(([key, value]) => key === network.AUTH_GENERATION_KEY && value.startsWith('logout:')));
});

test('租约串行执行临界区，且不依赖 navigator.locks', async () => {
  const idb = createFakeIndexedDb();
  const order: string[] = [];
  const first = runAuthCriticalSection(() => { order.push('first'); return 1; }, { locks: null, idbFactory: idb.factory });
  const second = runAuthCriticalSection(() => { order.push('second'); return 2; }, { locks: null, idbFactory: idb.factory });

  assert.deepEqual(await Promise.all([first, second]), [1, 2]);
  assert.deepEqual(order, ['first', 'second']);
  assert.equal(idb.stats.grants, 2);
  assert.equal(idb.stats.completes, 2, '两个事务都应正常结束');
});

test('租约超时拒绝且迟到授予不得执行', async () => {
  const idb = createFakeIndexedDb({ grantDelayMs: 40 });
  const runs: string[] = [];
  const pending = runAuthCriticalSection((mode) => { runs.push(mode); return mode; }, {
    locks: null, idbFactory: idb.factory, timeoutMs: 10,
  });
  await assert.rejects(pending, /认证互斥存储不可用或等待超时/);
  await delay(80);
  assert.deepEqual(runs, [], '超时后绝不能执行迟到的认证写入');
  assert.equal(idb.stats.aborts, 1);
});

test('事务完成后才报告成功；动作之后 abort 返回失败', async () => {
  const idb = createFakeIndexedDb({ abortAfterAction: true });
  let actionRan = false;
  await assert.rejects(runAuthCriticalSection(() => { actionRan = true; }, { idbFactory: idb.factory }), /认证互斥存储不可用/);
  assert.equal(actionRan, true, 'action 后的 abort 无法回滚 localStorage 等外部副作用');
  assert.equal(idb.stats.completes, 0);
});

test('登录 action 后事务 abort 不得形成已验证会话', async () => {
  const options = { abortAfterAction: true };
  const idb = createFakeIndexedDb(options);
  useIndexedDb(idb.factory);
  seed('old', 'pending:v2:old');
  const start = network.readAuthSnapshot();
  await assert.rejects(network.saveLoginSession({ token: 'new', role_id: 0, name: 'new' }, start));
  options.abortAfterAction = false; // 恢复读取，不能靠让所有 IDB 查询失败掩盖问题。
  assert.equal(await network.getVerifiedAuthSnapshot(), null,
    '提交中止后即使本地保留 pending:v2，也不得成为有效会话');
});

test('登录提交完成后，完整 IDB 快照与本地身份一致才获得已验证会话', async () => {
  const idb = createFakeIndexedDb();
  useIndexedDb(idb.factory);
  seed(null, null);
  await network.saveLoginSession({ token: 'new', role_id: 0, name: 'new' }, network.readAuthSnapshot());
  assert.match(values.get(network.AUTH_GENERATION_KEY) ?? '', /^pending:v2:/);
  assert.equal((await network.getVerifiedAuthSnapshot())?.token, 'new');
  values.set('name', 'changed-by-another-writer');
  assert.equal(await network.getVerifiedAuthSnapshot(), null, '任一字段不一致必须拒绝');
});

test('pending 会话退出失败且不发注销', async () => {
  const idb = createFakeIndexedDb();
  useIndexedDb(idb.factory);
  seed('old', 'pending:interrupted');
  let calls = 0;
  assert.equal(await network.clearLocalSessionForLogout(() => { calls += 1; }), 'failed');
  assert.equal(calls, 0);
  assert.equal(values.get('token'), 'old');
  assert.equal(values.get(network.AUTH_GENERATION_KEY), 'pending:interrupted');
});

test('租约取消后不再执行临界区', async () => {
  const idb = createFakeIndexedDb({ grantDelayMs: 40 });
  const controller = new AbortController();
  const runs: string[] = [];
  const pending = runAuthCriticalSection((mode) => { runs.push(mode); return mode; }, {
    locks: null,
    idbFactory: idb.factory,
    signal: controller.signal,
  });
  controller.abort();

  await assert.rejects(pending, /会话锁等待已取消/);
  await delay(80);
  assert.deepEqual(runs, []);
});

test('IndexedDB 打不开时登录及退出均拒绝写入', async () => {
  for (const openFailure of ['throw', 'error', 'blocked'] as const) {
    const idb = createFakeIndexedDb({ openFailure });
    useLocks(undefined);
    useIndexedDb(idb.factory);
    seed('old', 'pending:v2:old');
    const start = network.readAuthSnapshot();
    await assert.rejects(network.saveLoginSession({ token: 'new', role_id: 1, name: 'n' }, start), network.SessionLockUnavailableError);
    assert.equal(await network.clearLocalSessionForLogout(), 'failed');
    assert.equal(values.get('token'), 'old');
    assert.equal(values.get(network.AUTH_GENERATION_KEY), 'pending:v2:old');
    assert.deepEqual(writes, []);
  }
});

test('一个标签有 Web Locks 另一个没有，双方仍进入同一 IDB 队列', async () => {
  const idb = createFakeIndexedDb();
  const order: string[] = [];
  const forbidden = { request: () => { throw new Error('不得调用 Web Locks'); } } as unknown as LockManager;
  const first = runAuthCriticalSection(() => { order.push('with'); }, { locks: forbidden, idbFactory: idb.factory });
  const second = runAuthCriticalSection(() => { order.push('without'); }, { locks: null, idbFactory: idb.factory });
  await Promise.all([first, second]);
  assert.deepEqual(order, ['with', 'without']);
  assert.equal(idb.stats.opens, 2);
});

test('无 Web Locks 但有租约：401 在互斥段内清除当前会话', async () => {
  const idb = createFakeIndexedDb();
  useLocks(undefined);
  useIndexedDb(idb.factory);
  seed('old', 'pending:v2:old');
  (axios as unknown as { post: unknown }).post = () => Promise.reject(Object.assign(new Error('HTTP 401'), {
    response: { status: 401, data: { code: 401, msg: '登录已失效' } },
  }));

  const result = await Network.postCancellable('/user/package', {});
  assert.equal(result.code, 401);
  assert.equal(values.has('token'), false);
  assert.equal(locationStub.href, '/');
});

test('既无 Web Locks 也无 IndexedDB：受保护请求不发送，不清本地会话', async () => {
  useLocks(undefined);
  useIndexedDb(undefined);
  seed('old', 'pending:v2:old');
  (axios as unknown as { post: unknown }).post = () => Promise.reject(Object.assign(new Error('HTTP 401'), {
    response: { status: 401, data: { code: 401, msg: '登录已失效' } },
  }));

  const result = await Network.postCancellable('/user/package', {});
  assert.equal(result.code, -3);
  assert.equal(values.get('token'), 'old', '没有互斥时不写存储');
});

test('迟到的 401 不得清掉新登录', async () => {
  const idb = createFakeIndexedDb();
  useLocks(undefined);
  useIndexedDb(idb.factory);
  seed('old', 'pending:v2:old');
  const loginStart = network.readAuthSnapshot();
  let rejectRequest: ((error: unknown) => void) | null = null;
  (axios as unknown as { post: unknown }).post = () => new Promise((_resolve, reject) => { rejectRequest = reject; });

  const pending = Network.postCancellable('/user/package', {});
  for (let i = 0; i < 100 && !rejectRequest; i++) await delay(1);
  assert.ok(rejectRequest, '请求应已到达传输层');
  await network.saveLoginSession({ token: 'new', role_id: 0, name: 'new' }, loginStart);
  (rejectRequest as (error: unknown) => void)(Object.assign(new Error('HTTP 401'), {
    response: { status: 401, data: { code: 401, msg: '登录已失效' } },
  }));

  const result = await pending;
  assert.equal(result.code, -3);
  assert.equal(values.get('token'), 'new');
});

test('认证写入路径都经共享临界区，且不再直接读 navigator.locks', () => {
  const source = readFileSync(new URL('../src/api/network.ts', import.meta.url), 'utf8');
  assert.match(source, /import \{ runAuthCriticalSection/);
  assert.doesNotMatch(source, /navigator\.locks/);

  const section = (from: string, to: string) => {
    const start = source.indexOf(from);
    const end = source.indexOf(to, start + from.length);
    assert.ok(start >= 0 && end > start, '必须找到 ' + from);
    return source.slice(start, end);
  };
  assert.match(section('export async function saveLoginSession', 'function handleTokenExpired'), /runAuthCriticalSection/);
  assert.match(section('export async function clearLocalSessionForLogout', '// 仅驻留当前标签页内存'), /runAuthCriticalSection/);
  assert.match(section('async function tokenExpiredResult', 'function isCanceledRequest'), /runAuthCriticalSection/);

  // 其余 admin 派生标记的写入不涉及 token，不能成为绕过互斥的会话写入路径。
  for (const file of ['../src/layouts/admin.tsx', '../src/layouts/h5.tsx', '../src/pages/profile.tsx']) {
    const other = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(other, /setItem\(\s*['"]token['"]/, file + ' 不得直接写 token');
    assert.doesNotMatch(other, /removeItem\(\s*['"]token['"]/, file + ' 不得直接清 token');
  }
});
