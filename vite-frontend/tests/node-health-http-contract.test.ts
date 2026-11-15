/**
 * /node/health/summary 的 HTTP 契约回归。
 *
 * 锁死四件事：
 * 1. 后端 NodeHealthController 只暴露 POST /api/v1/node/health/summary，且要求管理员；
 * 2. dashboard 必须经 Network.postCancellable 读取，不能退回 Network.get / Network.post；
 * 3. postCancellable 底层必须是 POST，并透传 AbortSignal 与「发请求时」的 token；
 * 4. HTTP 401/403/取消都给出明确终态；迟到的 401 只在携带的 token 仍是当前 token 时才清会话，
 *    已作废的旧 token 不能把重新登录后的新会话清掉。
 *
 * 传输层用合成值 mock axios，不读真实凭据、不发真实请求。
 * 运行：node --import ./tests/register.mjs --test ./tests/node-health-http-contract.test.ts
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, test } from "node:test";
import { fileURLToPath } from "node:url";
import { createAuthIdb, waitForIdbRequest } from './fake-auth-idb';
import { createNodeHealthPoller, interpretNodeHealthResponse } from "../src/pages/nodeHealthView.ts";
import type { NodeHealthSummary } from "../src/pages/nodeHealthView.ts";

type ApiResponseShape<T = unknown> = { code: number; msg: string; data: T };

interface PostCall {
  path: string;
  data: unknown;
  config: {
    signal?: AbortSignal;
    timeout?: number;
    headers?: Record<string, string>;
  };
}

function createLocalStorage(): Storage {
  const store = new Map<string, string>();

  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    get length() {
      return store.size;
    },
  } as Storage;
}

const localStorageStub = createLocalStorage();
const locationStub = {
  href: "http://localhost/dashboard",
  pathname: "/dashboard",
  origin: "http://localhost",
};
let dispatchedEvents: string[] = [];
const windowEvents = new EventTarget();
const windowStub = {
  localStorage: localStorageStub,
  location: locationStub,
  addEventListener: windowEvents.addEventListener.bind(windowEvents),
  removeEventListener: windowEvents.removeEventListener.bind(windowEvents),
  dispatchEvent: (event: Event) => { dispatchedEvents.push(event.type); return windowEvents.dispatchEvent(event); },
};

// network.ts 在模块顶层读 window；补齐最小浏览器壳后再动态导入，避免 SSR 期就炸
(globalThis as any).window = windowStub;

const { default: axios } = await import("axios");
const networkModule = await import("../src/api/network");
const loginApi = await import("../src/api/index");
const Network = networkModule.default as unknown as {
  get: <T = unknown>(path?: string, data?: unknown) => Promise<ApiResponseShape<T | null>>;
  post: <T = unknown>(path?: string, data?: unknown) => Promise<ApiResponseShape<T | null>>;
  postCancellable: <T = unknown>(
    path?: string,
    data?: unknown,
    options?: { signal?: AbortSignal },
  ) => Promise<ApiResponseShape<T | null>>;
};

const REPO_ROOT = fileURLToPath(new URL("../../", import.meta.url));
const readRepoFile = (relative: string) =>
  readFileSync(join(REPO_ROOT, relative), "utf8");

const originalPost = axios.post;
const originalGet = axios.get;
let postCalls: PostCall[] = [];
let getCalls = 0;

function installTransport(handler: (call: PostCall) => Promise<unknown>) {
  postCalls = [];
  getCalls = 0;
  (axios as any).post = (path: string, data: unknown, config: PostCall["config"]) => {
    const call: PostCall = { path, data, config };
    postCalls.push(call);

    return handler(call);
  };
  (axios as any).get = () => {
    getCalls += 1;

    return Promise.reject(new Error("GET 不应被调用"));
  };
}

function seedSession(
  token: string | null,
  roleId: string | null = "0",
  name: string | null = "synthetic-admin",
) {
  localStorageStub.clear();
  if (token !== null) localStorageStub.setItem("token", token);
  if (roleId !== null) localStorageStub.setItem("role_id", roleId);
  if (name !== null) localStorageStub.setItem("name", name);
  if (token !== null) localStorageStub.setItem("aura_auth_generation", "pending:v2:seed");
  seedCurrentIdb();
  locationStub.pathname = "/dashboard";
  locationStub.href = "http://localhost/dashboard";
}

function httpError(status: number, data: unknown): Error {
  return Object.assign(new Error(`HTTP ${status}`), {
    response: { status, data },
  });
}

/** 契约要求 401/403/取消都 resolve 出终态；如果实现停在 pending，这里会失败而不是挂住整个用例 */
async function withinDeadline<T>(promise: Promise<T>, ms = 1000): Promise<T> {
  let handle: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    handle = setTimeout(() => reject(new Error("契约失败：请求没有给出终态")), ms);
  });

  try {
    return await Promise.race([promise, deadline]);
  } finally {
    if (handle !== undefined) clearTimeout(handle);
  }
}

