/**
 * 纯逻辑用例：节点健康状态归一、过期结论折成 UNKNOWN、计数与明细同源，
 * 以及只读摘要的刷新语义（跨时钟不误判、在途耗时保守计入、到期自己触发渲染、
 * 回到前台重新验证、失败不沿用旧在线、并发去重、超时/取消、定时器清理）。
 * 运行：node --test --experimental-strip-types src/pages/nodeHealthView.test.ts
 */
import type {
  NodeHealthNode,
  NodeHealthReadResult,
  NodeHealthPoller,
  NodeHealthSnapshot,
  NodeHealthSummary,
  NodeHealthTimeBasis,
  NodeHealthTimers,
} from "./nodeHealthView.ts";

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  NODE_HEALTH_READ_TIMEOUT_MS,
  NODE_STATUS_ORDER,
  applyNodeHealthFreshness,
  countNodeHealth,
  createNodeHealthPoller,
  interpretNodeHealthResponse,
  nextNodeHealthExpiryInMs,
  nodeHealthAgeMs,
  nodeHealthStatusOf,
  normalizeNodeStatus,
  resolveNodeHealthCounts,
  shouldInvalidateSessionToken,
  subscribeNodeHealthWake,
} from "./nodeHealthView.ts";

const node = (
  overrides: Pick<NodeHealthNode, "nodeId" | "status"> &
    Partial<Omit<NodeHealthNode, "nodeId" | "status">>,
): NodeHealthNode => ({
  ...overrides,
  nodeName: overrides.nodeName ?? `node-${overrides.nodeId}`,
});

test("normalizeNodeStatus 只认四个状态，其余一律未知", () => {
  assert.equal(normalizeNodeStatus("ONLINE"), "ONLINE");
  assert.equal(normalizeNodeStatus("online"), "ONLINE");
  assert.equal(normalizeNodeStatus("DEGRADED"), "DEGRADED");
  assert.equal(normalizeNodeStatus("OFFLINE"), "OFFLINE");
  assert.equal(normalizeNodeStatus("UNKNOWN"), "UNKNOWN");
  assert.equal(normalizeNodeStatus(""), "UNKNOWN");
  assert.equal(normalizeNodeStatus(undefined), "UNKNOWN");
  assert.equal(normalizeNodeStatus("WHATEVER"), "UNKNOWN");
  assert.deepEqual(
    [...NODE_STATUS_ORDER],
    ["ONLINE", "DEGRADED", "OFFLINE", "UNKNOWN"],
  );
});

test("过期结论按未知展示：stale 的 ONLINE / OFFLINE 都不再是它自己", () => {
  assert.equal(
    nodeHealthStatusOf({ status: "ONLINE", stale: false }),
    "ONLINE",
  );
  assert.equal(
    nodeHealthStatusOf({ status: "ONLINE", stale: true }),
    "UNKNOWN",
  );
  assert.equal(
    nodeHealthStatusOf({ status: "OFFLINE", stale: true }),
    "UNKNOWN",
  );
  assert.equal(
    nodeHealthStatusOf({ status: "DEGRADED", stale: true }),
    "UNKNOWN",
  );
  assert.equal(nodeHealthStatusOf({ status: "OFFLINE" }), "OFFLINE");
  assert.equal(nodeHealthStatusOf(null), "UNKNOWN");
  assert.equal(nodeHealthStatusOf(undefined), "UNKNOWN");
});

test("countNodeHealth 把过期结论计进未知，并单独统计旧结果条数", () => {
  const nodes = [
    node({ nodeId: 1, status: "ONLINE" }),
    node({ nodeId: 2, status: "ONLINE", stale: true }),
    node({ nodeId: 3, status: "OFFLINE", stale: true }),
    node({ nodeId: 4, status: "DEGRADED" }),
    node({ nodeId: 5, status: "UNKNOWN" }),
  ];

  assert.deepEqual(countNodeHealth(nodes), {
    total: 5,
    online: 1,
    degraded: 1,
    offline: 0,
    unknown: 3,
    staleCount: 2,
  });
});

test("resolveNodeHealthCounts 以明细为准：后端把过期节点算成在线也不作数", () => {
  const backendCounts: NodeHealthSummary = {
    total: 2,
    online: 2,
    offline: 0,
    degraded: 0,
    unknown: 0,
  };
  const nodes = [
    node({ nodeId: 1, status: "ONLINE" }),
    node({ nodeId: 2, status: "ONLINE", stale: true, lastLatencyMs: 8 }),
  ];

  assert.deepEqual(resolveNodeHealthCounts(backendCounts, nodes), {
    total: 2,
    online: 1,
    degraded: 0,
    offline: 0,
    unknown: 1,
    staleCount: 1,
  });
});

test("resolveNodeHealthCounts 无明细时用后端计数兜底，缺字段按 0", () => {
  assert.deepEqual(
    resolveNodeHealthCounts(
      { total: 3, online: 1, unknown: 2, staleCount: 2 },
      [],
    ),
    {
      total: 3,
      online: 1,
      degraded: 0,
      offline: 0,
      unknown: 2,
      staleCount: 2,
    },
  );
  assert.deepEqual(resolveNodeHealthCounts(null, []), {
    total: 0,
    online: 0,
    degraded: 0,
    offline: 0,
    unknown: 0,
    staleCount: 0,
  });
});

test("空明细的节点数组不回退后端计数：明细为空就是空", () => {
  assert.deepEqual(resolveNodeHealthCounts({ nodes: [], total: 4, online: 4 }, []), {
    total: 0,
    online: 0,
    degraded: 0,
    offline: 0,
    unknown: 0,
    staleCount: 0,
  });
});

