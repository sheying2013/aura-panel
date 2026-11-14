/**
 * 组件级用例：仪表板在「另一个标签页换了账号」时的状态隔离。
 *
 * 锁死四件事：
 * 1. 身份指纹覆盖 token 与 admin/role_id：任一变化都算换账号，三项都没变就不算；
 * 2. 只有 storage 里的 token/admin/role_id（或整库 clear）才触发核对，主题/路由记忆等键不误触发；
 * 3. 从 bfcache 返回、标签页回到前台时也能补上核对（这两条路径没有 storage 事件），隐藏期间不动；
 * 4. 清空语义：旧账号的摘要（含管理员在线结论）必须落回 idle/denied 且 summary 为 null；
 *    退订后三个监听器全部摘掉，StrictMode 反复挂载/卸载不会留下监听器。
 *
 * 局限（如实记录）：同 token 重新登录时 token 与 admin/role_id 都不变，浏览器侧没有任何信号，
 * 这里检测不到，只能靠后续请求的 401/403 兜底 —— 用例不假装覆盖这种情况。
 *
 * 运行：node --import ./tests/register.mjs --test ./tests/dashboard-identity-isolation.test.tsx
 */
import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

type Listener = (event: any) => void;

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
const noop = () => undefined;

const elementStub = () => ({
  style: {} as Record<string, string>,
  innerHTML: "",
  nonce: "",
  parentNode: null,
  firstChild: { data: "" },
  setAttribute: noop,
  appendChild: noop,
  addEventListener: noop,
  removeEventListener: noop,
});

const documentStub = {
  readyState: "complete",
  visibilityState: "visible",
  documentElement: {
    style: {} as Record<string, string>,
    addEventListener: noop,
    removeEventListener: noop,
  },
  createElement: elementStub,
  createElementNS: elementStub,
  createTextNode: () => ({}),
  querySelector: () => null,
  getElementById: () => null,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  head: { appendChild: () => undefined },
  body: {
    appendChild: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  },
};

class HtmlElementStub {
  focus() {}
  blur() {}
  addEventListener() {}
  removeEventListener() {}
}

// dashboard.tsx 顶层经 @/api/network 读取 window / import.meta.env，导入前先补最小浏览器壳
const windowStub = {
  HTMLElement: HtmlElementStub,
  location: { href: "http://localhost/", origin: "http://localhost", protocol: "http:", host: "localhost", hostname: "localhost", pathname: "/dashboard" },
  localStorage: localStorageStub,
  document: documentStub,
  screen: {
    orientation: { angle: 0, type: "landscape-primary", addEventListener: noop, removeEventListener: noop },
    width: 0,
    height: 0,
  },
  visualViewport: { width: 0, height: 0, addEventListener: noop, removeEventListener: noop },
  navigator: { userAgent: "node-test", maxTouchPoints: 0, platform: "node", language: "zh-CN", languages: ["zh-CN"] },
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  matchMedia: () => ({
    matches: false,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
  }),
};

Object.assign(globalThis, {
  window: windowStub,
  document: documentStub,
  localStorage: localStorageStub,
});

const {
  NodeHealthCapacityNotice,
  identityKeyOf,
  readIdentitySnapshot,
  isAdminIdentity,
  isIdentityStorageKey,
  watchIdentity,
  clearNodeHealthSnapshot,
  denyNodeHealthSnapshot,
} = (await import("../src/pages/dashboard")) as {
  NodeHealthCapacityNotice: (props: { nodes: Array<{ nodeId: number; nodeName: string; status: string; localHistoryCapacityExceeded?: boolean }> }) => import("react").ReactElement | null;
  identityKeyOf: (identity: IdentitySnapshot) => string;
  readIdentitySnapshot: (storage: Pick<Storage, "getItem">) => IdentitySnapshot;
  isAdminIdentity: (identity: IdentitySnapshot) => boolean;
  isIdentityStorageKey: (key: string | null) => boolean;
  watchIdentity: (options: {
    read: () => IdentitySnapshot;
    onChange: (nextKey: string, previousKey: string) => void;
    initialKey?: string;
    win?: any;
    doc?: any;
  }) => () => void;
  clearNodeHealthSnapshot: (prev: NodeHealthSnapshot) => NodeHealthSnapshot;
  denyNodeHealthSnapshot: (prev: NodeHealthSnapshot) => NodeHealthSnapshot;
};

type IdentitySnapshot = { token: string | null; admin: string | null; roleId: string | null };
type NodeHealthSnapshot = {
  state: string;
  summary: { nodes: unknown[] } | null;
  error: string;
  fetchedAt: number | null;
  basis: unknown;
  version: number;
};

const identity = (
  token: string | null,
  admin: string | null,
  roleId: string | null,
): IdentitySnapshot => ({ token, admin, roleId });

function seedStorage(snapshot: IdentitySnapshot) {
  localStorageStub.clear();
  if (snapshot.token !== null) localStorageStub.setItem("token", snapshot.token);
  if (snapshot.admin !== null) localStorageStub.setItem("admin", snapshot.admin);
  if (snapshot.roleId !== null) localStorageStub.setItem("role_id", snapshot.roleId);
}

