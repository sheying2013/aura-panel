/**
 * 节点健康视图的纯逻辑：状态归一、过期折成未知、计数。
 *
 * /api/v1/node/health/summary 里每行带 `status`（上次探测结论）与 `stale`（结论是否过期）。
 * status 是「上次结论」，stale=true 时它已经不代表此刻的连通性——必须按 UNKNOWN 展示并计数，
 * 否则过期结果会被当成当前在线。历史信息（检测时间、上次延迟、detail 原文）照常保留供查看。
 *
 * 本文件也承载摘要的只读刷新逻辑（createNodeHealthPoller）：它只决定「多久再读一次后端摘要」，
 * 发出的仍然是同一个只读接口，不触发任何探测；读取失败/被拒/悬挂时立刻丢弃上次结论，
 * 绝不让旧的 ONLINE 顶着最新状态。有效期兜底用后端下发的 freshnessMs，不自己编 TTL；
 * 结论年龄只用服务器自己的时间差加上本机实测耗时，不拿本机 Date.now 减服务器 checkedAt。
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
  /** 本页首次观察该检测的本机单调到期时刻；不是服务器时间。 */
  localFreshUntilMonotonic?: number;
  /** 本页历史记录达到上限，无法保存该节点的检测期限。 */
  localHistoryCapacityExceeded?: boolean;
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
  /** 后端组装这份摘要的时刻（毫秒） */
  generatedAt?: number;
  /** 后端判定结论过期的有效期（毫秒）：前端只在两次刷新之间用它兜底 */
  freshnessMs?: number;
  /** 后端自动探测节奏（毫秒） */
  intervalMs?: number;
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

// ========== 时间基准：服务器时钟算年龄 + 本机单调耗时 ==========

/** 本机单调时钟：只用来数「收到摘要之后过了多久」，不参与跨机时间比较 */
export const nodeHealthMonotonicNow = (): number =>
  typeof performance !== "undefined" && typeof performance.now === "function"
    ? performance.now()
    : Date.now();

/**
 * 摘要的时间基准。
 *
 * <p>服务器 checkedAt 与本机 Date.now 没有共同基准（跨机偏差、时区、NTP 漂移都可能有），
 * 直接相减会把刚检测完的在线判成过期，也会把过期结论留成在线。年龄因此只用服务器自己的
 * 两个时刻相减（generatedAt - checkedAt），再加本机实测的请求往返耗时与收到之后的单调
 * 耗时：所有减法都在同一个时钟里做，跨时钟偏差根本不参与计算。
 *
 * <p>请求往返耗时整段计入年龄（不做折半）：只会让结论更早判过期，不会更晚。
 */
export interface NodeHealthTimeBasis {
  /** 后端组装这份摘要的服务器时刻（毫秒）；缺失表示没有可信时间基准 */
  generatedAt?: number | null;
  /** 收到响应的本机单调时刻（毫秒），见 nodeHealthMonotonicNow */
  receivedAtMonotonic?: number | null;
  /** 本次请求的本机往返耗时（毫秒）：响应在途耗时的保守上界 */
  roundTripMs?: number | null;
}

const isTimeNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);

/** 结论年龄（毫秒）；时间基准不可信时返回 null，绝不靠猜补一个数 */
export const nodeHealthAgeMs = (
  node?: Pick<NodeHealthNode, "checkedAt"> | null,
  basis?: NodeHealthTimeBasis | null,
  monotonicNowMs: number = nodeHealthMonotonicNow(),
): number | null => {
  const checkedAt = node?.checkedAt;
  const generatedAt = basis?.generatedAt;
  const receivedAt = basis?.receivedAtMonotonic;
  if (
    !isTimeNumber(checkedAt) ||
    !isTimeNumber(generatedAt) ||
    !isTimeNumber(receivedAt) ||
    !isTimeNumber(monotonicNowMs) ||
    monotonicNowMs < receivedAt
  ) {
    return null;
  }
  const serverAge = generatedAt - checkedAt;
  // 检测时刻晚于摘要生成时刻：服务器自己时钟回拨，年龄不可信
  if (serverAge < 0) {
    return null;
  }
  const roundTrip = basis?.roundTripMs;
  const inFlightMs = isTimeNumber(roundTrip) ? Math.max(0, roundTrip) : 0;
  const sinceReceived = monotonicNowMs - receivedAt;

  const age = serverAge + inFlightMs + sinceReceived;
  return Number.isFinite(age) ? age : null;
};