// ========== 时间基准：年龄只用服务器时间差 + 本机单调耗时 ==========

const summaryWithNodes = (...nodes: NodeHealthNode[]): NodeHealthSummary => ({
  nodes,
  total: nodes.length,
});

/** 时间基准：服务器在 generatedAt 组装摘要，本机在 receivedAtMonotonic 收到，往返 roundTripMs */
const basis = (
  generatedAt?: number | null,
  receivedAtMonotonic: number | null = 0,
  roundTripMs: number | null = 0,
): NodeHealthTimeBasis => ({ generatedAt, receivedAtMonotonic, roundTripMs });

test("年龄只用服务器时间差加本机实测耗时，本机墙上时钟不参与", () => {
  const online = node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 });

  // 服务器认为过了 60s；本机往返 200ms、收到后又过了 800ms
  assert.equal(
    nodeHealthAgeMs(online, basis(61_000, 500, 200), 1_300),
    60_000 + 200 + 800,
  );
  // 缺任何一侧的时间元数据都不猜
  assert.equal(nodeHealthAgeMs(online, basis(undefined, 500, 200), 1_300), null);
  assert.equal(nodeHealthAgeMs(online, null, 1_300), null);
  assert.equal(nodeHealthAgeMs(online, basis(61_000, null, 200), 1_300), null);
  assert.equal(
    nodeHealthAgeMs(node({ nodeId: 2, status: "ONLINE" }), basis(61_000), 1_300),
    null,
  );
});

test("检测时刻晚于摘要生成时刻（服务器时钟回拨）按不可信处理", () => {
  assert.equal(
    nodeHealthAgeMs(
      node({ nodeId: 1, status: "ONLINE", checkedAt: 2_000 }),
      basis(1_000),
      0,
    ),
    null,
  );
});

test("跨时钟偏差不误判：服务器时钟比本机慢一天也照常判在有效期内", () => {
  const nodes = [node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 })];
  // 服务器时间戳停在 2_000（本机墙上时钟此刻可能是 1e12），年龄只有 1s + 5ms
  const result = applyNodeHealthFreshness(nodes, 60_000, basis(2_000, 10, 5), 1_010);

  assert.equal(result, nodes);
  assert.equal(result[0].status, "ONLINE");
  assert.equal(result[0].stale, undefined);
});

test("在途耗时保守计满：服务器年龄刚好等于有效期时也按过期处理", () => {
  const nodes = [
    node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000, latencyMs: 7 }),
  ];

  // 60_000 ≤ 60_000：刚好还在有效期内，数据原样返回
  assert.equal(applyNodeHealthFreshness(nodes, 60_000, basis(61_000, 0, 0), 0), nodes);

  // 只多算 1ms 的往返耗时，就按过期展示
  const withRtt = applyNodeHealthFreshness(nodes, 60_000, basis(61_000, 0, 1), 0);
  assert.equal(withRtt[0].status, "UNKNOWN");
  assert.equal(withRtt[0].stale, true);
  assert.equal(withRtt[0].latencyMs, null);
  assert.equal(withRtt[0].lastLatencyMs, 7);
  assert.equal(withRtt[0].staleNote, "上次检测结论已过期，仅作历史参考，不计入在线");
});

test("越过后端有效期折成未知：延迟转历史，检测时间与 detail 保留", () => {
  const nodes = [
    node({
      nodeId: 1,
      status: "ONLINE",
      checkedAt: 1_000,
      latencyMs: 12,
      detail: "控制通道 200",
    }),
  ];

  // 服务器年龄 60_000 + 往返 500 + 收到后 501 = 61_001 > 60_000
  const result = applyNodeHealthFreshness(nodes, 60_000, basis(61_000, 0, 500), 501);

  assert.deepEqual(result[0], {
    nodeId: 1,
    nodeName: "node-1",
    status: "UNKNOWN",
    checkedAt: 1_000,
    stale: true,
    staleNote: "上次检测结论已过期，仅作历史参考，不计入在线",
    latencyMs: null,
    lastLatencyMs: 12,
    detail: "控制通道 200",
  });
  assert.equal(nodeHealthStatusOf(result[0]), "UNKNOWN");
  assert.deepEqual(countNodeHealth(result), {
    total: 1,
    online: 0,
    degraded: 0,
    offline: 0,
    unknown: 1,
    staleCount: 1,
  });
});

test("已有历史延迟或 staleNote 时保留，不被兜底覆盖", () => {
  const result = applyNodeHealthFreshness(
    [
      node({
        nodeId: 2,
        status: "OFFLINE",
        checkedAt: 1_000,
        latencyMs: null,
        lastLatencyMs: 33,
        staleNote: "后端标注的历史结论",
      }),
    ],
    60_000,
    basis(120_000),
    0,
  );

  assert.equal(result[0].lastLatencyMs, 33);
  assert.equal(result[0].staleNote, "后端标注的历史结论");
  assert.equal(result[0].latencyMs, null);
});

test("没有可信时间基准时，在线、降级、离线一律按未知", () => {
  const nodes = [
    node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 }),
    node({ nodeId: 2, status: "DEGRADED", checkedAt: 1_000 }),
    node({ nodeId: 3, status: "OFFLINE", checkedAt: 1_000 }),
  ];

  const result = applyNodeHealthFreshness(nodes, 60_000, null, 0);

  assert.equal(result[0].status, "UNKNOWN");
  assert.equal(result[0].staleNote, "缺少可信检测时间，无法确认当前状态，按未知展示");
  assert.equal(result[1].status, "UNKNOWN");
  assert.equal(result[2].status, "UNKNOWN");
  assert.deepEqual(countNodeHealth(result), {
    total: 3,
    online: 0,
    degraded: 0,
    offline: 0,
    unknown: 3,
    staleCount: 3,
  });
});