interface Target {
  addEventListener: (type: string, listener: Listener) => void;
  removeEventListener: (type: string, listener: Listener) => void;
  fire: (type: string, event?: Record<string, unknown>) => void;
  count: (type: string) => number;
}

function createTarget(): Target {
  const listeners = new Map<string, Listener[]>();

  return {
    addEventListener: (type, listener) => {
      const list = listeners.get(type) ?? [];

      list.push(listener);
      listeners.set(type, list);
    },
    removeEventListener: (type, listener) => {
      const list = listeners.get(type) ?? [];

      listeners.set(
        type,
        list.filter((item) => item !== listener),
      );
    },
    fire: (type, event = {}) => {
      for (const listener of [...(listeners.get(type) ?? [])]) listener(event);
    },
    count: (type) => (listeners.get(type) ?? []).length,
  };
}

function createEventWorld() {
  const win = createTarget();
  const doc = Object.assign(createTarget(), { visibilityState: "visible" as string });

  return { win, doc };
}

/** 订阅时以当前 localStorage 为基线，模拟组件挂载时的 identityKey */
function subscribe(world: { win: Target; doc: Target }, onChange: (nextKey: string, previousKey: string) => void) {
  return watchIdentity({
    read: () => readIdentitySnapshot(localStorageStub),
    initialKey: identityKeyOf(readIdentitySnapshot(localStorageStub)),
    onChange,
    win: world.win,
    doc: world.doc,
  });
}

const adminReadySnapshot = (): NodeHealthSnapshot => ({
  state: "ready",
  summary: {
    nodes: [{ nodeId: 1, nodeName: "node-1", status: "ONLINE", stale: false }],
  },
  error: "",
  fetchedAt: 1_700_000_000_100,
  basis: { generatedAt: 1_700_000_000_000, receivedAtMonotonic: 5_000, roundTripMs: 12 },
  version: 7,
});

beforeEach(() => {
  localStorageStub.clear();
});

test("容量降级提示直接可读且键盘和辅助技术可访问", () => {
  const nodes = [
    { nodeId: 1, nodeName: "first", status: "ONLINE" },
    { nodeId: 2, nodeName: "second", status: "UNKNOWN", localHistoryCapacityExceeded: true },
    { nodeId: 3, nodeName: "third", status: "UNKNOWN", localHistoryCapacityExceeded: true },
  ];
  const html = renderToStaticMarkup(createElement(NodeHealthCapacityNotice, { nodes }));
  assert.match(html, /role="status"/);
  assert.match(html, /tabindex="0"/i);
  assert.match(html, /历史容量已满/);
  assert.match(html, /2 个节点状态按未知显示/);
  assert.match(html, /页面重载会清空本页历史/);
  assert.match(html, /首次收到的旧缓存可能无法识别/);
  assert.equal(renderToStaticMarkup(createElement(NodeHealthCapacityNotice, { nodes: [nodes[0]] })), "");
});

test("身份指纹把 token 与 admin/role_id 一起算：任一变化都算换账号", () => {
  const base = identity("token-a", "false", "1");

  assert.equal(identityKeyOf(base), identityKeyOf(identity("token-a", "false", "1")));
  assert.notEqual(identityKeyOf(base), identityKeyOf(identity("token-b", "false", "1")));
  assert.notEqual(identityKeyOf(base), identityKeyOf(identity("token-a", "true", "0")));
  assert.notEqual(identityKeyOf(base), identityKeyOf(identity("token-a", "false", "0")));
  assert.notEqual(identityKeyOf(base), identityKeyOf(identity(null, null, null)));
});

test("管理员判定需要 token 且标记冲突时保守拒绝", () => {
  assert.equal(isAdminIdentity(identity("t", "true", "1")), false);
  assert.equal(isAdminIdentity(identity(null, "true", "0")), false);
  assert.equal(isAdminIdentity(identity("", "true", "0")), false);
  assert.equal(isAdminIdentity(identity("t", "true", "0")), true);
  assert.equal(isAdminIdentity(identity("t", "true", null)), true);
  assert.equal(isAdminIdentity(identity("t", "false", "0")), false);
  assert.equal(isAdminIdentity(identity("t", null, "0")), true);
  assert.equal(isAdminIdentity(identity("t", null, "1")), false);
  assert.equal(isAdminIdentity(identity("t", null, null)), false);
  assert.equal(isAdminIdentity(identity(null, null, null)), false);
});

test("监听安装后立即核对安装前错过的身份变更", () => {
  const previous = identity("token-old", "true", "0");
  const next = identity("token-new", "false", "1");
  seedStorage(next);
  const world = createEventWorld();
  const seen: Array<[string, string]> = [];
  const dispose = watchIdentity({
    read: () => readIdentitySnapshot(localStorageStub),
    initialKey: identityKeyOf(previous),
    onChange: (newKey, oldKey) => seen.push([newKey, oldKey]),
    win: world.win,
    doc: world.doc,
  });
  assert.deepEqual(seen, [[identityKeyOf(next), identityKeyOf(previous)]]);
  world.win.fire("storage", { key: "token" });
  assert.equal(seen.length, 1);
  dispose();
});