/** 后端有效期兜底时用的说明，与后端 staleNote 同义 */
const FRESHNESS_STALE_NOTE = "上次检测结论已过期，仅作历史参考，不计入在线";
/** 没有可信时间基准：历史结论无从确认 */
const NO_BASIS_STALE_NOTE = "缺少可信检测时间，无法确认当前状态，按未知展示";
/** 后端没给有效期：历史结论是否仍成立无法确认 */
const NO_FRESHNESS_STALE_NOTE = "后端未提供结论有效期，无法确认当前状态，按未知展示";

/** 折成过期：只动展示口径，不编造新数据（检测时间、detail 原文照旧保留） */
const markNodeStale = (node: NodeHealthNode, note: string): NodeHealthNode => ({
  ...node,
  status: "UNKNOWN",
  stale: true,
  staleNote: node.staleNote || note,
  latencyMs: null,
  lastLatencyMs:
    node.lastLatencyMs ??
    (typeof node.latencyMs === "number" ? node.latencyMs : null),
});

/**
 * 用后端下发的 freshnessMs 兜底：两次刷新之间结论越过有效期，就折成过期。
 *
 * <p>年龄来自 {@link nodeHealthAgeMs}，本机墙上时钟不参与判定。没有可信时间基准时，
 * 或缺少有效期时，在线、降级和离线结论均按未知展示。
 */
export const applyNodeHealthFreshness = (
  nodes: readonly NodeHealthNode[] = [],
  freshnessMs?: number | null,
  basis?: NodeHealthTimeBasis | null,
  monotonicNowMs: number = nodeHealthMonotonicNow(),
): readonly NodeHealthNode[] => {
  const hasFreshness = isTimeNumber(freshnessMs) && freshnessMs > 0;
  let changed = false;
  const guarded = nodes.map((node) => {
    if (!node || node.stale) {
      return node;
    }
    const age = nodeHealthAgeMs(node, basis, monotonicNowMs);
    if (age === null || !hasFreshness) {
      changed = true;

      return markNodeStale(
        node,
        age === null ? NO_BASIS_STALE_NOTE : NO_FRESHNESS_STALE_NOTE,
      );
    }
    // 既检查本次摘要，也检查同一检测此前观察到的最早本机到期时刻。
    const localDeadline = node.localFreshUntilMonotonic;
    if (age <= freshnessMs && (localDeadline === undefined ||
        (isTimeNumber(localDeadline) && monotonicNowMs <= localDeadline))) {
      return node;
    }
    changed = true;

    return markNodeStale(node, FRESHNESS_STALE_NOTE);
  });

  return changed ? guarded : nodes;
};

/**
 * 距最近一条仍按当前结论展示的节点过期的毫秒数；没有可判定的节点时返回 null。
 *
 * <p>页面据此单独设一个到期定时器：到点立刻重算并重新渲染，不必等下一次只读刷新。
 * 已经越过有效期的结论不再返回 0（否则 0ms 定时器会退化成紧循环）——它们此刻就该按未知展示。
 */
export const nextNodeHealthExpiryInMs = (
  nodes: readonly NodeHealthNode[] = [],
  freshnessMs?: number | null,
  basis?: NodeHealthTimeBasis | null,
  monotonicNowMs: number = nodeHealthMonotonicNow(),
): number | null => {
  if (!isTimeNumber(freshnessMs) || freshnessMs <= 0) {
    return null;
  }
  let next: number | null = null;
  for (const node of nodes) {
    if (!node || node.stale) {
      continue;
    }
    const age = nodeHealthAgeMs(node, basis, monotonicNowMs);
    if (age === null) {
      continue;
    }
    const localDeadline = node.localFreshUntilMonotonic;
    const remaining = Math.min(freshnessMs - age,
      localDeadline === undefined ? Infinity : localDeadline - monotonicNowMs);
    if (!Number.isFinite(remaining) || remaining < 0) {
      continue;
    }
    // 严格 age > TTL；向上取整并多等 1ms，包含恰好相等的边界。
    const delay = Math.ceil(remaining) + 1;
    if (next === null || delay < next) {
      next = delay;
    }
  }

  return next;
};