async function waitForTransport(count = 1): Promise<void> {
  for (let i = 0; i < 100 && postCalls.length < count; i++) await new Promise(resolve => setTimeout(resolve, 1));
  assert.ok(postCalls.length >= count, "请求应已到达传输层");
}

const originalIdb = Object.getOwnPropertyDescriptor(globalThis, "indexedDB");
const seedCurrentIdb = () => {
  const factory = globalThis.indexedDB as unknown as { __seed?: (value: unknown) => void } | undefined;
  const snapshot = networkModule.readAuthSnapshot();
  factory?.__seed?.(snapshot.token && snapshot.generation?.startsWith('pending:v2:')
    ? { state: 'active', snapshot } : undefined);
};
const setActiveGeneration = (generation: string) => {
  localStorageStub.setItem('aura_auth_generation', generation);
  seedCurrentIdb();
};
const setIdb = (factory: unknown) => {
  Object.defineProperty(globalThis, "indexedDB", { configurable: true, value: factory });
  seedCurrentIdb();
};

beforeEach(() => {
  setIdb(createAuthIdb().factory);
  networkModule.beginReauthentication(networkModule.getReauthenticationNoticeId());
  seedSession(null);
  dispatchedEvents = [];
  installTransport(() =>
    Promise.reject(new Error("本用例未配置传输响应")),
  );
});

afterEach(() => {
  (axios as any).post = originalPost;
  (axios as any).get = originalGet;
  if (originalIdb) Object.defineProperty(globalThis, "indexedDB", originalIdb);
  else Reflect.deleteProperty(globalThis, "indexedDB");
});


test("同 token 同角色重登后，旧响应不得影响新会话", async () => {
  for (const mode of ["ok", "business401", "http401"] as const) {
    seedSession("same-token");
    setActiveGeneration("pending:v2:old");
    let finish!: (value: unknown) => void;
    let fail!: (error: Error) => void;
    installTransport(() => new Promise((resolve, reject) => { finish = resolve; fail = reject; }));
    const pending = Network.postCancellable("/node/health/summary");
    await waitForTransport();
    await networkModule.saveLoginSession({ token: "same-token", role_id: 0, name: "new" }, networkModule.readAuthSnapshot());
    if (mode === "http401") fail(httpError(401, { msg: "old" }));
    else finish({ data: { code: mode === "ok" ? 0 : 401, msg: "old", data: null } });
    assert.equal((await withinDeadline(pending)).code, -3);
    assert.equal(localStorageStub.getItem("token"), "same-token");
    assert.equal(localStorageStub.getItem("name"), "new");
    assert.match(localStorageStub.getItem("aura_auth_generation") ?? "", /^pending:v2:/);
  }
});

test("会话写入中断后仍可重新登录，成功登录每次生成新代次", async () => {
  seedSession(null);
  localStorageStub.setItem("aura_auth_generation", "pending:interrupted");
  installTransport(() => Promise.resolve({ data: { code: 0, msg: "ok", data: { token: "same-token", role_id: 0, name: "new" } } }));
  const response = await Network.post("/user/login", {});
  assert.equal(response.code, 0);
  await networkModule.saveLoginSession(response.data as { token: string; role_id: number; name: string }, networkModule.readAuthSnapshot());
  const first = localStorageStub.getItem("aura_auth_generation");
  await networkModule.saveLoginSession({ token: "same-token", role_id: 0, name: "new" }, networkModule.readAuthSnapshot());
  assert.notEqual(localStorageStub.getItem("aura_auth_generation"), first);
});

test("会话锁拒绝时明确提示并保留已有会话", async () => {
  for (const synchronous of [true, false]) {
    seedSession("old-token");
    setActiveGeneration("pending:v2:old");
    setIdb(createAuthIdb(synchronous ? { openThrows: true } : { openError: true }).factory);
    await assert.rejects(
      networkModule.saveLoginSession({ token: "new-token", role_id: 0, name: "new" }, networkModule.readAuthSnapshot()),
      (error: unknown) => error instanceof networkModule.SessionLockUnavailableError
        && error.message.includes("认证存储不可用"),
    );
    assert.equal(localStorageStub.getItem("token"), "old-token");
    assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:old");
    assert.equal(localStorageStub.getItem("name"), "synthetic-admin");
  }
  const page = readRepoFile("vite-frontend/src/pages/index.tsx");
  assert.ok(page.includes("error instanceof SessionLockUnavailableError ? error.message"));
});

