/**
 * 纯逻辑用例：节点健康状态归一、过期结论折成 UNKNOWN、计数与明细同源。
 * 运行：node --test --experimental-strip-types src/pages/nodeHealthView.test.ts
 */
import type { NodeHealthNode, NodeHealthSummary } from "./nodeHealthView.ts";

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  NODE_STATUS_ORDER,
  countNodeHealth,
  nodeHealthStatusOf,
  normalizeNodeStatus,
  resolveNodeHealthCounts,
} from "./nodeHealthView.ts";

const node = (
  overrides: Partial<NodeHealthNode> &
    Pick<NodeHealthNode, "nodeId" | "status">,
): NodeHealthNode => ({
  nodeName: `node-${overrides.nodeId}`,
  ...overrides,
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
    node({
      nodeId: 2,
      status: "ONLINE",
      stale: true,
    } satisfies Partial<NodeHealthNode>),
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