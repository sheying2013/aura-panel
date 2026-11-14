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
const windowStub = {
  localStorage: localStorageStub,
  location: locationStub,
};

// network.ts 在模块顶层读 window；补齐最小浏览器壳后再动态导入，避免 SSR 期就炸
(globalThis as any).window = windowStub;

const { default: axios } = await import("axios");
const Network = (await import("../src/api/network")).default as unknown as {
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

beforeEach(() => {
  seedSession(null);
  installTransport(() =>
    Promise.reject(new Error("本用例未配置传输响应")),
  );
});

afterEach(() => {
  (axios as any).post = originalPost;
  (axios as any).get = originalGet;
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

test("取消且身份发生变化时返回身份失效，不清新会话", async () => {
  seedSession("synthetic-token-old");
  const controller = new AbortController();
  installTransport(() => {
    seedSession("synthetic-token-new");
    controller.abort();
    return Promise.reject(Object.assign(new Error("canceled"), { code: "ERR_CANCELED" }));
  });
  const result = await withinDeadline(Network.postCancellable("/node/health/summary", {}, { signal: controller.signal }));
  assert.equal(result.code, -3);
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
    await Promise.resolve();
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
      await Promise.resolve();
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