test("锁回调开始后的存储错误报告为结果未确认", async () => {
  seedSession("old-token");
  setActiveGeneration("pending:v2:old");
  const originalSetItem = localStorageStub.setItem;
  const storageError = new Error("storage failed");
  localStorageStub.setItem = () => { throw storageError; };
  try {
    await assert.rejects(
      networkModule.saveLoginSession({ token: "new-token", role_id: 0, name: "new" }, networkModule.readAuthSnapshot()),
      (error: unknown) => error instanceof networkModule.SessionLockUnavailableError,
    );
    assert.equal(localStorageStub.getItem("token"), "old-token");
    assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:old");
  } finally {
    localStorageStub.setItem = originalSetItem;
  }
});

// 模拟 IDB 事务长期排队，超时或取消后迟到回调必须被忽略。
function heldAuthLock(autoGrants = 0) {
  const idb = createAuthIdb({ manual: true, autoGrants });
  setIdb(idb.factory);
  return idb;
}

test("登录锁等待有界，超时撤销真实排队且迟到回调也不写", async () => {
  seedSession("old");
  const start = networkModule.readAuthSnapshot();
  const held = heldAuthLock();
  const save = networkModule.saveLoginSession({ token: "new", role_id: 0, name: "new" }, start);
  await waitForIdbRequest(held.requests);
  await assert.rejects(withinDeadline(save, 4000), networkModule.SessionLockUnavailableError);
  assert.equal(held.requests[0].aborted, true);
  held.requests[0].grant();
  assert.equal(localStorageStub.getItem("token"), "old");
  assert.equal(localStorageStub.getItem("name"), "synthetic-admin");
});

test("401 锁等待可取消并撤销排队，超时也返回提示且不清除", async () => {
  for (const cancel of [true, false]) {
    seedSession("old");
    const held = heldAuthLock(2);
    const controller = new AbortController();
    installTransport(() => Promise.reject(httpError(401, { msg: "expired" })));
    const pending = Network.postCancellable("/user/package", {}, { signal: controller.signal });
    await waitForIdbRequest(held.requests, 3);
    if (cancel) controller.abort();
    assert.equal((await withinDeadline(pending, 4000)).code, cancel ? -2 : 401);
    assert.equal(held.requests[2].aborted, true);
    held.requests[2].grant();
    assert.equal(localStorageStub.getItem("token"), "old");
    assert.equal(locationStub.href, "http://localhost/dashboard");
    if (!cancel) assert.ok(networkModule.getReauthenticationNoticeId());
    networkModule.beginReauthentication(networkModule.getReauthenticationNoticeId());
  }
});

test("登录与退出共享锁：先退出使旧登录失效，先登录则退出不得清新会话", async () => {
  for (const first of ["logout", "login"] as const) {
    seedSession("old");
    setActiveGeneration("pending:v2:old");
    const start = networkModule.readAuthSnapshot();
    const idb = createAuthIdb({ manual: true });
    setIdb(idb.factory);
    const logout = networkModule.clearLocalSessionForLogout();
    const save = networkModule.saveLoginSession({ token: "new", role_id: 0, name: "new" }, start);
    await waitForIdbRequest(idb.requests, 2);
    if (first === "logout") {
      idb.requests[0].grant();
      assert.equal(await logout, "locked");
      idb.requests[1].grant();
      await assert.rejects(save, networkModule.LoginSessionChangedError);
      assert.equal(localStorageStub.getItem("token"), null);
      assert.match(localStorageStub.getItem("aura_auth_generation") ?? "", /^logout:/);
    } else {
      idb.requests[1].grant();
      await waitForIdbRequest(idb.requests, 3);
      idb.requests[2].grant();
      await save;
      idb.requests[0].grant();
      assert.equal(await logout, "failed");
      assert.equal(localStorageStub.getItem("token"), "new");
      assert.equal(localStorageStub.getItem("name"), "new");
    }
  }
});

test("IDB 不可用时退出失败且保留认证及偏好", async () => {
  seedSession("old");
  setActiveGeneration("pending:v2:old");
  localStorageStub.setItem("admin", "true");
  localStorageStub.setItem("theme", "dark");
  localStorageStub.setItem("forward-view-mode", "grid");
  setIdb(undefined);
  let sent = false;
  assert.equal(await networkModule.clearLocalSessionForLogout(() => { sent = true; }), "failed");
  assert.equal(sent, false, "IDB 不可用不得发送注销");
  assert.equal(localStorageStub.getItem("token"), "old");
  assert.equal(localStorageStub.getItem("admin"), "true");
  assert.equal(localStorageStub.getItem("theme"), "dark");
  assert.equal(localStorageStub.getItem("forward-view-mode"), "grid");
  assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:old");
});

