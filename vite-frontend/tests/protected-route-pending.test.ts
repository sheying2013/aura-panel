/**
 * Pending-session route contract and subscription tests.
 *
 * Run after the agreed validation window:
 * node --import ./tests/register.mjs --test ./tests/protected-route-pending.test.ts
 *
 * The route checks inspect source structure; they do not render React or
 * simulate StrictMode. Browser integration must verify real DOM and timing.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { createAuthIdb } from "./fake-auth-idb";

const appSource = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const networkSource = readFileSync(new URL("../src/api/network.ts", import.meta.url), "utf8");
const protectedRoute = appSource.slice(
  appSource.indexOf("const ProtectedRoute ="),
  appSource.indexOf("const LoginRoute ="),
);

test("pending 在认证跳转及 skipLayout 之前遮蔽受保护子树", () => {
  assert.ok(protectedRoute.length > 0, "必须找到 ProtectedRoute");
  assert.match(protectedRoute, /useSyncExternalStore\(\s*subscribeReauthenticationNotice\s*,\s*getAuthIdentityKey/);
  assert.match(protectedRoute, /<Layout key=\{identityKey\}>/);
  assert.match(protectedRoute, /const session = useAuthSession\(\)/);
  assert.match(protectedRoute, /const authenticated = session\.state === "verified" && isLoggedIn\(\)/);
  assert.match(protectedRoute, /const pending = session\.state === "unknown"/);
  assert.match(protectedRoute, /if\s*\(!pending\s*&&\s*!authenticated\)/);
  const pending = protectedRoute.indexOf("if (pending)");
  const unauthenticated = protectedRoute.indexOf("if (!authenticated) {");
  const skipLayout = protectedRoute.indexOf("if (skipLayout)");
  assert.ok(pending >= 0 && pending < unauthenticated && pending < skipLayout);
  const pendingBranch = protectedRoute.slice(pending, unauthenticated);
  assert.match(pendingBranch, /return\s*<div\s+aria-hidden="true"/);
  assert.doesNotMatch(pendingBranch, /\{children\}|<Layout>/);
});

test("恢复入口位于 Routes 外，公开登录和验证码链保留", () => {
  assert.match(appSource, /<ReauthenticationNotice\s*\/>\s*<Routes>/);
  assert.match(appSource, /if\s*\(pending\)\s*return\s*\([\s\S]*?navigate\("\/\?reauth=1"\)/);
  assert.match(appSource, /<Route\s+path="\/"\s+element=\{<LoginRoute\s*\/>\}/);
  const loginRoute = appSource.slice(appSource.indexOf("const LoginRoute ="), appSource.indexOf("const ReauthenticationNotice ="));
  assert.match(loginRoute, /session\.state === "verified" && isLoggedIn\(\)/);
  assert.match(loginRoute, /if\s*\(session\.state === "unknown" && !reauth && !startedLogin\.current\)/);
  assert.match(loginRoute, /return <IndexPage\s*\/>/);
  assert.match(networkSource, /PUBLIC_POST_PATHS = new Set\(\[[^\]]*['"]\/captcha\/check['"]/);
  assert.match(networkSource, /PUBLIC_POST_PATHS = new Set\(\[[^\]]*['"]\/user\/login['"]/);
});

// Exercises the actual shared subscription through a same-process event target.
// No credentials or network are used.
const values = new Map<string, string>();
const localStorageStub = {
  getItem: (key: string) => values.get(key) ?? null,
  setItem: (key: string, value: string) => { values.set(key, value); },
  removeItem: (key: string) => { values.delete(key); },
};
const fakeWindow = new EventTarget() as EventTarget & { localStorage: typeof localStorageStub };
fakeWindow.localStorage = localStorageStub;
Object.defineProperty(globalThis, "window", { value: fakeWindow, configurable: true });
const { AUTH_CHANGE_EVENT, AUTH_GENERATION_KEY, getAuthIdentityKey, getVerifiedAuthSnapshot, readAuthSnapshot, isAuthSessionPending, subscribeReauthenticationNotice } =
  await import("../src/api/network");

test("logout 墓碑与同 token 不同代次会产生不同身份快照", () => {
  values.clear();
  localStorageStub.setItem('token', 'synthetic');
  localStorageStub.setItem(AUTH_GENERATION_KEY, 'pending:v2:first');
  const first = getAuthIdentityKey();
  localStorageStub.setItem(AUTH_GENERATION_KEY, 'pending:v2:second');
  const second = getAuthIdentityKey();
  localStorageStub.setItem(AUTH_GENERATION_KEY, 'logout:last');
  assert.notEqual(first, second);
  assert.notEqual(second, getAuthIdentityKey());
  values.clear();
});

test("pending:v2 必须匹配已提交快照，墓碑、旧格式和字段不匹配均不认证", async () => {
  const originalIdb = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB');
  const idb = createAuthIdb();
  Object.defineProperty(globalThis, 'indexedDB', { value: idb.factory, configurable: true });
  try {
    values.clear();
    localStorageStub.setItem('token', 'synthetic');
    localStorageStub.setItem('role_id', '0');
    localStorageStub.setItem('name', 'fixture');
    localStorageStub.setItem('admin', 'true');
    localStorageStub.setItem(AUTH_GENERATION_KEY, 'pending:v2:first');
    const snapshot = readAuthSnapshot();
    assert.equal(await getVerifiedAuthSnapshot(), null, '缺少提交证据时不可认证');
    idb.seed({ state: 'active', snapshot });
    assert.deepEqual(await getVerifiedAuthSnapshot(), snapshot, '完整快照匹配才可认证');
    localStorageStub.setItem('name', 'changed');
    assert.equal(await getVerifiedAuthSnapshot(), null, '任一身份字段变化都需重新认证');
    localStorageStub.setItem('name', 'fixture');
    idb.seed({ state: 'inactive' });
    assert.equal(await getVerifiedAuthSnapshot(), null, 'IDB 注销墓碑不可认证');
    idb.seed({ state: 'active', snapshot });
    localStorageStub.setItem(AUTH_GENERATION_KEY, 'logout:last');
    assert.equal(await getVerifiedAuthSnapshot(), null, '本地注销墓碑不可认证');
    localStorageStub.setItem(AUTH_GENERATION_KEY, 'ready:legacy');
    assert.equal(await getVerifiedAuthSnapshot(), null, '旧 ready 标记不能代替提交证据');
  } finally {
    values.clear();
    if (originalIdb) Object.defineProperty(globalThis, 'indexedDB', originalIdb);
    else Reflect.deleteProperty(globalThis, 'indexedDB');
  }
});

test("实际订阅响应同页认证事件、跨标签 storage 与 pageshow，退订后停止", () => {
  values.clear();
  const observed: boolean[] = [];
  const unsubscribe = subscribeReauthenticationNotice(() => observed.push(isAuthSessionPending()));
  try {
    localStorageStub.setItem(AUTH_GENERATION_KEY, "pending:synthetic");
    fakeWindow.dispatchEvent(new Event(AUTH_CHANGE_EVENT));
    localStorageStub.setItem(AUTH_GENERATION_KEY, "ready:synthetic");
    fakeWindow.dispatchEvent(new Event("storage"));
    localStorageStub.setItem(AUTH_GENERATION_KEY, "pending:recovered");
    fakeWindow.dispatchEvent(new Event("pageshow"));
    assert.deepEqual(observed, [true, false, true]);
  } finally {
    unsubscribe();
  }
  fakeWindow.dispatchEvent(new Event(AUTH_CHANGE_EVENT));
  fakeWindow.dispatchEvent(new Event("storage"));
  fakeWindow.dispatchEvent(new Event("pageshow"));
  assert.deepEqual(observed, [true, false, true], "退订后不得继续调用监听器");
  values.clear();
});