test("离线检测时间缺失、无效或时钟回退时保留历史并显示未知", () => {
  const offline = node({ nodeId: 1, status: "OFFLINE", checkedAt: 2_000,
    latencyMs: 23, detail: "上次探测失败" });
  const cases: Array<[NodeHealthNode, NodeHealthTimeBasis | null, number]> = [
    [node({ ...offline, checkedAt: undefined }), basis(3_000), 0],
    [node({ ...offline, checkedAt: Number.NaN }), basis(3_000), 0],
    [offline, basis(1_000), 0],
    [offline, basis(3_000, 100), 99],
    [offline, basis(3_000), Number.NaN],
    [offline, null, 0],
  ];
  for (const [entry, timeBasis, now] of cases) {
    const result = applyNodeHealthFreshness([entry], 60_000, timeBasis, now)[0];
    assert.equal(result.status, "UNKNOWN");
    assert.equal(result.stale, true);
    assert.equal(result.detail, "上次探测失败");
    assert.equal(result.latencyMs, null);
    assert.equal(result.lastLatencyMs, 23);
  }
});

test("有效期缺失或无效时在线、降级和离线均按未知保留历史", () => {
  const nodes = [
    node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 }),
    node({ nodeId: 2, status: "OFFLINE", checkedAt: 1_000, latencyMs: 18, detail: "上次离线" }),
    node({ nodeId: 3, status: "DEGRADED", checkedAt: 1_000 }),
  ];

  for (const freshness of [undefined, null, 0, -1, Number.NaN]) {
    const result = applyNodeHealthFreshness(nodes, freshness, basis(2_000), 0);

    assert.equal(result[0].status, "UNKNOWN");
    assert.equal(
      result[0].staleNote,
      "后端未提供结论有效期，无法确认当前状态，按未知展示",
    );
    assert.equal(result[1].status, "UNKNOWN");
    assert.equal(result[1].stale, true);
    assert.equal(result[1].latencyMs, null);
    assert.equal(result[1].lastLatencyMs, 18);
    assert.equal(result[1].detail, "上次离线");
    assert.equal(result[2].status, "UNKNOWN");
  }
});

test("已过期标记的节点不重复改写", () => {
  const stale = node({
    nodeId: 1,
    status: "UNKNOWN",
    stale: true,
    checkedAt: 1_000,
    staleNote: "后端已标过期",
  });
  const nodes = [stale];

  assert.equal(applyNodeHealthFreshness(nodes, 60_000, basis(9_999_999), 0), nodes);
  assert.equal(nodes[0].staleNote, "后端已标过期");
});

test("nextNodeHealthExpiryInMs 取最早到期的那条，已过期的结论不再返回 0", () => {
  const nodes = [
    node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 }),
    node({ nodeId: 2, status: "ONLINE", checkedAt: 30_000 }),
    node({ nodeId: 3, status: "ONLINE", checkedAt: 1_000, stale: true }),
  ];

  // 年龄 39_000 / 10_000，取更早到期的那条
  assert.equal(nextNodeHealthExpiryInMs(nodes, 60_000, basis(40_000), 0), 21_001);
  // 恰好相等仍未过期，必须排在至少 1ms 后。
  const boundary = [node({ nodeId: 4, status: "ONLINE", checkedAt: 1_000 })];
  assert.equal(nextNodeHealthExpiryInMs(boundary, 60_000, basis(61_000), 0), 1);
  assert.equal(nextNodeHealthExpiryInMs(boundary, 60_000, basis(61_001), 0), null);
  // 没有有效期就算不出到期
  assert.equal(nextNodeHealthExpiryInMs(nodes, undefined, basis(40_000), 0), null);
});

// ========== 摘要响应解释 ==========

test("interpretNodeHealthResponse 区分拿到摘要、被拒与读取失败", () => {
  const summary = summaryWithNodes(node({ nodeId: 1, status: "ONLINE" }));

  const ok = interpretNodeHealthResponse({ code: 0, data: summary });
  assert.equal(ok.ok, true);
  if (ok.ok) {
    assert.equal(ok.summary, summary);
  }

  assert.deepEqual(interpretNodeHealthResponse({ code: 0, data: { total: 3 } }), {
    ok: false,
    denied: false,
    message: "后端未返回 nodes 数组",
  });
  assert.deepEqual(interpretNodeHealthResponse({ code: 0, data: null }), {
    ok: false,
    denied: false,
    message: "后端未返回 nodes 数组",
  });
  assert.deepEqual(interpretNodeHealthResponse({ code: 500, msg: "服务异常" }), {
    ok: false,
    denied: false,
    message: "服务异常",
  });
  assert.deepEqual(interpretNodeHealthResponse(null), {
    ok: false,
    denied: false,
    message: "未知错误",
  });
});