test("有锁退出只清认证键；锁拒绝时不执行清理或注销", async () => {
  seedSession("old");
  localStorageStub.setItem("theme", "dark");
  let sent = false;
  assert.equal(await networkModule.clearLocalSessionForLogout(() => { sent = true; }), "locked");
  assert.equal(sent, true);
  assert.equal(localStorageStub.getItem("token"), null);
  assert.equal(localStorageStub.getItem("theme"), "dark");
  seedSession("old");
  setActiveGeneration("pending:v2:old");
  setIdb(createAuthIdb({ openThrows: true }).factory);
  sent = false;
  assert.equal(await networkModule.clearLocalSessionForLogout(() => { sent = true; }), "failed");
  assert.equal(sent, false);
  assert.equal(localStorageStub.getItem("token"), "old");
  assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:old");
});

test("退出锁超时撤销排队，迟到回调不清新会话", async () => {
  seedSession("old");
  const held = heldAuthLock();
  let sent = false;
  const logout = networkModule.clearLocalSessionForLogout(() => { sent = true; });
  await waitForIdbRequest(held.requests);
  assert.equal(await withinDeadline(logout, 4000), "failed");
  assert.equal(held.requests[0].aborted, true);
  held.requests[0].grant();
  assert.equal(sent, false);
  assert.equal(localStorageStub.getItem("token"), "old");
});

test("登录页保留快照，三个退出入口等待成功后导航", () => {
  const page = readRepoFile("vite-frontend/src/pages/index.tsx");
  assert.ok(page.indexOf("const loginStart = readAuthSnapshot()") < page.indexOf("const response = await login(loginData)"));
  assert.ok(page.includes("saveLoginSession(response.data, loginStart)"));
  for (const path of ["vite-frontend/src/layouts/admin.tsx", "vite-frontend/src/pages/profile.tsx", "vite-frontend/src/pages/change-password.tsx"]) {
    const source = readRepoFile(path);
    assert.ok(source.includes("const result = await safeLogout()") || source.includes("const result = await safeLogout(expected)"), path);
    assert.ok(source.includes("if (result === 'failed')"), path);
    assert.ok(source.includes("跨标签状态及服务端撤销均未确认"), path);
  }
  const changePassword = readRepoFile("vite-frontend/src/pages/change-password.tsx");
  assert.ok(changePassword.includes("重试退出并重新登录"));
  assert.ok(changePassword.includes("!loading && !exitPending"));
});

test("残留 pending 的登录页前置验证码检查后可登录", async () => {
  for (const captchaEnabled of [0, 1]) {
    seedSession("old-token");
    localStorageStub.setItem("aura_auth_generation", "pending:interrupted");
    installTransport(call => {
      if (call.path === "/captcha/check") return Promise.resolve({ data: { code: 0, msg: "ok", data: captchaEnabled } });
      if (call.path === "/user/login") return Promise.resolve({ data: { code: 0, msg: "ok", data: { token: "new-token", role_id: 0, name: "new" } } });
      return Promise.reject(new Error("意外请求"));
    });
    // 调用登录页实际使用的 checkCaptcha/login，而不只是直接请求登录端点。
    const check = await loginApi.checkCaptcha();
    assert.equal(check.code, 0);
    assert.equal(check.data, captchaEnabled);
    assert.equal(localStorageStub.getItem("token"), "old-token");
    assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:interrupted");
    const response = await loginApi.login({ username: "fixture", password: "fixture", captchaId: captchaEnabled ? "verified-fixture" : "" });
    assert.equal(response.code, 0);
    assert.deepEqual(postCalls.map(call => call.path), ["/captcha/check", "/user/login"]);
    await networkModule.saveLoginSession(response.data, networkModule.readAuthSnapshot());
    assert.equal(localStorageStub.getItem("token"), "new-token");
    assert.match(localStorageStub.getItem("aura_auth_generation") ?? "", /^pending:v2:/);
  }
  const page = readRepoFile("vite-frontend/src/pages/index.tsx");
  assert.ok(page.includes("const checkResponse = await checkCaptcha()"));
  assert.ok(page.includes("const response = await login(loginData)"));
  assert.ok(page.includes("requestCaptchaDataUrl: `${baseURL}captcha/generate`"));
  assert.ok(page.includes("validCaptchaUrl: `${baseURL}captcha/verify`"));
  assert.ok(page.includes("performLogin()"));
  const app = readRepoFile("vite-frontend/src/App.tsx");
  assert.ok(app.includes('session.state === "verified" && isLoggedIn()'));
  assert.ok(app.includes('session.state === "unknown"'));
  assert.ok(app.includes("revalidateAuthSession()"));
});

test("pending 验证码请求报错或身份变化不清会话", async () => {
  seedSession("old-token");
  localStorageStub.setItem("aura_auth_generation", "pending:old");
  installTransport(() => Promise.reject(httpError(401, { msg: "captcha error" })));
  assert.equal((await loginApi.checkCaptcha()).code, 401);
  assert.equal(localStorageStub.getItem("token"), "old-token");
  assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:old");
  assert.equal(networkModule.getReauthenticationNoticeId(), 0);
  let resolve!: (value: unknown) => void;
  installTransport(() => new Promise(done => { resolve = done; }));
  const pending = loginApi.checkCaptcha();
  localStorageStub.setItem("token", "new-token");
  setActiveGeneration("pending:v2:new");
  resolve({ data: { code: 0, msg: "ok", data: 0 } });
  assert.equal((await pending).code, -3);
  assert.equal(localStorageStub.getItem("token"), "new-token");
  assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:new");
});