// ========== 请求身份：迟到的 401 不得清新会话 ==========

/**
 * 迟到的 401 能不能清掉本机会话：只有「失败时携带的 token 仍是当前 token」才可以。
 *
 * <p>旧请求（卸载前发出、重新登录后才回来）带的是已经作废的 token，它的 401 说明的是
 * 那次旧请求的凭据失效，与新登录无关，不能把新会话清掉。
 *
 * <p>放在这个纯逻辑模块里是为了能被 node 测试直接覆盖（api/network.ts 依赖 axios 与
 * window，纯模块测试加载不了它）；Network.postCancellable 复用同一条判定。
 */
export const shouldInvalidateSessionToken = (
  usedToken?: string | null,
  currentToken?: string | null,
): boolean =>
  typeof usedToken === "string" &&
  usedToken.length > 0 &&
  usedToken === currentToken;

// ========== 只读摘要的刷新（不触发探测） ==========

/** 页面持有的摘要状态：loading/error/denied 一律不携带旧结论 */
export type NodeHealthState = "idle" | "loading" | "ready" | "error" | "denied";

/** 只读摘要的后台刷新节奏：一分钟一次，不触发任何节点探测 */
export const NODE_HEALTH_REFRESH_INTERVAL_MS = 60_000;

/** 单次读取的看门狗：请求悬挂也按失败处理，避免旧在线一直留在页面上 */
export const NODE_HEALTH_READ_TIMEOUT_MS = 20_000;

/** setTimeout 的上限（毫秒）：到期时间超出这个范围时先在那个上限醒一次 */
const NODE_HEALTH_MAX_TIMER_MS = 2_147_000_000;

/** 后端接口的响应形状（只取本视图用到的字段，与 Network 的返回结构一致） */
export interface NodeHealthApiResponse<T = unknown> {
  code?: number;
  msg?: string;
  data?: T | null;
}

export type NodeHealthReadResult =
  | { ok: true; summary: NodeHealthSummary }
  | {
      ok: false;
      denied: boolean;
      message: string;
      /** 读取被取消（卸载/超时）：不得回写任何状态 */
      canceled?: boolean;
      /** 登录已失效（401）：与「无权限」分开，界面要提示重新登录 */
      sessionExpired?: boolean;
    };

/**
 * 前台唤醒订阅（切回标签页 / 窗口重新聚焦）。
 *
 * <p>后台标签页的定时器会被浏览器压低，到期定时器可能迟迟不醒；回到前台立刻补一次
 * 重新验证，别让旧结论顶在页面上。返回退订函数，卸载时必须调用。
 */
export const subscribeNodeHealthWake = (handler: () => void): (() => void) => {
  if (typeof document === "undefined" || typeof window === "undefined") {
    return () => {};
  }
  const onWake = () => {
    if (document.visibilityState === "visible") {
      handler();
    }
  };
  document.addEventListener("visibilitychange", onWake);
  window.addEventListener("focus", onWake);
  window.addEventListener("pageshow", onWake);

  return () => {
    document.removeEventListener("visibilitychange", onWake);
    window.removeEventListener("focus", onWake);
    window.removeEventListener("pageshow", onWake);
  };
};

/** 把响应翻译成「拿到摘要 / 被拒 / 登录失效 / 读取失败」，不在这里保留上一次结果 */
export const interpretNodeHealthResponse = (
  res?: NodeHealthApiResponse<NodeHealthSummary | null> | null,
): NodeHealthReadResult => {
  if (res && res.code === 0) {
    const summary = res.data;
    if (!summary || !Array.isArray(summary.nodes)) {
      return { ok: false, denied: false, message: "后端未返回 nodes 数组" };
    }
    return { ok: true, summary };
  }
  if (res && res.code === 401) {
    return {
      ok: false,
      denied: false,
      sessionExpired: true,
      message: res.msg || "登录已失效，请重新登录",
    };
  }
  if (res && res.code === 403) {
    return {
      ok: false,
      denied: true,
      message: res.msg || "当前账号无节点健康查看权限",
    };
  }
  return { ok: false, denied: false, message: res?.msg || "未知错误" };
};

