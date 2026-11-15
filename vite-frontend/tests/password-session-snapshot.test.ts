/**
 * 改密操作跨标签切换时不得退出后来的会话。
 * 运行：node --import ./tests/register.mjs --test ./tests/password-session-snapshot.test.ts
 * IDB 不可用时拒绝写入；替身不代表真实浏览器跨进程调度。
 */
import assert from 'node:assert/strict';
import { beforeEach, afterEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { createAuthIdb, waitForIdbRequest } from './fake-auth-idb';

const store = new Map<string, string>();
const storage = {
  getItem: (key: string) => store.get(key) ?? null,
  setItem: (key: string, value: string) => { store.set(key, String(value)); },
  removeItem: (key: string) => { store.delete(key); },
  clear: () => store.clear(),
  key: (index: number) => Array.from(store.keys())[index] ?? null,
  get length() { return store.size; },
};
const events = new EventTarget();
(globalThis as any).window = {
  localStorage: storage,
  location: { href: 'http://localhost/dashboard', pathname: '/dashboard', origin: 'http://localhost' },
  addEventListener: events.addEventListener.bind(events),
  removeEventListener: events.removeEventListener.bind(events),
  dispatchEvent: events.dispatchEvent.bind(events),
};
const originalIdb = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB');
const setIdb = (factory: unknown) => Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: factory });
const network = await import('../src/api/network');
const { safeLogout } = await import('../src/utils/logout');
const { default: axios } = await import('axios');
const originalPost = axios.post;
let logoutRequests = 0;

beforeEach(() => {
  storage.clear();
  storage.setItem('token', 'account-A');
  storage.setItem('aura_auth_generation', 'pending:v2:A');
  storage.setItem('role_id', '0');
  storage.setItem('name', 'A');
  storage.setItem('theme', 'dark');
  logoutRequests = 0;
  (axios as any).post = () => { logoutRequests += 1; return Promise.resolve({ data: { code: 0, msg: 'ok' } }); };
  const idb = createAuthIdb();
  idb.seed({ state: 'active', snapshot: network.readAuthSnapshot() });
  setIdb(idb.factory);
});
afterEach(() => {
  (axios as any).post = originalPost;
  if (originalIdb) Object.defineProperty(globalThis, 'indexedDB', originalIdb);
  else Reflect.deleteProperty(globalThis, 'indexedDB');
});
function switchToB() {
  storage.setItem('token', 'account-B');
  storage.setItem('aura_auth_generation', 'pending:v2:B');
  storage.setItem('name', 'B');
}

test('改密响应后身份已换：保存原快照的退出不清 B 或发送注销', async () => {
  const operationIdentity = network.readAuthSnapshot();
  switchToB();
  assert.equal(await safeLogout(operationIdentity), 'failed');
  assert.equal(storage.getItem('token'), 'account-B');
  assert.equal(storage.getItem('aura_auth_generation'), 'pending:v2:B');
  assert.equal(logoutRequests, 0);
});

test('退出等待锁期间身份变化：锁内复核原操作快照', async () => {
  const operationIdentity = network.readAuthSnapshot();
  const idb = createAuthIdb({ manual: true });
  idb.seed({ state: 'active', snapshot: operationIdentity });
  setIdb(idb.factory);
  const pending = safeLogout(operationIdentity);
  await waitForIdbRequest(idb.requests);
  switchToB();
  idb.requests[0].grant();
  assert.equal(await pending, 'failed');
  assert.equal(storage.getItem('token'), 'account-B');
  assert.equal(logoutRequests, 0);
});

test('IDB 不可用且身份已切换：拒绝清理新身份', async () => {
  setIdb(undefined);
  const operationIdentity = network.readAuthSnapshot();
  switchToB();
  assert.equal(await safeLogout(operationIdentity), 'failed');
  assert.equal(storage.getItem('token'), 'account-B');
  assert.equal(logoutRequests, 0);
});

test('身份未变：有锁退出允许注销并保留偏好', async () => {
  const operationIdentity = network.readAuthSnapshot();
  assert.equal(await safeLogout(operationIdentity), 'locked');
  assert.equal(storage.getItem('token'), null);
  assert.equal(storage.getItem('theme'), 'dark');
  assert.equal(logoutRequests, 1);
});

test('改密页和资料页在请求前捕获快照并传到退出重试', () => {
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const read = (path: string) => readFileSync(join(root, path), 'utf8');
  const password = read('vite-frontend/src/pages/change-password.tsx');
  const profile = read('vite-frontend/src/pages/profile.tsx');
  assert.ok(password.indexOf('const started = readAuthSnapshot()') < password.indexOf('await updatePassword(form)'));
  assert.ok(password.includes('await logout(started)'));
  assert.ok(password.includes('await logout(exitIdentity.current)'));
  assert.ok(profile.indexOf('const started = readAuthSnapshot()') < profile.indexOf('await updatePassword(passwordForm)'));
  assert.ok(profile.includes('await handleLogout(started)'));
  assert.ok(profile.includes('safeLogout(expected)'));
});