test("多键写入中、未登录和无锁环境的 401 按保守口径处理", async () => {
  seedSession(null);
  installTransport(() => Promise.reject(httpError(401, { msg: "no token" })));
  assert.equal((await Network.postCancellable("/user/package")).code, -3);
  assert.equal(postCalls.length, 0);
  assert.equal(locationStub.href, "http://localhost/dashboard");
  seedSession("same-token");
  localStorageStub.setItem("aura_auth_generation", "pending:new");
  assert.equal((await Network.postCancellable("/user/package")).code, -3);
  setActiveGeneration("pending:v2:new");
  setIdb(undefined);
  assert.equal((await Network.postCancellable("/user/package")).code, -3);
  assert.equal(localStorageStub.getItem("token"), "same-token");
});

test("普通 GET/POST 旧 401 的代次隔离及终态", async () => {
  for (const method of ["get", "post"] as const) {
    seedSession("same-token");
    setActiveGeneration("pending:v2:old");
    let reject!: (error: Error) => void;
    const deferred = () => new Promise((_resolve, fail) => { reject = fail; });
    (axios as any).get = deferred;
    (axios as any).post = deferred;
    const pending = Network[method]("/somewhere");
    for (let i = 0; i < 100 && !reject; i++) await new Promise(resolve => setTimeout(resolve, 1));
    assert.ok(reject, "请求应已到达传输层");
    await networkModule.saveLoginSession({ token: "same-token", role_id: 0, name: "new" }, networkModule.readAuthSnapshot());
    reject(httpError(401, { msg: "old" }));
    assert.equal((await withinDeadline(pending)).code, -3);
    assert.equal(localStorageStub.getItem("name"), "new");
  }
});

test("401 等待锁期间新会话先提交时，锁内重新校验", async () => {
  seedSession("same-token");
  setActiveGeneration("pending:v2:old");
  const idb = createAuthIdb({ manual: true, autoGrants: 2 });
  setIdb(idb.factory);
  installTransport(() => Promise.reject(httpError(401, { msg: "old" })));
  const pending = Network.postCancellable("/user/package");
  await waitForIdbRequest(idb.requests, 3);
  localStorageStub.setItem("name", "new");
  setActiveGeneration("pending:v2:new");
  idb.requests[2].grant();
  assert.equal((await withinDeadline(pending)).code, -3);
  assert.equal(localStorageStub.getItem("token"), "same-token");
  assert.equal(localStorageStub.getItem("name"), "new");
});

test("无锁 401 显示一次持久提示，显式重登不清会话", async () => {
  seedSession("same-token");
  setActiveGeneration("pending:v2:old");
  setIdb(createAuthIdb({ openErrorAfter: 2 }).factory);
  installTransport(() => Promise.reject(httpError(401, { msg: "expired" })));
  assert.equal((await Network.postCancellable("/user/package")).code, 401);
  setIdb(createAuthIdb({ openErrorAfter: 2 }).factory);
  assert.equal((await Network.postCancellable("/user/package")).code, 401);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), true);
  assert.equal(dispatchedEvents.filter(name => name === "aura-reauth-notice").length, 1);
  assert.equal(locationStub.href, "http://localhost/dashboard");
  assert.equal(networkModule.beginReauthentication(networkModule.getReauthenticationNoticeId()), true);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
  assert.equal(localStorageStub.getItem("token"), "same-token");
  assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:old");
  assert.equal(locationStub.href, "http://localhost/dashboard");
});

test("旧请求及旧提示在同 token 新代次后均失效", async () => {
  seedSession("same-token");
  setActiveGeneration("pending:v2:old");
  let reject!: (error: Error) => void;
  installTransport(() => new Promise((_resolve, fail) => { reject = fail; }));
  const pending = Network.postCancellable("/user/package");
  await waitForTransport();
  setActiveGeneration("pending:v2:new");
  reject(httpError(401, { msg: "old" }));
  assert.equal((await pending).code, -3);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
  assert.equal(dispatchedEvents.filter(name => name === "aura-reauth-notice").length, 0);

  setActiveGeneration("pending:v2:old");
  setIdb(createAuthIdb({ openErrorAfter: 2 }).factory);
  installTransport(() => Promise.resolve({ data: { code: 401, msg: "expired", data: null } }));
  assert.equal((await Network.post("/user/package")).code, 401);
  const oldNotice = networkModule.getReauthenticationNoticeId();
  assert.notEqual(oldNotice, 0);
  setActiveGeneration("pending:v2:new");
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
  assert.equal(networkModule.beginReauthentication(oldNotice), false);
  assert.equal(localStorageStub.getItem("token"), "same-token");
  assert.equal(localStorageStub.getItem("aura_auth_generation"), "pending:v2:new");
});