export interface NodeHealthSnapshot {
  state: NodeHealthState;
  summary: NodeHealthSummary | null;
  error: string;
  /** 最近一次成功读取的本机时刻；失败/被拒时为 null */
  fetchedAt: number | null;
  /** 服务器时钟与本机单调时钟的时间基准；没拿到可信摘要时为 null */
  basis: NodeHealthTimeBasis | null;
  /** 每次上报递增：同一份摘要因「到期」重新渲染时也换得掉界面 */
  version: number;
}

export interface NodeHealthTimers {
  setInterval: (callback: () => void, ms: number) => unknown;
  clearInterval: (handle: unknown) => void;
  setTimeout: (callback: () => void, ms: number) => unknown;
  clearTimeout: (handle: unknown) => void;
}

const DEFAULT_NODE_HEALTH_TIMERS: NodeHealthTimers = {
  setInterval: (callback, ms) => globalThis.setInterval(callback, ms),
  clearInterval: (handle) => globalThis.clearInterval(handle as number),
  setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
};

// 本页生命周期内只保存非凭据会话标识与节点时间；已有历史不淘汰。
// 容量不足时新条目保持未知，不因丢失旧历史而让旧检测复活。
const NODE_HEALTH_HISTORY_LIMIT = 4096;
type NodeHealthHistory = { identity: string; checkedAt: number; generatedAt: number; deadline: number; lastNow: number; invalid: boolean };
const nodeHealthHistory = new Map<string, NodeHealthHistory>();
let noBrowserScope = 0;

const readBrowserHealthIdentity = (): string | null => {
  try {
    if (typeof window === "undefined") return null;
    const store = window.localStorage;
    const token = store.getItem("token");
    if (!token || token.length > 4096) return null;
    // JWT payload 中的公开 sub/iat/exp/ver 是非凭据标识，仅用于隔离缓存，绝不用作鉴权。
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/"))) as Record<string, unknown>;
    const subject = String(payload.sub ?? "");
    if (!/^[0-9]{1,24}$/.test(subject) || !Number.isSafeInteger(payload.iat) ||
        !Number.isSafeInteger(payload.exp) || (payload.ver != null && !Number.isSafeInteger(payload.ver))) return null;
    const admin = store.getItem("admin");
    const role = store.getItem("role_id");
    if ((admin?.length ?? 0) > 32 || (role?.length ?? 0) > 32) return null;
    return JSON.stringify([subject, payload.iat, payload.exp, payload.ver ?? 0, admin, role]);
  } catch {
    return null;
  }
};

const rememberNodeHealthDeadlines = (
  summary: NodeHealthSummary, basis: NodeHealthTimeBasis, now: number, identity: string | null,
): NodeHealthSummary => {
  if (!Array.isArray(summary.nodes)) return summary;
  const nodes = summary.nodes.map((node) => {
    const checkedAt = node.checkedAt;
    const generatedAt = basis.generatedAt;
    const age = nodeHealthAgeMs(node, basis, now);
    const ttl = summary.freshnessMs;
    const key = identity !== null && isTimeNumber(node.nodeId) ? JSON.stringify([identity, node.nodeId]) : null;
    const previous = key === null ? undefined : nodeHealthHistory.get(key);
    if (key === null || identity === null || !isTimeNumber(checkedAt) ||
        !isTimeNumber(generatedAt) || age === null || !isTimeNumber(ttl) || ttl <= 0) {
      if (previous) previous.invalid = true;
      else if (key !== null && identity !== null && isTimeNumber(checkedAt) &&
          isTimeNumber(generatedAt) && isTimeNumber(now) && nodeHealthHistory.size < NODE_HEALTH_HISTORY_LIMIT) {
        nodeHealthHistory.set(key, { identity, checkedAt, generatedAt, deadline: now, lastNow: now, invalid: true });
      }
      return markNodeStale(node, "无法证明检测的新鲜度，按未知展示");
    }
    if (!previous && nodeHealthHistory.size >= NODE_HEALTH_HISTORY_LIMIT) {
      return { ...markNodeStale(node, "节点健康历史容量已满，此项按未知展示；页面重载后重新观察"),
        localHistoryCapacityExceeded: true };
    }
    if (previous && (now < previous.lastNow || generatedAt < previous.generatedAt || checkedAt < previous.checkedAt)) {
      previous.invalid = true;
      return markNodeStale(node, "检测时间或本机计时发生回退，按未知展示");
    }
    const deadline = now + ttl - age;
    if (!Number.isFinite(deadline)) return markNodeStale(node, "无法证明检测的新鲜度，按未知展示");
    const sameSample = previous && checkedAt === previous.checkedAt;
    const record: NodeHealthHistory = {
      identity, checkedAt, generatedAt, lastNow: now,
      deadline: previous && sameSample ? Math.min(previous.deadline, deadline) : deadline,
      invalid: Boolean(node.stale || (previous && sameSample && previous.invalid)),
    };
    nodeHealthHistory.set(key, record);
    if (record.invalid || now > record.deadline) {
      return markNodeStale(node, FRESHNESS_STALE_NOTE);
    }
    return { ...node, localFreshUntilMonotonic: record.deadline };
  });
  return { ...summary, nodes };
};