test("只有 token/admin/role_id 或整库 clear 才核对，主题与路由记忆不误触发", () => {
  assert.equal(isIdentityStorageKey("token"), true);
  assert.equal(isIdentityStorageKey("admin"), true);
  assert.equal(isIdentityStorageKey("role_id"), true);
  assert.equal(isIdentityStorageKey(null), true);
  assert.equal(isIdentityStorageKey("e"), false);
  assert.equal(isIdentityStorageKey("theme"), false);
  assert.equal(isIdentityStorageKey("lastNotified"), false);
});

test("跨标签页换账号：storage 只回调一次，同一身份重复通知不再回调", () => {
  seedStorage(identity("token-a", "false", "1"));
  const world = createEventWorld();
  const seen: Array<[string, string]> = [];
  const dispose = subscribe(world, (nextKey, previousKey) => seen.push([nextKey, previousKey]));

  // 无关键（主题设置）不关身份的事
  localStorageStub.setItem("theme", "dark");
  world.win.fire("storage", { key: "theme" });
  assert.equal(seen.length, 0);

  // 另一个标签页换成管理员：token/admin/role_id 一起变
  seedStorage(identity("token-b", "true", "0"));
  world.win.fire("storage", { key: "token" });
  assert.equal(seen.length, 1);
  assert.equal(seen[0][0], identityKeyOf(identity("token-b", "true", "0")));
  assert.equal(seen[0][1], identityKeyOf(identity("token-a", "false", "1")));

  // 同一份身份再通知一次：不再回调（避免整页重复重装）
  world.win.fire("storage", { key: "role_id" });
  world.win.fire("pageshow");
  assert.equal(seen.length, 1);

  dispose();
});

test("另一个标签页登出（整库 clear）也算身份变化", () => {
  seedStorage(identity("token-a", "true", "0"));
  const world = createEventWorld();
  const seen: string[] = [];
  const dispose = subscribe(world, (nextKey) => seen.push(nextKey));

  localStorageStub.clear();
  world.win.fire("storage", { key: null });

  assert.equal(seen.length, 1);
  assert.equal(seen[0], identityKeyOf(identity(null, null, null)));
  dispose();
});

test("没有 storage 事件时：pageshow / 回到前台才核对，隐藏期间不动", () => {
  seedStorage(identity("token-a", "false", "1"));
  const world = createEventWorld();
  const seen: string[] = [];
  const dispose = subscribe(world, (nextKey) => seen.push(nextKey));

  // bfcache 返回：storage 补不到，pageshow 主动核对
  seedStorage(identity("token-b", "false", "1"));
  world.doc.visibilityState = "hidden";
  world.doc.fire("visibilitychange");
  assert.equal(seen.length, 0);

  world.doc.visibilityState = "visible";
  world.doc.fire("visibilitychange");
  assert.equal(seen.length, 1);

  // 已经同步过：pageshow 不再重复回调
  world.win.fire("pageshow");
  assert.equal(seen.length, 1);

  dispose();
});

test("卸载（含 StrictMode 双挂载）后监听器全部摘掉，不再回调", () => {
  seedStorage(identity("token-a", "false", "1"));
  const world = createEventWorld();
  const seen: string[] = [];

  // StrictMode 的挂载 → 卸载 → 再挂载
  const disposeFirst = subscribe(world, (nextKey) => seen.push(nextKey));
  disposeFirst();
  const disposeSecond = subscribe(world, (nextKey) => seen.push(nextKey));

  assert.equal(world.win.count("storage"), 1);
  assert.equal(world.win.count("pageshow"), 1);
  assert.equal(world.doc.count("visibilitychange"), 1);

  disposeSecond();
  assert.equal(world.win.count("storage"), 0);
  assert.equal(world.win.count("pageshow"), 0);
  assert.equal(world.doc.count("visibilitychange"), 0);

  // 退订之后身份变化也不再回调
  seedStorage(identity("token-b", "true", "0"));
  world.win.fire("storage", { key: "token" });
  world.win.fire("pageshow");
  assert.equal(seen.length, 0);
});

test("换身份后旧账号的摘要清空：非管理员只剩拒绝态，不保留管理员在线结论", () => {
  const before = adminReadySnapshot();
  const cleared = clearNodeHealthSnapshot(before);

  // 只作废，不改写原快照：其它引用（渲染闭包）不会被动过
  assert.equal(before.state, "ready");
  assert.equal(before.version, 7);

  assert.equal(cleared.state, "idle");
  assert.equal(cleared.summary, null);
  assert.equal(cleared.error, "");
  assert.equal(cleared.fetchedAt, null);
  assert.equal(cleared.basis, null);
  assert.equal(cleared.version, 8);

  const denied = denyNodeHealthSnapshot(adminReadySnapshot());

  assert.equal(denied.state, "denied");
  assert.equal(denied.summary, null);
  assert.equal(denied.basis, null);
  assert.equal(denied.version, 8);
});