test("无 token、登录 401、取消及锁失败后的变化不产生旧提示", async () => {
  setIdb(undefined);
  seedSession(null);
  installTransport(() => Promise.reject(httpError(401, { msg: "no token" })));
  assert.equal((await Network.post("/user/package")).code, -3);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
  seedSession("same-token");
  assert.equal((await Network.post("/user/login")).code, 401);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
  const controller = new AbortController();
  controller.abort();
  assert.equal((await Network.postCancellable("/user/package", {}, { signal: controller.signal })).code, -2);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
  setActiveGeneration("pending:v2:old");
  setIdb(createAuthIdb({ openError: true, beforeOpenError: () => {
    setActiveGeneration("pending:v2:new");
  } }).factory);
  assert.equal((await Network.post("/user/package")).code, -3);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), false);
});

test("跨标签身份变更清除提示，退订后监听停止", async () => {
  seedSession("same-token");
  setActiveGeneration("pending:v2:old");
  setIdb(createAuthIdb({ openErrorAfter: 2 }).factory);
  let notifications = 0;
  const unsubscribe = networkModule.subscribeReauthenticationNotice(() => { notifications += 1; });
  installTransport(() => Promise.reject(httpError(401, {})));
  assert.equal((await Network.post("/user/package")).code, 401);
  assert.equal(notifications, 1);
  const oldNotice = networkModule.getReauthenticationNoticeId();
  assert.notEqual(oldNotice, 0);
  setActiveGeneration("pending:v2:new");
  windowStub.dispatchEvent(new Event("storage"));
  assert.equal(networkModule.getReauthenticationNoticeId(), 0);
  assert.equal(notifications, 2);
  setIdb(createAuthIdb({ openErrorAfter: 2 }).factory);
  assert.equal((await Network.post("/user/package")).code, 401);
  const newNotice = networkModule.getReauthenticationNoticeId();
  assert.notEqual(newNotice, 0);
  assert.notEqual(newNotice, oldNotice);
  assert.equal(networkModule.beginReauthentication(oldNotice), false);
  assert.equal(networkModule.getReauthenticationNoticeId(), newNotice);
  unsubscribe();
  const before = notifications;
  assert.equal(networkModule.beginReauthentication(newNotice), true);
  assert.equal(notifications, before);
});

test("锁请求失败但当前身份未变时提供恢复提示", async () => {
  seedSession("same-token");
  setActiveGeneration("pending:v2:old");
  setIdb(createAuthIdb({ openErrorAfter: 2 }).factory);
  installTransport(() => Promise.reject(httpError(401, {})));
  assert.equal((await Network.post("/user/package")).code, 401);
  assert.equal(!!networkModule.getReauthenticationNoticeId(), true);
  assert.equal(localStorageStub.getItem("token"), "same-token");
});

test("路由显式重登与可访问提示的接线", () => {
  const app = readRepoFile("vite-frontend/src/App.tsx");
  assert.ok(app.includes('get("reauth") === "1"'));
  assert.ok(app.includes("authenticated && !reauth"));
  assert.ok(app.includes('role="alert"'));
  assert.ok(app.includes('if (beginReauthentication(noticeId)) navigate("/?reauth=1")'));
  assert.doesNotMatch(app, /localStorage\.(?:clear|removeItem)/);
});

test("后端 NodeHealthController：/summary 只接受 POST，且要求管理员", () => {
  const controllerSource = readRepoFile(
    "springboot-backend/src/main/java/com/admin/controller/NodeHealthController.java",
  );

  assert.match(
    controllerSource,
    /@RequestMapping\("\/api\/v1\/node\/health"\)/,
    "路由前缀必须是 /api/v1/node/health",
  );
  assert.match(
    controllerSource,
    /@RequireRole\s+@PostMapping\("\/summary"\)\s+public R summary\(\)/,
    "summary 必须是 @RequireRole + @PostMapping(\"/summary\")",
  );
  assert.doesNotMatch(
    controllerSource,
    /@GetMapping/,
    "健康摘要只读但必须走 POST，不能留 GET 入口",
  );
});