export interface NodeHealthPollerOptions {
  /** 只读读取；signal 取消时应立即放弃（Network.postCancellable 会真正中断请求） */
  read: (signal: AbortSignal) => Promise<NodeHealthReadResult>;
  onChange?: (snapshot: NodeHealthSnapshot) => void;
  intervalMs?: number;
  timeoutMs?: number;
  /** 本机墙上时钟：只用来记录 fetchedAt，不参与过期判定 */
  now?: () => number;
  /** 本机单调时钟：用来数「收到摘要之后过了多久」 */
  monotonicNow?: () => number;
  /** 非凭据身份隔离键；默认使用 JWT 公开声明和角色标记，不保存原始 token。 */
  identityKey?: () => string | null;
  /** 前台唤醒订阅（visibilitychange/focus）；返回退订函数 */
  subscribeWake?: (handler: () => void) => () => void;
  /** 测试注入的定时器；默认用全局 setInterval / setTimeout */
  timers?: Partial<NodeHealthTimers>;
}

export interface NodeHealthPoller {
  /** 立即读取一次；同一时刻只发一条请求，返回本次是否读到有效摘要 */
  refresh: (options?: { showLoading?: boolean }) => Promise<boolean>;
  /** 启动周期刷新（重复调用无副作用） */
  start: () => void;
  /** 停止周期刷新、作废在途请求并清理定时器（卸载时调用） */
  stop: () => void;
}

/**
 * 只读摘要轮询器。
 *
 * <p>硬约束：只调用外部传入的 read（后端只读摘要接口），自身不发任何探测；
 * 定时器可清理，卸载后不再发新请求、也不再回写；失败/被拒/超时一律清空摘要，不保留旧的
 * ONLINE；同一时刻只允许一条读取，定时刷新与手工刷新重叠时复用同一次请求。
 */
