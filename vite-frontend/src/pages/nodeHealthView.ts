/**
 * 节点健康视图的纯逻辑：状态归一、过期折成未知、计数。
 *
 * /api/v1/node/health/summary 里每行带 `status`（上次探测结论）与 `stale`（结论是否过期）。
 * status 是「上次结论」，stale=true 时它已经不代表此刻的连通性——必须按 UNKNOWN 展示并计数，
 * 否则过期结果会被当成当前在线。历史信息（检测时间、上次延迟、detail 原文）照常保留供查看。
 */

export type NodeHealthStatus = "ONLINE" | "OFFLINE" | "DEGRADED" | "UNKNOWN";

/** 展示顺序：在线 → 降级 → 离线 → 未知 */
export const NODE_STATUS_ORDER: readonly NodeHealthStatus[] = [
  "ONLINE",
  "DEGRADED",
  "OFFLINE",
  "UNKNOWN",
];

export interface NodeHealthNode {
  nodeId: number;
  nodeName: string;
  /** 上次探测结论（可能是过期结论，见 stale） */
  status: string;
  checkedAt?: number | null;
  checkedAtText?: string;
  /** 结论已过期：只作历史参考 */
  stale?: boolean;
  staleNote?: string | null;
  /** 当前延迟；过期时为 null */
  latencyMs?: number | null;
  /** 上次延迟：过期结论的历史值 */
  lastLatencyMs?: number | null;
  detail?: string;
}

export interface NodeHealthSummary {
  nodes?: NodeHealthNode[];
  stateHealthy?: boolean;
  total?: number;
  online?: number;
  offline?: number;
  degraded?: number;
  unknown?: number;
  /** 其中有多少条是过期结论（已计入 unknown） */
  staleCount?: number;
}

export interface NodeHealthCounts {
  total: number;
  online: number;
  degraded: number;
  offline: number;
  unknown: number;
  staleCount: number;
}

export const normalizeNodeStatus = (
  status?: string | null,
): NodeHealthStatus => {
  const value = (status || "").toUpperCase();

  return value === "ONLINE" || value === "OFFLINE" || value === "DEGRADED"
    ? value
    : "UNKNOWN";
};

/** 过期结论一律按 UNKNOWN：宁可说不知道，也不能把上次在线当此刻在线 */
export const nodeHealthStatusOf = (
  node?: Pick<NodeHealthNode, "status" | "stale"> | null,
): NodeHealthStatus =>
  node?.stale ? "UNKNOWN" : normalizeNodeStatus(node?.status);

const countOf = (value: number | undefined, fallback: number): number =>
  typeof value === "number" && Number.isFinite(value) ? value : fallback;

/** 按明细自算计数：stale 行落进 unknown，并单独记 staleCount 供界面提示「旧结果」 */
export const countNodeHealth = (
  nodes: readonly NodeHealthNode[] = [],
): NodeHealthCounts => {
  const counts: NodeHealthCounts = {
    total: 0,
    online: 0,
    degraded: 0,
    offline: 0,
    unknown: 0,
    staleCount: 0,
  };

  for (const node of nodes) {
    counts.total += 1;
    if (node?.stale) {
      counts.staleCount += 1;
    }
    const status = nodeHealthStatusOf(node);

    if (status === "ONLINE") {
      counts.online += 1;
    } else if (status === "DEGRADED") {
      counts.degraded += 1;
    } else if (status === "OFFLINE") {
      counts.offline += 1;
    } else {
      counts.unknown += 1;
    }
  }

  return counts;
};

/**
 * 统计与明细同源：只要有节点明细，就按明细重算（明细已经把 stale 折成 UNKNOWN），
 * 后端计数只在没有明细时兜底。两者不一致时以明细为准，杜绝「列表显示未知、计数却算在线」。
 */
export const resolveNodeHealthCounts = (
  summary: NodeHealthSummary | null | undefined,
  nodes: readonly NodeHealthNode[] = [],
): NodeHealthCounts => {
  if (nodes.length > 0 || Array.isArray(summary?.nodes)) {
    return countNodeHealth(nodes);
  }

  return {
    total: countOf(summary?.total, 0),
    online: countOf(summary?.online, 0),
    degraded: countOf(summary?.degraded, 0),
    offline: countOf(summary?.offline, 0),
    unknown: countOf(summary?.unknown, 0),
    staleCount: countOf(summary?.staleCount, 0),
  };
};