test("dashboard：/node/health/summary 只经 Network.postCancellable 读取", () => {
  const dashboardSource = readRepoFile("vite-frontend/src/pages/dashboard.tsx");
  const requestLines = dashboardSource
    .split("\n")
    .filter((line) => /Network\.\w+[^\n]*\/node\/health\/summary/.test(line));

  assert.equal(requestLines.length, 1, "摘要接口应当只有一处请求入口");
  assert.match(
    requestLines[0],
    /Network\.postCancellable<[^>]*>\(\s*['"]\/node\/health\/summary['"]\s*,\s*\{\}\s*,\s*\{\s*signal\s*\}\s*\)/,
    "必须带 signal 走可取消 POST",
  );
  assert.doesNotMatch(
    dashboardSource,
    /Network\.(?:get|post)\(\s*['"]\/node\/health\/summary/,
    "不能退回无取消、无 401/403 终态保证的 Network.get / Network.post",
  );
});

test("postCancellable：以 POST 发送，并透传 signal 与发请求时的 token", async () => {
  seedSession("synthetic-token-current");
  const controller = new AbortController();
  const payload = { code: 0, msg: "ok", data: { nodes: [] } };
  installTransport(() => Promise.resolve({ data: payload }));

  const result = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, { signal: controller.signal }),
  );

  assert.deepEqual(result, payload);
  assert.equal(postCalls.length, 1, "只允许一次 POST");
  assert.equal(getCalls, 0, "不得用 GET 读取摘要");
  assert.equal(postCalls[0].path, "/node/health/summary");
  assert.deepEqual(postCalls[0].data, {});
  assert.equal(postCalls[0].config.signal, controller.signal);
  assert.equal(
    postCalls[0].config.headers?.Authorization,
    "synthetic-token-current",
    "Authorization 必须是发请求时的 token",
  );
});

test("HTTP 401 且携带的是当前 token：返回 401 终态并清除会话", async () => {
  seedSession("synthetic-token-current");
  installTransport(() =>
    Promise.reject(httpError(401, { code: 401, msg: "登录已失效，请重新登录" })),
  );

  const result = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, {}),
  );

  assert.equal(result.code, 401);
  assert.equal(result.msg, "登录已失效，请重新登录");
  assert.equal(result.data, null);
  assert.equal(localStorageStub.getItem("token"), null);
  assert.equal(localStorageStub.getItem("role_id"), null);
  assert.equal(localStorageStub.getItem("name"), null);
  assert.equal(locationStub.href, "/", "401 后应跳回登录页");
});

test("HTTP 401 来自已作废的旧 token：保留重新登录后的新会话", async () => {
  seedSession("synthetic-token-old");
  installTransport(() => {
    // 请求在途期间用户重新登录：当前 token 已经换成新的
    localStorageStub.setItem("token", "synthetic-token-reissued");
    localStorageStub.setItem("role_id", "0");
    localStorageStub.setItem("name", "synthetic-admin-2");

    return Promise.reject(httpError(401, { code: 401, msg: "旧凭据已失效" }));
  });

  const result = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, {}),
  );

  assert.equal(result.code, -3);
  assert.equal(result.msg, "请求身份已变化，请重新读取");
  assert.equal(
    localStorageStub.getItem("token"),
    "synthetic-token-reissued",
    "迟到的旧 401 不得清掉新 token",
  );
  assert.equal(localStorageStub.getItem("role_id"), "0");
  assert.equal(localStorageStub.getItem("name"), "synthetic-admin-2");
  assert.equal(locationStub.href, "http://localhost/dashboard", "不得跳回登录页");
});

test("旧身份的 200 和 403 都作废，保留新登录", async () => {
  for (const status of [200, 403]) {
    seedSession("synthetic-token-old");
    let resolveResponse: (value: unknown) => void = () => {};
    let rejectResponse: (reason: unknown) => void = () => {};
    installTransport(() => new Promise((resolve, reject) => {
      resolveResponse = resolve;
      rejectResponse = reject;
    }));
    const pending = Network.postCancellable("/node/health/summary", {}, {});
    await waitForTransport();
    seedSession("synthetic-token-new", "1", "new-user");
    if (status === 200) {
      resolveResponse({ status, data: { code: 0, msg: "ok", data: { nodes: [] } } });
    } else {
      rejectResponse(httpError(status, { code: 403, msg: "old denied" }));
    }
    const result = await withinDeadline(pending);
    assert.equal(result.code, -3);
    assert.equal(result.data, null);
    assert.equal(localStorageStub.getItem("token"), "synthetic-token-new");
    assert.equal(localStorageStub.getItem("name"), "new-user");
    assert.equal(locationStub.href, "http://localhost/dashboard");
  }
});

test("HTTP 500 的 code:0 响应绝不作为成功", async () => {
  seedSession("synthetic-token-current");
  for (const rejected of [true, false]) {
    const body = { code: 0, msg: "error body", data: { nodes: [] } };
    installTransport(() => rejected
      ? Promise.reject(httpError(500, body))
      : Promise.resolve({ status: 500, data: body }));
    const result = await withinDeadline(Network.postCancellable("/node/health/summary", {}, {}));
    assert.equal(result.code, -1);
    assert.equal(result.data, null);
    assert.equal(localStorageStub.getItem("token"), "synthetic-token-current");
  }
});