test("401 是登录失效，403 才是无权限", () => {
  assert.deepEqual(interpretNodeHealthResponse({ code: 401 }), {
    ok: false,
    denied: false,
    sessionExpired: true,
    message: "登录已失效，请重新登录",
  });
  assert.deepEqual(interpretNodeHealthResponse({ code: 401, msg: "token 已失效" }), {
    ok: false,
    denied: false,
    sessionExpired: true,
    message: "token 已失效",
  });
  assert.deepEqual(interpretNodeHealthResponse({ code: 403 }), {
    ok: false,
    denied: true,
    message: "当前账号无节点健康查看权限",
  });
  assert.deepEqual(interpretNodeHealthResponse({ code: 403, msg: "禁止访问" }), {
    ok: false,
    denied: true,
    message: "禁止访问",
  });
});

test("迟到的 401 不能清新会话：只有失败时用的 token 仍是当前 token 才清", () => {
  assert.equal(shouldInvalidateSessionToken("t-new", "t-new"), true);
  // 旧请求迟到：它带的是已经作废的 token，与新登录无关
  assert.equal(shouldInvalidateSessionToken("t-old", "t-new"), false);
  assert.equal(shouldInvalidateSessionToken("t-old", null), false);
  assert.equal(shouldInvalidateSessionToken(null, "t-new"), false);
  assert.equal(shouldInvalidateSessionToken("", ""), false);
});

// ========== 只读刷新：定时器、并发、失败清理 ==========

const flushMicrotasks = async (): Promise<void> => {
  for (let i = 0; i < 8; i += 1) {
    await Promise.resolve();
  }
};

const okResult = (summary: NodeHealthSummary): NodeHealthReadResult => ({
  ok: true,
  summary,
});

const failResult = (
  message: string,
  denied = false,
): NodeHealthReadResult => ({ ok: false, denied, message });

interface FakeTimerTask {
  id: number;
  at: number;
  callback: () => void;
  interval?: number;
}

const createFakeTimers = () => {
  let clock = 0;
  let seq = 0;
  const tasks: FakeTimerTask[] = [];

  const timers: NodeHealthTimers = {
    setInterval: (callback, ms) => {
      seq += 1;
      tasks.push({ id: seq, at: clock + ms, callback, interval: ms });
      return seq;
    },
    clearInterval: (handle) => {
      const id = typeof handle === "number" ? handle : -1;
      const index = tasks.findIndex((task) => task.id === id);
      if (index >= 0) {
        tasks.splice(index, 1);
      }
    },
    setTimeout: (callback, ms) => {
      seq += 1;
      tasks.push({ id: seq, at: clock + ms, callback });
      return seq;
    },
    clearTimeout: (handle) => {
      const id = typeof handle === "number" ? handle : -1;
      const index = tasks.findIndex((task) => task.id === id);
      if (index >= 0) {
        tasks.splice(index, 1);
      }
    },
  };

  const advance = async (ms: number): Promise<void> => {
    const target = clock + ms;
    for (;;) {
      const due = tasks
        .filter((task) => task.at <= target)
        .sort((left, right) => left.at - right.at)[0];
      if (!due) {
        break;
      }
      clock = due.at;
      if (due.interval === undefined) {
        tasks.splice(tasks.indexOf(due), 1);
      } else {
        due.at = clock + due.interval;
      }
      due.callback();
      await flushMicrotasks();
    }
    clock = target;
    await flushMicrotasks();
  };

  return { timers, advance, pending: () => tasks.length };
};

test("读到摘要进入 ready：记录本机读取时刻与服务器时间基准", async () => {
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () =>
      okResult({
        ...summaryWithNodes(node({ nodeId: 1, status: "ONLINE", checkedAt: 500 })),
        generatedAt: 900,
      }),
    onChange: (snapshot) => snapshots.push(snapshot),
    now: () => 777,
    monotonicNow: () => 1_000,
  });

  assert.equal(await poller.refresh({ showLoading: true }), true);
  assert.deepEqual(
    snapshots.map((snapshot) => snapshot.state),
    ["loading", "ready"],
  );
  const ready = snapshots[1];
  assert.equal(ready.fetchedAt, 777);
  assert.deepEqual(ready.basis, {
    generatedAt: 900,
    receivedAtMonotonic: 1_000,
    roundTripMs: 0,
  });
  assert.equal(ready.version > snapshots[0].version, true);
  assert.equal(ready.summary?.nodes?.length, 1);
});

test("读取失败不沿用上一次在线：摘要清空、状态转 error", async () => {
  let call = 0;
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () =>
      call++ === 0
        ? okResult(summaryWithNodes(node({ nodeId: 1, status: "ONLINE" })))
        : failResult("网关错误"),
    onChange: (snapshot) => snapshots.push(snapshot),
  });

  await poller.refresh({ showLoading: true });
  assert.equal(await poller.refresh(), false);

  const last = snapshots[snapshots.length - 1];
  assert.equal(last.state, "error");
  assert.equal(last.summary, null);
  assert.equal(last.error, "网关错误");
  assert.equal(last.fetchedAt, null);
  assert.equal(last.basis, null);
});

test("read 抛异常不算成功，也不保留旧摘要", async () => {
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () => {
      throw new Error("网络中断");
    },
    onChange: (snapshot) => snapshots.push(snapshot),
  });

  assert.equal(await poller.refresh({ showLoading: true }), false);
  const last = snapshots[snapshots.length - 1];
  assert.equal(last.state, "error");
  assert.equal(last.error, "网络中断");
  assert.equal(last.summary, null);
});

test("访问被拒进入 denied，同样不带旧摘要", async () => {
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () => interpretNodeHealthResponse({ code: 403 }),
    onChange: (snapshot) => snapshots.push(snapshot),
  });

  assert.equal(await poller.refresh({ showLoading: true }), false);
  const last = snapshots[snapshots.length - 1];
  assert.equal(last.state, "denied");
  assert.equal(last.summary, null);
  assert.equal(last.error, "当前账号无节点健康查看权限");
});