export const createNodeHealthPoller = (
  options: NodeHealthPollerOptions,
): NodeHealthPoller => {
  const {
    read,
    onChange,
    intervalMs = NODE_HEALTH_REFRESH_INTERVAL_MS,
    timeoutMs = NODE_HEALTH_READ_TIMEOUT_MS,
    now = Date.now,
    monotonicNow = nodeHealthMonotonicNow,
    identityKey,
    subscribeWake,
    timers,
  } = options;
  const timer: NodeHealthTimers = { ...DEFAULT_NODE_HEALTH_TIMERS, ...timers };
  const isolatedScope = `node-test-scope-${++noBrowserScope}`;
  const currentIdentity = () => {
    try {
      const key = identityKey ? identityKey() :
        typeof window === "undefined" ? isolatedScope : readBrowserHealthIdentity();
      return typeof key === "string" && key.length <= 8192 ? key : null;
    } catch { return null; }
  };

  let snapshot: NodeHealthSnapshot = {
    state: "idle",
    summary: null,
    error: "",
    fetchedAt: null,
    basis: null,
    version: 0,
  };
  let inFlight: Promise<boolean> | null = null;
  let snapshotIdentity: string | null = null;
  let intervalHandle: unknown = null;
  let expiryHandle: unknown = null;
  let unsubscribeWake: (() => void) | null = null;
  let activeAbort: AbortController | null = null;
  let generation = 0;

  const emit = (next: NodeHealthSnapshot) => {
    snapshot = next;
    onChange?.(next);
  };

  const readOnce = async (): Promise<NodeHealthReadResult> => {
    const controller = new AbortController();
    activeAbort = controller;
    let timeoutHandle: unknown = null;
    let onAbort: () => void = () => {};
    const canceled = new Promise<NodeHealthReadResult>((resolve) => {
      onAbort = () => {
        // stop/wake 时同步撤销看门狗，即使 read 忽略取消也结束本轮等待。
        if (timeoutHandle !== null) {
          timer.clearTimeout(timeoutHandle);
          timeoutHandle = null;
        }
        resolve({ ok: false, denied: false, canceled: true, message: "读取已取消" });
      };
      controller.signal.addEventListener("abort", onAbort, { once: true });
    });
    const timeout = new Promise<NodeHealthReadResult>((resolve) => {
      timeoutHandle = timer.setTimeout(() => {
        timeoutHandle = null;
        // 先确定超时结果，再取消底层读取，避免取消结果覆盖超时错误。
        resolve({
          ok: false,
          denied: false,
          message: "读取超时，未拿到最新摘要",
        });
        controller.abort();
      }, timeoutMs);
    });
    const pending = (async () => {
      // 让 stop()/卸载 有机会插到真正发请求之前：已作废就绝不发出新请求
      await Promise.resolve();
      if (controller.signal.aborted) {
        return {
          ok: false,
          denied: false,
          canceled: true,
          message: "读取已取消",
        } as NodeHealthReadResult;
      }

      return read(controller.signal);
    })().then(
      (result) => result,
      (error: unknown): NodeHealthReadResult => ({
        ok: false,
        denied: false,
        message: error instanceof Error ? error.message : "网络请求失败",
      }),
    );
    try {
      return await Promise.race([pending, timeout, canceled]);
    } finally {
      controller.signal.removeEventListener("abort", onAbort);
      if (timeoutHandle !== null) {
        timer.clearTimeout(timeoutHandle);
      }
      if (activeAbort === controller) {
        activeAbort = null;
      }
    }
  };

  const refresh = (
    refreshOptions: { showLoading?: boolean } = {},
  ): Promise<boolean> => {
    // 同一时刻只允许一条只读请求：定时刷新与手工刷新重叠时复用同一次读取
    if (inFlight) {
      return inFlight;
    }
    const requestGeneration = generation;
    const requestIdentity = currentIdentity();
    const startedMonotonic = monotonicNow();
    if (refreshOptions.showLoading ?? (snapshot.state !== "ready")) {
      emit({
        ...snapshot,
        state: "loading",
        summary: null,
        error: "",
        fetchedAt: null,
        basis: null,
        version: snapshot.version + 1,
      });
    }

    const promise = readOnce().then((result) => {
      // 已停止/已卸载：在途结果作废，绝不回写
      if (requestGeneration !== generation) {
        return false;
      }
      // 被取消的读取本身不是失败，不改变界面状态
      if (result.ok === false && result.canceled) {
        return false;
      }
      if (result.ok) {
        const receivedMonotonic = monotonicNow();
        if (requestIdentity !== currentIdentity()) {
          emit({ state: "error", summary: null, error: "身份已变化，请重新读取",
            fetchedAt: null, basis: null, version: snapshot.version + 1 });
          scheduleExpiry();
          return false;
        }
        const clockValid = isTimeNumber(startedMonotonic) && isTimeNumber(receivedMonotonic) && receivedMonotonic >= startedMonotonic;
        if (!clockValid) {
          for (const entry of nodeHealthHistory.values()) {
            if (entry.identity === requestIdentity) entry.invalid = true;
          }
        }
        const basis: NodeHealthTimeBasis = {
          generatedAt: result.summary.generatedAt ?? null,
          receivedAtMonotonic: clockValid ? receivedMonotonic : null,
          roundTripMs: Math.max(0, receivedMonotonic - startedMonotonic),
        };
        const summary = rememberNodeHealthDeadlines(result.summary, basis, receivedMonotonic, requestIdentity);
        snapshotIdentity = requestIdentity;
        emit({
          state: "ready",
          summary,
          error: "",
          fetchedAt: now(),
          basis,
          version: snapshot.version + 1,
        });
        scheduleExpiry();

        return true;
      }
      // 失败、被拒、登录失效、空数据：立刻清空上一次摘要，不保留旧在线
      emit({
        state: result.denied ? "denied" : "error",
        summary: null,
        error: result.message,
        fetchedAt: null,
        basis: null,
        version: snapshot.version + 1,
      });
      scheduleExpiry();

      return false;
    });

    inFlight = promise;
    const clearInFlight = () => {
      if (inFlight === promise) {
        inFlight = null;
      }
    };
    void promise.then(clearInFlight, clearInFlight);

    return promise;
  };

  /**
   * 单独排一个到期定时器：有效期一到就按当前时刻重算并渲染，不必等下一次只读刷新
   * （后台标签页的定时器会被浏览器压低，回到前台由 subscribeWake 立刻补一次重新验证）。
   */
  const scheduleExpiry = () => {
    if (expiryHandle !== null) {
      timer.clearTimeout(expiryHandle);
      expiryHandle = null;
    }
    if (snapshot.state !== "ready" || !snapshot.summary) {
      return;
    }
    const due = nextNodeHealthExpiryInMs(
      snapshot.summary.nodes ?? [],
      snapshot.summary.freshnessMs,
      snapshot.basis,
      monotonicNow(),
    );
    if (due === null) {
      return;
    }
    expiryHandle = timer.setTimeout(() => {
      expiryHandle = null;
      // 记录本地过期事实；单调钟之后回退也不可让相同检测重新变新鲜。
      const observedNow = monotonicNow();
      if (snapshotIdentity !== null && snapshotIdentity === currentIdentity()) {
        for (const node of snapshot.summary?.nodes ?? []) {
          const record = nodeHealthHistory.get(JSON.stringify([snapshotIdentity, node.nodeId]));
          if (!record || record.checkedAt !== node.checkedAt) continue;
          if (!isTimeNumber(observedNow) || observedNow < record.lastNow || observedNow > record.deadline) record.invalid = true;
          if (isTimeNumber(observedNow)) record.lastNow = Math.max(record.lastNow, observedNow);
        }
      }
      // 到期本身就该变一次界面：先用同一份摘要换新版本触发渲染，再去后端要一次新结论
      emit({ ...snapshot, version: snapshot.version + 1 });
      // 有多个到期节点、或等待时间超过浏览器上限时，必须继续排后续定时器。
      scheduleExpiry();
      void refresh({ showLoading: false });
    }, Math.min(due, NODE_HEALTH_MAX_TIMER_MS));
  };

  const start = () => {
    if (intervalHandle !== null) {
      return;
    }
    intervalHandle = timer.setInterval(() => {
      void refresh({ showLoading: false });
    }, intervalMs);
    // 前台唤醒（切回标签页/窗口重新聚焦）立刻重新验证，别让旧结论顶着
    unsubscribeWake =
      subscribeWake?.(() => {
        // 立即丢弃可见摘要并重新读取；历史期限仍保留，不把普通聚焦当成检测失效。
        generation += 1;
        activeAbort?.abort();
        activeAbort = null;
        inFlight = null;
        if (expiryHandle !== null) {
          timer.clearTimeout(expiryHandle);
          expiryHandle = null;
        }
        void refresh({ showLoading: true });
      }) ?? null;
    scheduleExpiry();
  };

  const stop = () => {
    generation += 1;
    // 取消真正在跑的请求，并断开复用：重新挂载时不会被上一条卡在 loading
    activeAbort?.abort();
    activeAbort = null;
    inFlight = null;
    if (intervalHandle !== null) {
      timer.clearInterval(intervalHandle);
      intervalHandle = null;
    }
    if (expiryHandle !== null) {
      timer.clearTimeout(expiryHandle);
      expiryHandle = null;
    }
    unsubscribeWake?.();
    unsubscribeWake = null;
  };

  return { refresh, start, stop };
};