test("取消且身份发生变化时优先返回取消，不清新会话", async () => {
  seedSession("synthetic-token-old");
  const controller = new AbortController();
  installTransport(() => {
    seedSession("synthetic-token-new");
    controller.abort();
    return Promise.reject(Object.assign(new Error("canceled"), { code: "ERR_CANCELED" }));
  });
  const result = await withinDeadline(Network.postCancellable("/node/health/summary", {}, { signal: controller.signal }));
  assert.equal(result.code, -2);
  assert.equal(result.data, null);
  assert.equal(localStorageStub.getItem("token"), "synthetic-token-new");
});

test("stop 或唤醒作废在途请求，迟到 401 不清会话", async () => {
  for (const action of ["stop", "wake"] as const) {
    seedSession("synthetic-token-current");
    const replies: Array<{ reject: (error: unknown) => void; resolve: (response: unknown) => void }> = [];
    installTransport(() => new Promise((resolve, reject) => replies.push({ resolve, reject })));
    let wake: (() => void) | undefined;
    const states: string[] = [];
    const poller = createNodeHealthPoller({
      read: async (signal) => interpretNodeHealthResponse(
        await Network.postCancellable<NodeHealthSummary | null>("/node/health/summary", {}, { signal }),
      ),
      subscribeWake: (handler) => { wake = handler; return () => { wake = undefined; }; },
      onChange: (snapshot) => states.push(snapshot.state),
    });
    poller.start();
    const first = poller.refresh();
    await waitForTransport();
    assert.equal(replies.length, 1);
    const signal = postCalls[0].config.signal;
    if (action === "stop") poller.stop();
    else wake?.();
    assert.equal(signal?.aborted, true);
    replies[0].reject(httpError(401, { code: 401, msg: "old" }));
    assert.equal(await withinDeadline(first), false);
    assert.equal(localStorageStub.getItem("token"), "synthetic-token-current");
    assert.equal(locationStub.href, "http://localhost/dashboard");
    assert.equal(states.includes("denied"), false);
    if (action === "wake") {
      await waitForTransport(2);
      assert.equal(replies.length, 2);
      replies[1].resolve({ data: { code: 0, msg: "ok", data: { nodes: [] } } });
      await Promise.resolve();
      poller.stop();
    }
  }
});

test("HTTP 403：返回明确无权限终态，不清会话也不跳登录", async () => {
  seedSession("synthetic-token-current");
  let step = 0;
  installTransport(() => {
    const body =
      step++ === 0 ? { code: 403, msg: "当前账号无节点健康查看权限" } : {};

    return Promise.reject(httpError(403, body));
  });

  const withServerMsg = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, {}),
  );
  const withDefaultMsg = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, {}),
  );

  assert.equal(withServerMsg.code, 403);
  assert.equal(withServerMsg.msg, "当前账号无节点健康查看权限");
  assert.equal(withServerMsg.data, null);
  assert.equal(withDefaultMsg.code, 403);
  assert.equal(withDefaultMsg.msg, "当前账号无该项查看权限");
  assert.equal(localStorageStub.getItem("token"), "synthetic-token-current");
  assert.equal(locationStub.href, "http://localhost/dashboard");
});

test("读取被取消：signal 中止或 axios 取消错误都返回 -2，且不清会话", async () => {
  seedSession("synthetic-token-current");
  const controller = new AbortController();
  installTransport(() => {
    controller.abort();

    // 响应在取消之后才到：必须按取消处理，不能把迟到的成功写回界面
    return Promise.resolve({ data: { code: 0, msg: "ok", data: { nodes: [] } } });
  });

  const aborted = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, { signal: controller.signal }),
  );
  assert.equal(aborted.code, -2);
  assert.equal(aborted.msg, "读取已取消");
  assert.equal(aborted.data, null);
  assert.equal(localStorageStub.getItem("token"), "synthetic-token-current");

  installTransport(() =>
    Promise.reject(
      Object.assign(new Error("canceled"), {
        code: "ERR_CANCELED",
        name: "CanceledError",
      }),
    ),
  );
  const canceledError = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, {}),
  );
  assert.equal(canceledError.code, -2);
  assert.equal(canceledError.msg, "读取已取消");
});

test("取消与 401 同时发生：优先按取消处理，不得清新会话", async () => {
  seedSession("synthetic-token-current");
  const controller = new AbortController();
  installTransport(() => {
    controller.abort();

    return Promise.reject(httpError(401, { code: 401, msg: "登录已失效" }));
  });

  const result = await withinDeadline(
    Network.postCancellable("/node/health/summary", {}, { signal: controller.signal }),
  );

  assert.equal(result.code, -2, "卸载时的取消不能被当成登录失效");
  assert.equal(localStorageStub.getItem("token"), "synthetic-token-current");
  assert.equal(locationStub.href, "http://localhost/dashboard");
});