test("登录失效（401）落进 error 并带重新登录提示，不冒充无权限", async () => {
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () => interpretNodeHealthResponse({ code: 401 }),
    onChange: (snapshot) => snapshots.push(snapshot),
  });

  assert.equal(await poller.refresh({ showLoading: true }), false);
  const last = snapshots[snapshots.length - 1];
  assert.equal(last.state, "error");
  assert.equal(last.summary, null);
  assert.equal(last.error, "登录已失效，请重新登录");
});

test("并发刷新复用同一条请求，不会打出两次只读调用", async () => {
  let reads = 0;
  let release: (result: NodeHealthReadResult) => void = () => {};
  const poller = createNodeHealthPoller({
    read: () => {
      reads += 1;
      return new Promise<NodeHealthReadResult>((resolve) => {
        release = resolve;
      });
    },
  });

  const first = poller.refresh({ showLoading: true });
  const second = poller.refresh({ showLoading: true });

  assert.equal(first, second);
  // read 在微任务里才真正发起：先让出一次事件循环再断言只发了一条
  await flushMicrotasks();
  assert.equal(reads, 1);

  release(okResult(summaryWithNodes()));
  assert.equal(await first, true);
  assert.equal(reads, 1);
});

test("定时器按间隔只读刷新，start 幂等，stop 之后不再读取", async () => {
  const fake = createFakeTimers();
  let reads = 0;
  const poller = createNodeHealthPoller({
    read: async () => {
      reads += 1;
      return okResult(summaryWithNodes());
    },
    intervalMs: 60_000,
    timers: fake.timers,
  });

  poller.start();
  poller.start();
  assert.equal(fake.pending(), 1);

  await fake.advance(60_000);
  assert.equal(reads, 1);

  await fake.advance(60_000);
  assert.equal(reads, 2);

  poller.stop();
  assert.equal(fake.pending(), 0);

  await fake.advance(180_000);
  assert.equal(reads, 2);
});

test("读取悬挂时看门狗取消真正的请求，并按失败处理", async () => {
  const fake = createFakeTimers();
  const snapshots: NodeHealthSnapshot[] = [];
  const observed: { signal: AbortSignal | null } = { signal: null };
  const poller = createNodeHealthPoller({
    read: (signal) => {
      observed.signal = signal;
      return new Promise<NodeHealthReadResult>(() => {});
    },
    onChange: (snapshot) => snapshots.push(snapshot),
    timeoutMs: NODE_HEALTH_READ_TIMEOUT_MS,
    timers: fake.timers,
  });

  const pending = poller.refresh({ showLoading: true });
  // 先让读请求真正发出去，再看门狗到点
  await flushMicrotasks();
  assert.ok(observed.signal);

  await fake.advance(NODE_HEALTH_READ_TIMEOUT_MS);

  assert.equal(await pending, false);
  assert.equal(observed.signal?.aborted, true);
  const last = snapshots[snapshots.length - 1];
  assert.equal(last.state, "error");
  assert.equal(last.summary, null);
  assert.match(last.error, /超时/);
  assert.equal(fake.pending(), 0);
});

test("到期由独立定时器触发一次渲染并重新验证，不必等下一次轮询", async () => {
  const fake = createFakeTimers();
  let monotonic = 0;
  let reads = 0;
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () => {
      reads += 1;
      return okResult({
        generatedAt: 1_000,
        freshnessMs: 60_000,
        nodes: [node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 })],
      });
    },
    monotonicNow: () => monotonic,
    timers: fake.timers,
    onChange: (snapshot) => snapshots.push(snapshot),
  });

  await poller.refresh({ showLoading: true });
  assert.equal(reads, 1);
  // 只剩到期定时器
  assert.equal(fake.pending(), 1);

  monotonic = 60_000;
  await fake.advance(60_000);
  assert.equal(reads, 1);
  monotonic = 60_001;
  await fake.advance(1);

  assert.equal(reads, 2);
  // 到期先换一次渲染：同一份摘要、新版本，界面才有机会按当前时刻折成未知
  assert.equal(snapshots[2].state, "ready");
  assert.equal(snapshots[2].summary, snapshots[1].summary);
  assert.ok(snapshots[2].version > snapshots[1].version);
  poller.stop();
});

test("多个节点依次过期，即使重新读取悬挂也继续排期", async () => {
  const fake = createFakeTimers();
  let clock = 0;
  let reads = 0;
  const snapshots: NodeHealthSnapshot[] = [];
  const summary: NodeHealthSummary = {
    generatedAt: 6_000, freshnessMs: 10_000,
    nodes: [node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 }),
      node({ nodeId: 2, status: "ONLINE", checkedAt: 6_000 })],
  };
  const poller = createNodeHealthPoller({
    read: () => ++reads === 1 ? Promise.resolve(okResult(summary)) : new Promise<NodeHealthReadResult>(() => {}),
    monotonicNow: () => clock,
    timeoutMs: 50_000,
    timers: fake.timers,
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  await poller.refresh();
  clock = 5_001;
  await fake.advance(5_001);
  assert.equal(reads, 2);
  assert.deepEqual(applyNodeHealthFreshness(summary.nodes, summary.freshnessMs, snapshots[1].basis, clock).map(nodeHealthStatusOf), ["UNKNOWN", "ONLINE"]);
  const version = snapshots[snapshots.length - 1].version;
  clock = 10_001;
  await fake.advance(5_000);
  assert.ok(snapshots[snapshots.length - 1].version > version);
  assert.deepEqual(applyNodeHealthFreshness(summary.nodes, summary.freshnessMs, snapshots[1].basis, clock).map(nodeHealthStatusOf), ["UNKNOWN", "UNKNOWN"]);
  poller.stop();
});

test("超过浏览器定时器上限时重新排期，不紧循环", async () => {
  const fake = createFakeTimers();
  let clock = 0;
  let reads = 0;
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: () => ++reads === 1 ? Promise.resolve(okResult({
      generatedAt: 1_000, freshnessMs: 2_147_000_100,
      nodes: [node({ nodeId: 1, status: "ONLINE", checkedAt: 1_000 })],
    })) : new Promise<NodeHealthReadResult>(() => {}),
    timeoutMs: 10_000,
    monotonicNow: () => clock,
    timers: fake.timers,
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  await poller.refresh();
  clock = 2_147_000_000;
  await fake.advance(clock);
  const version = snapshots[snapshots.length - 1].version;
  clock += 101;
  await fake.advance(101);
  assert.ok(snapshots[snapshots.length - 1].version > version);
  assert.equal(reads, 2);
  poller.stop();
});

test("唤醒同步清除旧在线，并作废唤醒前在途响应", async () => {
  const fake = createFakeTimers();
  const snapshots: NodeHealthSnapshot[] = [];
  let wake: (() => void) | undefined;
  let reads = 0;
  let releaseOld: (result: NodeHealthReadResult) => void = () => {};
  const poller = createNodeHealthPoller({
    read: () => {
      reads += 1;
      if (reads === 1) return Promise.resolve(okResult(summaryWithNodes(node({ nodeId: 1, status: "ONLINE" }))));
      if (reads === 2) return new Promise<NodeHealthReadResult>((resolve) => { releaseOld = resolve; });
      return new Promise<NodeHealthReadResult>(() => {});
    },
    monotonicNow: () => 0,
    timers: fake.timers,
    subscribeWake: (handler) => { wake = handler; return () => { wake = undefined; }; },
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  poller.start();
  await poller.refresh();
  const old = poller.refresh({ showLoading: false });
  await flushMicrotasks();
  wake?.();
  assert.equal(snapshots[snapshots.length - 1].state, "loading");
  assert.equal(snapshots[snapshots.length - 1].summary, null);
  releaseOld(okResult(summaryWithNodes(node({ nodeId: 1, status: "ONLINE" }))));
  await old;
  await flushMicrotasks();
  assert.equal(reads, 3);
  assert.equal(snapshots[snapshots.length - 1].summary, null);
  poller.stop();
});

test("pageshow 恢复页面触发唤醒并在退订时移除监听", () => {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  const doc = new EventTarget();
  Object.defineProperty(doc, "visibilityState", { value: "visible" });
  const win = new EventTarget();
  try {
    Object.defineProperty(globalThis, "document", { value: doc, configurable: true });
    Object.defineProperty(globalThis, "window", { value: win, configurable: true });
    let count = 0;
    const unsubscribe = subscribeNodeHealthWake(() => { count += 1; });
    win.dispatchEvent(new Event("pageshow"));
    assert.equal(count, 1);
    unsubscribe();
    win.dispatchEvent(new Event("pageshow"));
    assert.equal(count, 1);
  } finally {
    if (originalDocument) Object.defineProperty(globalThis, "document", originalDocument);
    else Reflect.deleteProperty(globalThis, "document");
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow);
    else Reflect.deleteProperty(globalThis, "window");
  }
});

test("普通 focus 新生成的摘要可维持在线，旧快照到期后不复活", async () => {
  const fake = createFakeTimers();
  let clock = 0;
  let generatedAt = 1_000;
  let wake: (() => void) | undefined;
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: async () => okResult({ generatedAt, freshnessMs: 25_000,
      nodes: [node({ nodeId: 87321, status: "ONLINE", checkedAt: 1_000 })] }),
    identityKey: () => "focus-history-no-credentials",
    monotonicNow: () => clock, timers: fake.timers,
    subscribeWake: (handler) => { wake = handler; return () => { wake = undefined; }; },
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  poller.start();
  await poller.refresh();
  clock = 1_000;
  generatedAt = 2_000;
  wake?.();
  assert.equal(snapshots[snapshots.length - 1].state, "loading");
  assert.equal(snapshots[snapshots.length - 1].summary, null);
  await poller.refresh();
  let latest = snapshots[snapshots.length - 1];
  assert.equal(nodeHealthStatusOf(latest.summary?.nodes?.[0]), "ONLINE");
  assert.equal(latest.summary?.nodes?.[0].localFreshUntilMonotonic, 25_000);
  clock = 25_001;
  await poller.refresh();
  latest = snapshots[snapshots.length - 1];
  assert.equal(nodeHealthStatusOf(latest.summary?.nodes?.[0]), "UNKNOWN");
  generatedAt = 27_000;
  await poller.refresh();
  latest = snapshots[snapshots.length - 1];
  assert.equal(nodeHealthStatusOf(latest.summary?.nodes?.[0]), "UNKNOWN");
  poller.stop();
});

test("重复缓存摘要及 A→B→A 轮询器重建不重置同一检测期限", async () => {
  const fake = createFakeTimers();
  let clock = 0;
  let identity = "history-test-identity-A";
  const snapshots: NodeHealthSnapshot[] = [];
  const summary: NodeHealthSummary = {
    generatedAt: 1_000,
    freshnessMs: 25_000,
    nodes: [node({ nodeId: 87231, status: "ONLINE", checkedAt: 1_000 })],
  };
  const create = () => createNodeHealthPoller({
    read: async () => okResult(summary),
    identityKey: () => identity,
    monotonicNow: () => clock,
    timers: fake.timers,
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  const a = create();
  await a.refresh();
  clock = 20_000;
  await a.refresh();
  const repeated = snapshots[snapshots.length - 1];
  assert.equal(repeated.summary?.nodes?.[0].localFreshUntilMonotonic, 25_000);
  assert.equal(nodeHealthStatusOf(applyNodeHealthFreshness(repeated.summary?.nodes, 25_000, repeated.basis, 25_001)[0]), "UNKNOWN");
  clock = 25_001;
  await a.refresh();
  assert.equal(nodeHealthStatusOf(snapshots[snapshots.length - 1].summary?.nodes?.[0]), "UNKNOWN");
  a.stop();
  identity = "history-test-identity-B";
  const b = create();
  await b.refresh();
  assert.equal(nodeHealthStatusOf(snapshots[snapshots.length - 1].summary?.nodes?.[0]), "ONLINE");
  b.stop();
  identity = "history-test-identity-A";
  const aAgain = create();
  await aAgain.refresh();
  assert.equal(nodeHealthStatusOf(snapshots[snapshots.length - 1].summary?.nodes?.[0]), "UNKNOWN");
  aAgain.stop();
});

test("不同节点期限互不覆盖，更新的检测可重新获得期限", async () => {
  const fake = createFakeTimers();
  let clock = 0;
  let summary: NodeHealthSummary = { generatedAt: 10_000, freshnessMs: 25_000,
    nodes: [node({ nodeId: 87241, status: "ONLINE", checkedAt: 1_000 }),
      node({ nodeId: 87242, status: "ONLINE", checkedAt: 10_000 })] };
  let last: NodeHealthSnapshot | null = null;
  const poller = createNodeHealthPoller({
    read: async () => okResult(summary), identityKey: () => "history-test-nodes",
    monotonicNow: () => clock, timers: fake.timers, onChange: (snapshot) => { last = snapshot; },
  });
  await poller.refresh();
  clock = 16_001;
  await poller.refresh();
  const nodes = (last as NodeHealthSnapshot | null)?.summary?.nodes ?? [];
  assert.equal(nodeHealthStatusOf(nodes[0]), "UNKNOWN");
  assert.equal(nodeHealthStatusOf(nodes[1]), "ONLINE");
  summary = { ...summary, generatedAt: 11_000,
    nodes: [node({ nodeId: 87241, status: "ONLINE", checkedAt: 11_000 })] };
  await poller.refresh();
  assert.equal(nodeHealthStatusOf((last as NodeHealthSnapshot | null)?.summary?.nodes?.[0]), "ONLINE");
  poller.stop();
});

test("单调钟回退时相同检测保持未知，不由重新读取复活", async () => {
  const fake = createFakeTimers();
  let clock = 100;
  let last: NodeHealthSnapshot | null = null;
  const poller = createNodeHealthPoller({
    read: async () => okResult({ generatedAt: 1_000, freshnessMs: 25_000,
      nodes: [node({ nodeId: 87251, status: "ONLINE", checkedAt: 1_000 })] }),
    identityKey: () => "history-test-clock", monotonicNow: () => clock, timers: fake.timers,
    onChange: (snapshot) => { last = snapshot; },
  });
  await poller.refresh();
  clock = 50;
  await poller.refresh();
  assert.equal(nodeHealthStatusOf((last as NodeHealthSnapshot | null)?.summary?.nodes?.[0]), "UNKNOWN");
  clock = 150;
  await poller.refresh();
  assert.equal(nodeHealthStatusOf((last as NodeHealthSnapshot | null)?.summary?.nodes?.[0]), "UNKNOWN");
  poller.stop();
});

test("回到前台立刻重新验证一次，卸载时退订", async () => {
  let wake: (() => void) | undefined;
  let unsubscribed = 0;
  let reads = 0;
  const poller = createNodeHealthPoller({
    read: async () => {
      reads += 1;
      return okResult(summaryWithNodes());
    },
    subscribeWake: (handler) => {
      wake = handler;

      return () => {
        unsubscribed += 1;
        wake = undefined;
      };
    },
  });

  poller.start();
  wake?.();
  await flushMicrotasks();
  assert.equal(reads, 1);

  poller.stop();
  assert.equal(unsubscribed, 1);
  wake?.();
  await flushMicrotasks();
  assert.equal(reads, 1);
});

test("卸载后连请求都不发：stop() 之后排队中的读取不再触发", async () => {
  let reads = 0;
  const poller = createNodeHealthPoller({
    read: async () => {
      reads += 1;
      return okResult(summaryWithNodes());
    },
  });

  const pending = poller.refresh({ showLoading: true });
  poller.stop();

  assert.equal(await pending, false);
  await flushMicrotasks();
  assert.equal(reads, 0);
});

test("在途响应迟到也不回写：stop() 之后拿到的结果按代次丢弃", async () => {
  const snapshots: NodeHealthSnapshot[] = [];
  let release: (result: NodeHealthReadResult) => void = () => {};
  const poller = createNodeHealthPoller({
    read: () =>
      new Promise<NodeHealthReadResult>((resolve) => {
        release = resolve;
      }),
    onChange: (snapshot) => snapshots.push(snapshot),
  });

  const pending = poller.refresh({ showLoading: true });
  await flushMicrotasks();
  poller.stop();
  release(okResult(summaryWithNodes(node({ nodeId: 1, status: "ONLINE" }))));

  assert.equal(await pending, false);
  await flushMicrotasks();
  assert.deepEqual(
    snapshots.map((snapshot) => snapshot.state),
    ["loading"],
  );
  assert.equal(snapshots[0].summary, null);
});

test("stop 立即清除看门狗，忽略取消的悬挂读取也结束等待", async () => {
  const fake = createFakeTimers();
  const snapshots: NodeHealthSnapshot[] = [];
  const poller = createNodeHealthPoller({
    read: () => new Promise<NodeHealthReadResult>(() => {}),
    timers: fake.timers,
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  poller.start();
  const pending = poller.refresh();
  await flushMicrotasks();
  assert.equal(fake.pending(), 2, "周期定时器和请求看门狗均已启动");
  poller.stop();
  assert.equal(fake.pending(), 0, "stop 必须同步清除两种定时器");
  assert.equal(await pending, false);
  assert.deepEqual(snapshots.map((snapshot) => snapshot.state), ["loading"]);
});

test("StrictMode 双次挂载：旧在途请求作废，只留一条定时器", async () => {
  const fake = createFakeTimers();
  let reads = 0;
  const poller = createNodeHealthPoller({
    read: async () => {
      reads += 1;
      return okResult(summaryWithNodes());
    },
    intervalMs: 60_000,
    timers: fake.timers,
  });

  poller.start();
  void poller.refresh({ showLoading: true });
  poller.stop();
  poller.start();

  assert.equal(fake.pending(), 1);
  await flushMicrotasks();
  assert.equal(reads, 0);

  await fake.advance(60_000);
  assert.equal(reads, 1);
  poller.stop();
});

test("stop 之后手动刷新仍然可用，不会被上一条在途请求卡住", async () => {
  let reads = 0;
  let release: (result: NodeHealthReadResult) => void = () => {};
  const poller = createNodeHealthPoller({
    read: () => {
      reads += 1;
      if (reads === 1) {
        return new Promise<NodeHealthReadResult>((resolve) => {
          release = resolve;
        });
      }
      return Promise.resolve(
        okResult(summaryWithNodes(node({ nodeId: 1, status: "ONLINE" }))),
      );
    },
  });

  const stuck = poller.refresh({ showLoading: true });
  await flushMicrotasks();
  poller.stop();

  assert.equal(await poller.refresh({ showLoading: true }), true);
  release(okResult(summaryWithNodes()));
  assert.equal(await stuck, false);
  assert.equal(reads, 2);
});

test("超过历史容量时不淘汰旧样本，超限项持续未知", async () => {
  // 独立模块实例隔离容量测试，避免污染其他轮询器的进程内历史。
  const isolatedModulePath = "./nodeHealthView.ts?history-capacity-test";
  const isolated = await import(isolatedModulePath);
  const fake = createFakeTimers();
  let clock = 0;
  const generated = 9_000_000_000_000;
  let summary: NodeHealthSummary = {
    generatedAt: generated,
    freshnessMs: 60_000,
    nodes: Array.from({ length: 4097 }, (_, index) =>
      node({ nodeId: 900_000 + index, status: "ONLINE", checkedAt: generated })),
  };
  let last: NodeHealthSnapshot | null = null;
  const poller: NodeHealthPoller = isolated.createNodeHealthPoller({
    read: async () => okResult(summary), identityKey: () => "history-capacity-test",
    monotonicNow: () => clock, timers: fake.timers,
    onChange: (snapshot: NodeHealthSnapshot) => { last = snapshot; },
  });
  const current = () => (last as NodeHealthSnapshot | null)?.summary?.nodes ?? [];
  await poller.refresh();
  assert.equal(current().filter((item) => nodeHealthStatusOf(item) === "ONLINE").length, 4096);
  assert.equal(nodeHealthStatusOf(current()[4096]), "UNKNOWN");
  assert.equal(current()[4096].localHistoryCapacityExceeded, true);
  assert.equal(current()[0].localHistoryCapacityExceeded, undefined);
  assert.match(current()[4096].staleNote ?? "", /容量已满/);
  await poller.refresh();
  assert.equal(current().filter((item) => nodeHealthStatusOf(item) === "ONLINE").length, 4096);
  assert.equal(nodeHealthStatusOf(current()[0]), "ONLINE");
  assert.equal(nodeHealthStatusOf(current()[4096]), "UNKNOWN");
  summary = { ...summary, generatedAt: generated + 1 };
  await poller.refresh();
  assert.equal(current().filter((item) => nodeHealthStatusOf(item) === "ONLINE").length, 4096);
  assert.equal(nodeHealthStatusOf(current()[4096]), "UNKNOWN", "仅生成时间前进并不会扩大容量");
  summary = { ...summary, generatedAt: generated + 2, nodes: summary.nodes?.slice(1) };
  await poller.refresh();
  assert.equal(current().length, 4096);
  assert.equal(current().filter((item) => nodeHealthStatusOf(item) === "ONLINE").length, 4095);
  assert.equal(nodeHealthStatusOf(current()[4095]), "UNKNOWN", "删除节点不会淘汰曾见历史");
  clock = 60_001;
  summary = { ...summary, generatedAt: generated + 3,
    nodes: [node({ nodeId: 900_000, status: "ONLINE", checkedAt: generated })] };
  await poller.refresh();
  assert.equal(nodeHealthStatusOf(current()[0]), "UNKNOWN", "旧样本的原到期时间仍被保留");
  poller.stop();
});
