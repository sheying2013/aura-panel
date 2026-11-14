import { Button } from "@heroui/button";
import { Modal, ModalContent, ModalHeader, ModalBody } from "@heroui/modal";
import { useRef, useState, useEffect } from "react";
import toast from 'react-hot-toast';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

import Network from '@/api/network';

import {
  NODE_STATUS_ORDER,
  applyNodeHealthFreshness,
  createNodeHealthPoller,
  interpretNodeHealthResponse,
  nodeHealthStatusOf,
  resolveNodeHealthCounts,
  subscribeNodeHealthWake,
} from './nodeHealthView';
import type {
  NodeHealthNode,
  NodeHealthPoller,
  NodeHealthSnapshot,
  NodeHealthState,
  NodeHealthStatus,
  NodeHealthSummary,
} from './nodeHealthView';

interface UserInfo {
  flow: number;
  inFlow: number;
  outFlow: number;
  num: number;
  expTime?: string;
  flowResetTime?: number;
}

interface UserTunnel {
  id: number;
  tunnelId: number;
  tunnelName: string;
  tunnelFlow: number;
  flow: number;
  inFlow: number;
  outFlow: number;
  num: number;
  expTime?: string;
  flowResetTime?: number;
  speedLimitName?: string;
}

interface Forward {
  id: number;
  userId: number;
  userName: string;
  name: string;
  tunnelId: number;
  tunnelName: string;
  inPort: number;
  remoteAddr: string;
  inFlow: number;
  outFlow: number;
  status: number;
  pauseReason?: string;
  inIp: string;
}

interface StatisticsFlow {
  time: string;
  flow: number;
}

interface AddressItem {
  id: number;
  ip: string;
  address: string;
  copying?: boolean;
}

// 节点健康状态/计数口径统一在 ./nodeHealthView：过期结论一律按 UNKNOWN 展示与计数
const NODE_STATUS_META: Record<NodeHealthStatus, { label: string; tile: string; dot: string; text: string }> = {
  ONLINE: { label: '在线', tile: 'bg-[#a3b89a]/70 border-[#a3b89a]/80 hover:border-[#c8b89a]', dot: 'bg-[#a3b89a]', text: 'text-[#a3b89a]' },
  DEGRADED: { label: '降级', tile: 'bg-[#e0b84f]/60 border-[#e0b84f]/70 hover:border-[#c8b89a]', dot: 'bg-[#e0b84f]', text: 'text-[#e0b84f]' },
  OFFLINE: { label: '离线', tile: 'bg-[#c89a9a]/40 border-[#c89a9a]/50 hover:border-[#c8b89a]', dot: 'bg-[#c89a9a]', text: 'text-[#c89a9a]' },
  UNKNOWN: { label: '未知', tile: 'bg-white/[0.04] border-dashed border-white/20 hover:border-[#c8b89a]/60', dot: 'bg-white/30', text: 'text-[#8a857c]' },
};

/** 历史容量不足时直接显示说明，不依赖悬停或颜色。 */
export const NodeHealthCapacityNotice = ({ nodes }: { nodes: readonly NodeHealthNode[] }) => {
  const count = nodes.filter((node) => node.localHistoryCapacityExceeded === true).length;
  if (count === 0) return null;
  return (
    <p role="status" tabIndex={0} className="mt-2 rounded border border-[#e0b84f]/40 bg-[#e0b84f]/10 p-3 text-sm text-[#f5dfa5]">
      节点健康历史容量已满，{count} 个节点状态按未知显示。页面重载会清空本页历史，首次收到的旧缓存可能无法识别；重载后若节点仍超过容量，部分节点仍将显示未知。
    </p>
  );
};

// ========== 身份快照：登录/退出可能发生在别的标签页 ==========
// 只做身份核对，不猜服务端状态：浏览器侧能观察到的只有 localStorage 里的 token / admin / role_id。

/** 浏览器侧能看到的身份三件套 */
export interface IdentitySnapshot {
  token: string | null;
  /** 登录时服务端写入的兼容标记；老会话可能没有这个字段 */
  admin: string | null;
  roleId: string | null;
}

/** 身份指纹：token 与 admin/role_id 任一变化都算换了账号 */
export const identityKeyOf = (identity: IdentitySnapshot): string =>
  JSON.stringify([identity.token, identity.admin, identity.roleId]);

/** 读出当前身份；写入方与登录页、管理端布局保持一致 */
export const readIdentitySnapshot = (storage: Pick<Storage, 'getItem'>): IdentitySnapshot => ({
  token: storage.getItem('token'),
  admin: storage.getItem('admin'),
  roleId: storage.getItem('role_id'),
});

/** 管理员必须有 token；任一角色标记明确非管理员或两者冲突时保守拒绝。 */
export const isAdminIdentity = (identity: IdentitySnapshot): boolean => {
  if (!identity.token?.trim()) return false;
  if (identity.admin !== null && identity.admin !== 'true') return false;
  if (identity.roleId !== null && identity.roleId !== '0') return false;
  return identity.admin === 'true' || identity.roleId === '0';
};

/** storage 事件里只有这三个键会改变身份（key 为 null 表示整体被 clear） */
export const isIdentityStorageKey = (key: string | null): boolean =>
  key === null || key === 'token' || key === 'admin' || key === 'role_id';

export interface IdentityWatchOptions {
  read: () => IdentitySnapshot;
  onChange: (nextKey: string, previousKey: string) => void;
  /** 订阅时已知的指纹：只有真的变了才回调 */
  initialKey?: string;
  win?: Pick<Window, 'addEventListener' | 'removeEventListener'>;
  doc?: Pick<Document, 'addEventListener' | 'removeEventListener' | 'visibilityState'>;
}

/**
 * 身份变化订阅。
 *
 * <p>跨标签页：storage 事件（别的标签页登录/退出只改 localStorage，不会重挂载本页）。
 * <p>页内恢复：从 bfcache 返回时 storage 补不到，pageshow / visibilitychange 时再主动核对一次。
 * <p>同标签页的登录/退出本身就会让路由重挂载本页，不需要额外事件；同 token 重新登录
 * （服务端换了账号但 token 没变）浏览器侧没有任何信号，这里检测不到，只能靠请求失败兜底。
 *
 * <p>返回退订函数，卸载时必须调用。
 */
export const watchIdentity = (options: IdentityWatchOptions): (() => void) => {
  const { read, onChange, initialKey } = options;
  const win = options.win ?? (typeof window === 'undefined' ? null : window);
  const doc = options.doc ?? (typeof document === 'undefined' ? null : document);

  if (!win || !doc) return () => {};

  let lastKey = initialKey ?? identityKeyOf(read());

  const sync = () => {
    const nextKey = identityKeyOf(read());

    if (nextKey === lastKey) return;
    const previousKey = lastKey;

    lastKey = nextKey;
    onChange(nextKey, previousKey);
  };

  const onStorage = (event: StorageEvent) => {
    if (isIdentityStorageKey(event.key)) sync();
  };
  // 只有回到前台才核对，标签页隐藏期间不动
  const onVisibility = () => {
    if (doc.visibilityState === 'visible') sync();
  };

  win.addEventListener('storage', onStorage);
  win.addEventListener('pageshow', sync);
  doc.addEventListener('visibilitychange', onVisibility);
  // 补上 render 与监听安装之间发生的身份变更。
  sync();

  return () => {
    win.removeEventListener('storage', onStorage);
    win.removeEventListener('pageshow', sync);
    doc.removeEventListener('visibilitychange', onVisibility);
  };
};

/** 换身份：旧账号的摘要与时间基准一起作废，绝不留在线结论 */
export const clearNodeHealthSnapshot = (prev: NodeHealthSnapshot): NodeHealthSnapshot => ({
  state: 'idle',
  summary: null,
  error: '',
  fetchedAt: null,
  basis: null,
  version: prev.version + 1,
});

/** 非管理员：不发管理员摘要请求，只留拒绝态 */
export const denyNodeHealthSnapshot = (prev: NodeHealthSnapshot): NodeHealthSnapshot => ({
  ...clearNodeHealthSnapshot(prev),
  state: 'denied',
});

export default function DashboardPage() {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [userInfo, setUserInfo] = useState<UserInfo>({} as UserInfo);
  const [userTunnels, setUserTunnels] = useState<UserTunnel[]>([]);
  const [forwardList, setForwardList] = useState<Forward[]>([]);
  const [statisticsFlows, setStatisticsFlows] = useState<StatisticsFlow[]>([]);
  
  const [addressModalOpen, setAddressModalOpen] = useState(false);
  const [addressModalTitle, setAddressModalTitle] = useState('');
  const [addressList, setAddressList] = useState<AddressItem[]>([]);

  // 节点健康摘要：/api/v1/node/health/summary 带 @RequireRole，仅 role_id=0 的管理员可读，非管理员不发起请求
  // 一份快照就是一份真相：状态、摘要、报错、时间基准、版本一起换；
  // 到期定时器只递增版本，界面也会跟着按当前时刻重算（不必等下一次读取）
  const [nodeHealthSnapshot, setNodeHealthSnapshot] = useState<NodeHealthSnapshot>({
    state: 'idle',
    summary: null,
    error: '',
    fetchedAt: null,
    basis: null,
    version: 0,
  });
  const nodeHealth = nodeHealthSnapshot.summary;
  const nodeHealthState: NodeHealthState = nodeHealthSnapshot.state;
  const nodeHealthError = nodeHealthSnapshot.error;
  // 只读摘要轮询器：跨渲染复用同一个实例，卸载时停表
  const nodeHealthPollerRef = useRef<NodeHealthPoller | null>(null);
  // 页面生命周期内的取消令牌：卸载（含 StrictMode 重挂载）时中断在途的 /user/package
  const packageAbortRef = useRef<AbortController | null>(null);
  // 请求代次：多次刷新重叠时，只有最新一次响应可以回写界面
  const packageRequestSeqRef = useRef(0);
  // 手工刷新代次：被取代的刷新不再提示、不再改 refreshing
  const refreshSeqRef = useRef(0);
  // 身份指纹：另一标签页登录/退出只改 localStorage，这里一变就整段重装
  const [identityKey, setIdentityKey] = useState(() => identityKeyOf(readIdentitySnapshot(localStorage)));
  // 即使同一批次先后切换回原身份，也要重建已停止的请求作用域。
  const [identityScopeRevision, setIdentityScopeRevision] = useState(0);

  // 检查有效期通知
  const checkExpirationNotifications = (info: UserInfo, tunnels: UserTunnel[]) => {
    const notificationKey = `expiration-${info.expTime}-${tunnels.map(t => t.expTime).join(',')}`;
    const lastNotified = localStorage.getItem('lastNotified');
    
    if (lastNotified === notificationKey) return;
    
    let hasNotification = false;
    
    if (info.expTime) {
      const expDate = new Date(info.expTime);
      const now = new Date();
      
      if (!isNaN(expDate.getTime()) && expDate > now) {
        const diffTime = expDate.getTime() - now.getTime();
        const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
        
        if (diffDays <= 7 && diffDays > 0) {
          hasNotification = true;
          toast(`账户将于${diffDays}天后到期`, { 
            icon: '⚠️',
            duration: 6000,
            style: { background: '#141414', color: '#c8b89a', border: '1px solid rgba(200,184,154,0.25)' }
          });
        }
      }
    }
    
    if (hasNotification) {
      localStorage.setItem('lastNotified', notificationKey);
    }
  };

  // 身份一变（另一标签页登录/退出、bfcache 或可见性恢复）就换 key，这一段整体重跑：
  // 停表作废旧请求 → 清掉上一账号数据 → 按当前身份重新装载。
  useEffect(() => {
    const identity = readIdentitySnapshot(localStorage);
    // 身份在 render 和 effect 之间变化时，等待 watcher 切换 key，绝不使用旧作用域发请求。
    if (identityKeyOf(identity) !== identityKey) return;
    const controller = new AbortController();
    packageAbortRef.current = controller;
    let poller: NodeHealthPoller | null = null;

    const stopIdentityScope = () => {
      // 先作废代次再中断：即使底层没抛 AbortError，迟到的旧账号回调也不能写状态
      packageRequestSeqRef.current += 1;
      refreshSeqRef.current += 1;
      if (packageAbortRef.current === controller) {
        packageAbortRef.current = null;
      }
      controller.abort();
      poller?.stop();
      if (poller && nodeHealthPollerRef.current === poller) {
        nodeHealthPollerRef.current = null;
      }
    };

    // 上一账号的套餐/隧道/转发/流量一律不能留在页面上，管理员摘要同样清空
    setUserInfo({} as UserInfo);
    setUserTunnels([]);
    setForwardList([]);
    setStatisticsFlows([]);
    setLoading(true);
    setRefreshing(false);
    setNodeHealthSnapshot(clearNodeHealthSnapshot);
    setAddressModalOpen(false);
    setAddressModalTitle('');
    setAddressList([]);
    if (!identity.token?.trim()) {
      setLoading(false);
      return stopIdentityScope;
    }

    void loadPackageData();
    localStorage.setItem('e', '/dashboard');

    if (!isAdminIdentity(identity)) {
      // 非管理员不读管理员摘要：上面已清空，这里只留拒绝态
      setNodeHealthSnapshot(denyNodeHealthSnapshot);
      return stopIdentityScope;
    }

    // 首次读取 + 每分钟只读刷新；接口只返回后端最近一次探测结论，浏览器侧不发起任何探测
    poller = nodeHealthPoller();
    void poller.refresh({ showLoading: true });
    poller.start();
    // 卸载时停表并作废在途结果，避免卸载后仍把旧在线写回界面
    return stopIdentityScope;
  }, [identityKey, identityScopeRevision]);

  // 另一标签页登录/退出不会重挂载本页：靠 storage 感知，bfcache/可见性恢复时再核对一次
  useEffect(() => {
    return watchIdentity({
      initialKey: identityKey,
      read: () => readIdentitySnapshot(localStorage),
      onChange: (nextKey) => {
        // 在 React 重新提交新身份前同步终止旧作用域，并作废全部异步回写。
        packageRequestSeqRef.current += 1;
        refreshSeqRef.current += 1;
        packageAbortRef.current?.abort();
        packageAbortRef.current = null;
        nodeHealthPollerRef.current?.stop();
        nodeHealthPollerRef.current = null;
        setUserInfo({} as UserInfo);
        setUserTunnels([]);
        setForwardList([]);
        setStatisticsFlows([]);
        setAddressModalOpen(false);
        setAddressModalTitle('');
        setAddressList([]);
        setNodeHealthSnapshot(clearNodeHealthSnapshot);
        setRefreshing(false);
        setLoading(true);
        setIdentityKey(nextKey);
        setIdentityScopeRevision((revision) => revision + 1);
      },
    });
  }, [identityKey]);

  // 可取消的只读 POST：旧 Network.post 遇 401 不会 settle，且会用旧 token 误清当前会话
  const loadPackageData = async (): Promise<boolean> => {
    const controller = packageAbortRef.current;
    // 页面已卸载 / 尚未挂载：不发请求，也不写任何状态
    const identity = readIdentitySnapshot(localStorage);
    if (!controller || controller.signal.aborted || !identity.token?.trim() || identityKeyOf(identity) !== identityKey) return false;

    const requestSeq = ++packageRequestSeqRef.current;
    // 仍是最新一次请求且页面未卸载时才允许回写；旧响应一律丢弃
    const canWrite = () =>
      packageAbortRef.current === controller &&
      packageRequestSeqRef.current === requestSeq &&
      !controller.signal.aborted &&
      identityKeyOf(readIdentitySnapshot(localStorage)) === identityKey;

    try {
      const res = await Network.postCancellable<any>('/user/package', {}, { signal: controller.signal });
      if (!canWrite()) return false;
      if (res.code === 0) {
        const data = res.data;
        setUserInfo(data.userInfo || {});
        setUserTunnels(data.tunnelPermissions || []);
        setForwardList(data.forwards || []);
        setStatisticsFlows(data.statisticsFlows || []);
        checkExpirationNotifications(data.userInfo, data.tunnelPermissions || []);
        return true;
      }
      console.error('获取面板数据失败:', res.msg);
      return false;
    } catch (error) {
      if (!canWrite()) return false;
      console.error('获取面板数据失败:', error);
      return false;
    } finally {
      if (canWrite()) setLoading(false);
    }
  };

  // 只读取后端最近一次控制通道往返检测的结果，不在浏览器侧发起任何探测
  const nodeHealthPoller = (): NodeHealthPoller => {
    const existing = nodeHealthPollerRef.current;

    if (existing) {
      return existing;
    }
    const created = createNodeHealthPoller({
      // 可取消的只读 POST：卸载/超时时中断请求；401/403 返回明确结果
      read: async (signal) => {
        const identity = readIdentitySnapshot(localStorage);
        if (identityKeyOf(identity) !== identityKey || !isAdminIdentity(identity)) {
          return { ok: false, denied: true, message: '当前身份不可读取节点健康摘要' };
        }
        return interpretNodeHealthResponse(
          await Network.postCancellable<NodeHealthSummary | null>('/node/health/summary', {}, { signal }),
        );
      },
      onChange: (snapshot) => {
        if (identityKeyOf(readIdentitySnapshot(localStorage)) === identityKey) setNodeHealthSnapshot(snapshot);
      },
      // 切回标签页/窗口重新聚焦时立刻重新验证，别让被压低的定时器把旧结论留在页面上
      subscribeWake: subscribeNodeHealthWake,
    });

    nodeHealthPollerRef.current = created;
    return created;
  };

  // 读取失败/被拒时轮询器会把摘要清空，这里不会留下上一次的在线结论
  const loadNodeHealth = (options?: { showLoading?: boolean }): Promise<boolean> => {
    // 按调用时的身份判定，不信任渲染时的旧快照
    const identity = readIdentitySnapshot(localStorage);
    if (identityKeyOf(identity) !== identityKey || !isAdminIdentity(identity)) {
      setNodeHealthSnapshot(denyNodeHealthSnapshot);
      return Promise.resolve(true); // 普通用户无需请求管理员接口
    }
    return nodeHealthPoller().refresh(options);
  };

  // 服务器已按服务器时间格式化好 checkedAtText：原样展示，不拿本机时钟/时区重新解释服务器时刻
  const formatCheckedAt = (node: Pick<NodeHealthNode, 'checkedAt' | 'checkedAtText'>): string => {
    if (node.checkedAtText) return node.checkedAtText;
    const checkedAt = node.checkedAt;
    if (checkedAt == null) return '时间未知';
    const time = new Date(checkedAt);
    if (isNaN(time.getTime())) return '时间无效';
    const pad = (value: number) => value.toString().padStart(2, '0');
    return `${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${pad(time.getHours())}:${pad(time.getMinutes())}:${pad(time.getSeconds())}`;
  };

  // 摘要接口只读；刷新最新结果不会触发新的节点探测
  const handleRefresh = async () => {
    const refreshSeq = ++refreshSeqRef.current;
    // 页面已卸载或本次刷新已被更新的刷新取代：不再提示、不再写 refreshing
    const canWrite = () => {
      const controller = packageAbortRef.current;
      return refreshSeq === refreshSeqRef.current && !!controller && !controller.signal.aborted;
    };
    setRefreshing(true);
    try {
      const [packageOk, nodeHealthOk] = await Promise.all([loadPackageData(), loadNodeHealth({ showLoading: true })]);
      if (!canWrite()) return;
      if (packageOk && nodeHealthOk) {
        toast.success("数据已刷新", {
          style: { background: '#141414', color: '#c8b89a', border: '1px solid rgba(200,184,154,0.25)' }
        });
      } else {
        toast.error("部分数据刷新失败，请重试");
      }
    } finally {
      if (canWrite()) setRefreshing(false);
    }
  };

  const formatFlow = (value: number | undefined | null, unit: string = 'bytes'): string => {
    if (unit === 'gb') {
      if (value === undefined || value === null || value <= 0 || value === 99999) {
        return '无限制';
      }
      return value + ' GB';
    } else {
      const bytes = value || 0;
      if (bytes === 0) return '0 B';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
      if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
      return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }
  };

  const formatNumber = (value: number | undefined | null): string => {
    if (value === undefined || value === null || value <= 0 || value === 99999) {
      return '无限制';
    }
    return value.toString();
  };

  const processFlowChartData = () => {
    const now = new Date();
    const hours: string[] = [];
    for (let i = 23; i >= 0; i--) {
      const time = new Date(now.getTime() - i * 60 * 60 * 1000);
      const hourString = time.getHours().toString().padStart(2, '0') + ':00';
      hours.push(hourString);
    }

    const flowMap = new Map<string, number>();
    statisticsFlows.forEach(item => {
      flowMap.set(item.time, item.flow || 0);
    });

    return hours.map(hour => ({
      time: hour,
      flow: flowMap.get(hour) || 0,
      formattedFlow: formatFlow(flowMap.get(hour) || 0)
    }));
  };

  const getExpStatus = (expTime?: string) => {
    if (!expTime) return { 
      color: 'text-[#a3b89a]', 
      bg: 'bg-[#a3b89a]/10 border-[#a3b89a]/20',
      text: '永久' 
    };

    const now = new Date();
    const expDate = new Date(expTime);

    if (isNaN(expDate.getTime())) {
      return { 
        color: 'text-[#8a857c]', 
        bg: 'bg-white/5 border-white/10',
        text: '无效' 
      };
    }

    if (expDate < now) {
      return { 
        color: 'text-[#c89a9a]', 
        bg: 'bg-[#c89a9a]/10 border-[#c89a9a]/20',
        text: '已过期' 
      };
    }

    const diffTime = expDate.getTime() - now.getTime();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

    if (diffDays <= 7) {
      return { 
        color: 'text-[#e0b84f]', 
        bg: 'bg-[#e0b84f]/10 border-[#e0b84f]/20',
        text: `${diffDays}天后到期` 
      };
    } else {
      return { 
        color: 'text-[#a3b89a]', 
        bg: 'bg-[#a3b89a]/10 border-[#a3b89a]/20',
        text: `${diffDays}天后到期` 
      };
    }
  };

  const calculateUserTotalUsedFlow = (): number => {
    return (userInfo.inFlow || 0) + (userInfo.outFlow || 0);
  };

  const calculateUsagePercentage = (type: 'flow' | 'forwards'): number => {
    if (type === 'flow') {
      const totalUsed = calculateUserTotalUsedFlow();
      const totalLimit = (userInfo.flow || 0) * 1024 * 1024 * 1024;
      if (!userInfo.flow || userInfo.flow <= 0 || userInfo.flow === 99999) return 0;
      return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
    } else if (type === 'forwards') {
      const totalUsed = forwardList.length;
      const totalLimit = userInfo.num || 0;
      if (!userInfo.num || userInfo.num <= 0 || userInfo.num === 99999) return 0;
      return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
    }
    return 0;
  };

  const calculateRemainingQuota = (): string => {
    if (!userInfo.flow || userInfo.flow <= 0 || userInfo.flow === 99999) return '无限制';
    const totalBytes = userInfo.flow * 1024 * 1024 * 1024;
    return formatFlow(Math.max(totalBytes - calculateUserTotalUsedFlow(), 0));
  };

  const calculateRemainingForwards = (): string => {
    if (!userInfo.num || userInfo.num <= 0 || userInfo.num === 99999) return '无限制';
    return String(Math.max(userInfo.num - forwardList.length, 0));
  };

  const renderProgressBar = (percentage: number, isUnlimited: boolean = false) => {
    if (isUnlimited) {
      return (
        <div className="w-full bg-white/[0.06] rounded-full h-1.5 overflow-hidden">
          <div className="h-full bg-gradient-to-r from-[#c8b89a]/40 to-[#c8b89a] rounded-full w-full opacity-70"></div>
        </div>
      );
    }
    
    return (
      <div className="w-full bg-white/[0.06] rounded-full h-1.5 overflow-hidden">
        <div 
          className="h-full rounded-full transition-all duration-300 bg-[#c8b89a]"
          style={{ width: `${Math.min(percentage, 100)}%` }}
        ></div>
      </div>
    );
  };

  const calculateTunnelUsedFlow = (tunnel: UserTunnel): number => {
    if (!tunnel) return 0;
    return (tunnel.inFlow || 0) + (tunnel.outFlow || 0);
  };

  const calculateTunnelFlowPercentage = (tunnel: UserTunnel): number => {
    const totalUsed = calculateTunnelUsedFlow(tunnel);
    const totalLimit = (tunnel.flow || 0) * 1024 * 1024 * 1024;
    if (!tunnel.flow || tunnel.flow <= 0 || tunnel.flow === 99999) return 0;
    return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
  };

  const getTunnelUsedForwards = (tunnelId: number): number => {
    return forwardList.filter(forward => forward.tunnelId === tunnelId).length;
  };

  const calculateTunnelForwardPercentage = (tunnel: UserTunnel): number => {
    const totalUsed = getTunnelUsedForwards(tunnel.tunnelId);
    const totalLimit = tunnel.num || 0;
    if (!tunnel.num || tunnel.num <= 0 || tunnel.num === 99999) return 0;
    return totalLimit > 0 ? Math.min((totalUsed / totalLimit) * 100, 100) : 0;
  };

  const formatResetTime = (resetDay?: number): string => {
    if (resetDay === undefined || resetDay === null) return '';
    if (resetDay === 0) return '不重置';
    
    const now = new Date();
    const currentDay = now.getDate();
    
    let daysUntilReset;
    if (resetDay > currentDay) {
      daysUntilReset = resetDay - currentDay;
    } else if (resetDay < currentDay) {
      const nextMonth = new Date(now.getFullYear(), now.getMonth() + 1, resetDay);
      const diffTime = nextMonth.getTime() - now.getTime();
      daysUntilReset = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    } else {
      daysUntilReset = 0;
    }
    
    if (daysUntilReset === 0) return '今日重置';
    if (daysUntilReset === 1) return '明日重置';
    return `${daysUntilReset}天后重置`;
  };

  const groupedForwards = () => {
    const groups: { [key: string]: { tunnelName: string; forwards: Forward[] } } = {};
    forwardList.forEach(forward => {
      const tunnelName = forward.tunnelName || '默认隧道';
      if (!groups[tunnelName]) {
        groups[tunnelName] = { tunnelName, forwards: [] };
      }
      groups[tunnelName].forwards.push(forward);
    });
    return Object.values(groups);
  };

  const formatInAddress = (ipString: string, port: number): string => {
    if (!ipString) return '';
    const items = ipString.split(',').map(item => item.trim()).filter(Boolean);
    if (items.length === 0) return '';
    const firstItem = items[0];
    if (/:\d+$/.test(firstItem)) {
      return items.length === 1 ? items[0] : `${items[0]} (+${items.length - 1}个)`;
    }
    if (!port) return '';
    if (items.length === 1) {
      return firstItem.includes(':') && !firstItem.startsWith('[') ? `[${firstItem}]:${port}` : `${firstItem}:${port}`;
    }
    const formatted = firstItem.includes(':') && !firstItem.startsWith('[') ? `[${firstItem}]` : firstItem;
    return `${formatted}:${port} (+${items.length - 1}个)`;
  };

  const formatRemoteAddress = (remoteAddr: string): string => {
    if (!remoteAddr) return '';
    const addresses = remoteAddr.split(',').map(addr => addr.trim()).filter(Boolean);
    if (addresses.length === 0) return '';
    return addresses.length === 1 ? addresses[0] : `${addresses[0]} (+${addresses.length - 1})`;
  };

  const hasMultipleIps = (ipString: string): boolean => {
    if (!ipString) return false;
    return ipString.split(',').map(ip => ip.trim()).filter(Boolean).length > 1;
  };

  const hasMultipleRemoteAddresses = (remoteAddr: string): boolean => {
    if (!remoteAddr) return false;
    return remoteAddr.split(',').map(addr => addr.trim()).filter(Boolean).length > 1;
  };

  const showAddressModal = (ipString: string, port: number, title: string) => {
    if (!ipString) return;
    const items = ipString.split(',').map(item => item.trim()).filter(Boolean);
    if (items.length <= 1) {
      copyToClipboard(formatInAddress(ipString, port));
      return;
    }
    const hasPort = /:\d+$/.test(items[0]);
    const formattedList = items.map((ip, index) => {
      let formattedAddress;
      if (hasPort) {
        formattedAddress = ip;
      } else if (ip.includes(':') && !ip.startsWith('[')) {
        formattedAddress = `[${ip}]:${port}`;
      } else {
        formattedAddress = `${ip}:${port}`;
      }
      return { id: index, ip, address: formattedAddress, copying: false };
    });
    setAddressList(formattedList);
    setAddressModalTitle(`${title} (${items.length}个)`);
    setAddressModalOpen(true);
  };

  const showRemoteAddressModal = (remoteAddr: string, title: string) => {
    if (!remoteAddr) return;
    const addresses = remoteAddr.split(',').map(addr => addr.trim()).filter(Boolean);
    if (addresses.length <= 1) {
      copyToClipboard(remoteAddr);
      return;
    }
    const formattedList = addresses.map((address, index) => ({
      id: index,
      ip: address,
      address: address,
      copying: false
    }));
    setAddressList(formattedList);
    setAddressModalTitle(`${title} (${addresses.length}个)`);
    setAddressModalOpen(true);
  };

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success('已复制');
    } catch {
      toast.error('复制失败');
    }
  };

  const copyAddress = async (addressItem: AddressItem) => {
    try {
      setAddressList(prev => prev.map(item => item.id === addressItem.id ? { ...item, copying: true } : item));
      await copyToClipboard(addressItem.address);
    } finally {
      setAddressList(prev => prev.map(item => item.id === addressItem.id ? { ...item, copying: false } : item));
    }
  };

  const copyAllAddresses = async () => {
    if (addressList.length === 0) return;
    await copyToClipboard(addressList.map(item => item.address).join('\n'));
  };

  const calculateForwardBillingFlow = (forward: Forward): number => {
    if (!forward) return 0;
    return (forward.inFlow || 0) + (forward.outFlow || 0);
  };

  // 后端有效期兜底：结论年龄只用「服务器时间差 + 本机实测往返/单调耗时」推算，
  // 不拿本机 Date.now 减服务器 checkedAt（跨时钟偏差会把刚检测完的在线判成过期）
  const nodeHealthNodes = applyNodeHealthFreshness(
    nodeHealth?.nodes ?? [],
    nodeHealth?.freshnessMs,
    nodeHealthSnapshot.basis,
  );
  // 统计与明细同源：有明细就按明细重算，过期结论落进未知并单独记 staleCount
  const healthCounts = resolveNodeHealthCounts(nodeHealth, nodeHealthNodes);
  const healthTotal = healthCounts.total;
  const healthOnline = healthCounts.online;
  const healthUnknown = healthCounts.unknown;
  const healthStale = healthCounts.staleCount;

  const renderIdentity = readIdentitySnapshot(localStorage);
  if (!renderIdentity.token?.trim() || identityKeyOf(renderIdentity) !== identityKey) {
    return <div className="px-3 lg:px-6 pt-4 text-[#888]">请登录后查看仪表板</div>;
  }

  if (loading) {
    return (
      <div className="px-3 lg:px-6 flex-grow pt-4">
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <div className="animate-spin h-5 w-5 border-2 border-white/10 border-t-[#c8b89a] rounded-full"></div>
            <span className="text-[#888] font-mono text-sm tracking-wide">正在加载数据...</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-3 lg:px-6 py-4 space-y-6">
      {/* 顶栏简洁操作区 */}
      <div className="flex items-center justify-between pb-3 border-b border-white/[0.06]">
        <div className="flex items-center gap-2.5">
          <div className="w-1.5 h-4 bg-[#c8b89a] rounded-sm"></div>
          <h1 className="text-base font-medium text-[#e6e6e6] tracking-wide">仪表板概览</h1>
        </div>
        <Button
          size="sm"
          onClick={handleRefresh}
          isLoading={refreshing}
          className="bg-white/5 hover:bg-white/10 border border-white/10 text-[#c8b89a] font-mono text-xs px-3 h-8 rounded-lg"
        >
          刷新数据
        </Button>
      </div>

      {/* 4 项核心数据指标卡片 (严格使用真实数据，深度黑金岩石视觉规范) */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 lg:gap-4">
        {/* 卡片 1: 总流量配额 */}
        <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2.5">
            <span className="text-[10px] font-semibold text-[#8a857c] tracking-[2px] uppercase font-mono">总流量配额 / TOTAL</span>
            <div className="p-1.5 bg-[#c8b89a]/10 border border-[#c8b89a]/20 rounded text-[#c8b89a]">
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
              </svg>
            </div>
          </div>
          <div className="text-2xl lg:text-3xl font-light text-[#c8b89a] font-mono tracking-tight">
            {formatFlow(userInfo.flow, 'gb')}
          </div>
          <div className="mt-3 text-[11px] text-[#8a857c] font-mono flex items-center justify-between pt-2 border-t border-white/[0.04]">
            <span>套餐额度</span>
            <span className="text-[#a3b89a]">剩余 {calculateRemainingQuota()}</span>
          </div>
        </div>

        {/* 卡片 2: 已消耗流量 */}
        <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2.5">
            <span className="text-[10px] font-semibold text-[#8a857c] tracking-[2px] uppercase font-mono">已消耗流量 / USED</span>
            <div className="p-1.5 bg-[#a3b89a]/10 border border-[#a3b89a]/20 rounded text-[#a3b89a]">
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M12 7a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0V8.414l-4.293 4.293a1 1 0 01-1.414 0L8 10.414l-4.293 4.293a1 1 0 01-1.414-1.414l5-5a1 1 0 011.414 0L11 10.586 14.586 7H12z" clipRule="evenodd" />
              </svg>
            </div>
          </div>
          <div className="text-2xl lg:text-3xl font-light text-[#c8b89a] font-mono tracking-tight">
            {formatFlow(calculateUserTotalUsedFlow())}
          </div>
          <div className="mt-3 space-y-1.5 pt-2 border-t border-white/[0.04]">
            {renderProgressBar(calculateUsagePercentage('flow'), !userInfo.flow || userInfo.flow <= 0 || userInfo.flow === 99999)}
            <div className="flex items-center justify-between text-[11px] text-[#8a857c] font-mono">
              <span>{!userInfo.flow || userInfo.flow <= 0 || userInfo.flow === 99999 ? '无限制' : `${calculateUsagePercentage('flow').toFixed(1)}%`}</span>
              {(userInfo.flowResetTime !== undefined && userInfo.flowResetTime !== null) && (
                <span className="text-[#a3b89a]">{formatResetTime(userInfo.flowResetTime)}</span>
              )}
            </div>
          </div>
        </div>

        {/* 卡片 3: 转发配额 */}
        <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2.5">
            <span className="text-[10px] font-semibold text-[#8a857c] tracking-[2px] uppercase font-mono">转发配额 / QUOTA</span>
            <div className="p-1.5 bg-[#c8b89a]/10 border border-[#c8b89a]/20 rounded text-[#c8b89a]">
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clipRule="evenodd" />
              </svg>
            </div>
          </div>
          <div className="text-2xl lg:text-3xl font-light text-[#c8b89a] font-mono tracking-tight">
            {formatNumber(userInfo.num || 0)}
          </div>
          <div className="mt-3 text-[11px] text-[#8a857c] font-mono flex items-center justify-between pt-2 border-t border-white/[0.04]">
            <span>规则槽位</span>
            <span className="text-[#c8b89a]">剩余 {calculateRemainingForwards()}</span>
          </div>
        </div>

        {/* 卡片 4: 已用转发 */}
        <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-2.5">
            <span className="text-[10px] font-semibold text-[#8a857c] tracking-[2px] uppercase font-mono">已部署转发 / ACTIVE</span>
            <div className="p-1.5 bg-[#c8b89a]/10 border border-[#c8b89a]/20 rounded text-[#c8b89a]">
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" />
              </svg>
            </div>
          </div>
          <div className="text-2xl lg:text-3xl font-light text-[#c8b89a] font-mono tracking-tight">
            {forwardList.length}
          </div>
          <div className="mt-3 space-y-1.5 pt-2 border-t border-white/[0.04]">
            {renderProgressBar(calculateUsagePercentage('forwards'), !userInfo.num || userInfo.num <= 0 || userInfo.num === 99999)}
            <div className="flex items-center justify-between text-[11px] text-[#8a857c] font-mono">
              <span>已占用</span>
              <span>{!userInfo.num || userInfo.num <= 0 || userInfo.num === 99999 ? '无限制' : `${calculateUsagePercentage('forwards').toFixed(1)}%`}</span>
            </div>
          </div>
        </div>
      </div>

      {/* 节点健康矩阵：仅管理员可读（/node/health/summary 带 @RequireRole）；状态来自控制通道往返检测，非业务链路 */}
      <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl px-4 py-3 shadow-sm">
        <div className="flex items-center justify-between gap-3 pb-2.5 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-1.5 h-3.5 bg-[#c8b89a] rounded-sm" />
            <h2 className="text-xs lg:text-sm font-medium text-[#e6e6e6] tracking-wide">节点健康矩阵</h2>
          </div>
          <span className="text-[11px] font-mono text-[#8a857c]">
            上次检测在线 <span className="text-[#a3b89a] font-medium">{nodeHealthState === 'ready' ? healthOnline : '—'}</span>
            {' / '}总数 {nodeHealthState === 'ready' ? healthTotal : '—'}
            {nodeHealthState === 'ready' && healthUnknown > 0 && (
              <span className="text-[#e0b84f]"> · 未知 {healthUnknown}</span>
            )}
            {nodeHealthState === 'ready' && healthStale > 0 && (
              <span className="text-[#8a857c]"> · 其中旧结果 {healthStale}</span>
            )}
          </span>
        </div>

        {nodeHealthState === 'ready' && <NodeHealthCapacityNotice nodes={nodeHealthNodes} />}
        {nodeHealthState === 'ready' && nodeHealth?.stateHealthy === false && (
          <p role="alert" className="pt-2 text-[11px] text-[#c89a9a]">检测状态保存异常，自动检测可能暂停；请检查面板日志及数据目录权限。</p>
        )}
        {nodeHealthState === 'denied' ? (
          <div className="flex items-center gap-2 py-3 text-[11px] font-mono text-[#8a857c]">
            <svg className="w-3.5 h-3.5 text-[#555] shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} />
            </svg>
            <span>当前账号无节点健康查看权限，仅管理员可读取健康摘要</span>
          </div>
        ) : nodeHealthState === 'loading' || nodeHealthState === 'idle' ? (
          <div className="flex items-center gap-2 py-3 text-[11px] font-mono text-[#8a857c]">
            <span className="animate-spin h-3 w-3 border border-white/10 border-t-[#c8b89a] rounded-full" />
            <span>正在读取节点健康摘要…</span>
          </div>
        ) : nodeHealthState === 'error' ? (
          <div className="flex items-center justify-between gap-3 py-3 text-[11px] font-mono">
            <span className="text-[#c89a9a] truncate">节点健康摘要读取失败：{nodeHealthError}</span>
            <Button className="text-[#c8b89a] text-xs h-7 px-2 shrink-0" size="sm" variant="light" onClick={() => { void loadNodeHealth({ showLoading: true }); }}>重试</Button>
          </div>
        ) : nodeHealthNodes.length === 0 ? (
          <div className="flex items-center gap-2 py-3 text-[11px] font-mono text-[#8a857c]">
            <span className="w-3 h-3 rounded-[3px] border border-white/10 bg-white/[0.04]" />
            <span>暂无节点（后端返回空节点数组）</span>
          </div>
        ) : (
          <div className="pt-2.5 space-y-2.5">
            <div className="flex items-start gap-3">
              <div className="flex flex-wrap gap-1.5">
                {nodeHealthNodes.map((node) => {
                  const status = nodeHealthStatusOf(node);
                  const meta = NODE_STATUS_META[status];
                  const history = node.stale
                    ? `（旧结果 ${node.staleNote || '上次结论已过期，不计入在线'}）${node.lastLatencyMs != null ? ` · 上次延迟 ${node.lastLatencyMs}ms` : ''}`
                    : '';
                  return (
                    <div
                      key={node.nodeId}
                      className={`w-6 h-6 rounded-[5px] border cursor-help transition-colors ${meta.tile}`}
                      title={`${node.nodeName || '未命名节点'} · ${meta.label} · 检测 ${formatCheckedAt(node)}${history}${node.detail ? ` · ${node.detail}` : ''} · ID ${node.nodeId}`}
                    />
                  );
                })}
              </div>
              <div className="ml-auto flex flex-col items-end gap-1 text-[10px] font-mono text-[#8a857c] shrink-0">
                <div className="flex items-center gap-3">
                  {NODE_STATUS_ORDER.map((status) => (
                    <span key={status} className="flex items-center gap-1">
                      <span className={`w-2 h-2 rounded-[2px] ${NODE_STATUS_META[status].dot}`} />
                      {NODE_STATUS_META[status].label}
                    </span>
                  ))}
                </div>
                <span className="text-[#555]">显示最近一次控制通道检测 · 每分钟只读摘要 · 读取不触发检测 · 结论过期自动转未知</span>
              </div>
            </div>

            <div className="space-y-1 max-h-40 overflow-y-auto pr-1 pt-2 border-t border-white/[0.06]">
              {nodeHealthNodes.map((node) => {
                const status = nodeHealthStatusOf(node);
                const meta = NODE_STATUS_META[status];
                return (
                  <div
                    key={node.nodeId}
                    className="flex items-center justify-between gap-3 text-[10px] font-mono"
                    title={node.detail || undefined}
                  >
                    <span className="flex items-center gap-1.5 min-w-0">
                      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${meta.dot}`} />
                      <span className="text-[#e6e6e6] truncate">{node.nodeName || `节点 ${node.nodeId}`}</span>
                      <span className={`${meta.text} shrink-0`}>{meta.label}</span>
                      {node.stale && <span className="text-[#e0b84f] shrink-0">旧结果</span>}
                    </span>
                    <span className="text-[#8a857c] shrink-0">
                      {node.stale ? '上次 ' : ''}{formatCheckedAt(node)}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      {/* 24小时流量趋势分析 */}
      <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 lg:p-6 shadow-sm">
        <div className="flex items-center justify-between mb-4 pb-3 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-1.5 h-4 bg-[#c8b89a] rounded-sm"></div>
            <h2 className="text-sm lg:text-base font-medium text-[#e6e6e6] tracking-wide">24小时流量趋势</h2>
          </div>
          <span className="text-[10px] tracking-[2px] uppercase text-[#8a857c] font-mono">24H TRAFFIC ACTIVITY</span>
        </div>
        
        <div>
          {statisticsFlows.length === 0 ? (
            <div className="text-center py-12">
              <svg className="w-10 h-10 text-[#555] mx-auto mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              <p className="text-xs text-[#8a857c] font-mono">暂无 24 小时流量记录</p>
            </div>
          ) : (
            <div className="h-64 lg:h-72 w-full pt-2">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={processFlowChartData()} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#c8b89a" stopOpacity={0.25}/>
                      <stop offset="95%" stopColor="#c8b89a" stopOpacity={0.0}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
                  <XAxis 
                    dataKey="time" 
                    tick={{ fontSize: 10, fill: '#8a857c', fontFamily: 'monospace' }}
                    tickLine={false}
                    axisLine={{ stroke: 'rgba(255,255,255,0.08)' }}
                  />
                  <YAxis 
                    tick={{ fontSize: 10, fill: '#8a857c', fontFamily: 'monospace' }}
                    tickLine={false}
                    axisLine={{ stroke: 'rgba(255,255,255,0.08)' }}
                    tickFormatter={(value) => {
                      if (value === 0) return '0';
                      if (value < 1024) return `${value}B`;
                      if (value < 1024 * 1024) return `${(value / 1024).toFixed(0)}K`;
                      if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(0)}M`;
                      return `${(value / (1024 * 1024 * 1024)).toFixed(1)}G`;
                    }}
                  />
                  <Tooltip 
                    content={({ active, payload, label }) => {
                      if (active && payload && payload.length) {
                        return (
                          <div className="bg-[#101010]/95 backdrop-blur-md border border-[#c8b89a]/30 rounded p-2.5 shadow-xl text-xs font-mono">
                            <p className="text-[#888] mb-1">{`时间: ${label}`}</p>
                            <p className="text-[#c8b89a] font-medium">
                              {`吞吐: ${formatFlow(payload[0]?.value as number || 0)}`}
                            </p>
                          </div>
                        );
                      }
                      return null;
                    }}
                  />
                  <Area
                    type="monotone"
                    dataKey="flow"
                    stroke="#c8b89a"
                    strokeWidth={2}
                    fillOpacity={1}
                    fill="url(#chartGradient)"
                    activeDot={{ r: 4, stroke: '#c8b89a', strokeWidth: 2, fill: '#050505' }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      </div>

      {/* 已授权隧道与配额 */}
      {userTunnels.length > 0 && (
        <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 lg:p-6 shadow-sm">
          <div className="flex items-center justify-between mb-4 pb-3 border-b border-white/[0.06]">
            <div className="flex items-center gap-2.5">
              <div className="w-1.5 h-4 bg-[#c8b89a] rounded-sm"></div>
              <h2 className="text-sm lg:text-base font-medium text-[#e6e6e6] tracking-wide">已授权隧道与配额</h2>
            </div>
            <span className="px-2.5 py-0.5 bg-[#c8b89a]/10 text-[#c8b89a] border border-[#c8b89a]/25 rounded text-xs font-mono">
              {userTunnels.length} 条隧道可用
            </span>
          </div>

          <div className="space-y-3">
            {userTunnels.map((tunnel) => {
              const tunnelExpStatus = getExpStatus(tunnel.expTime);
              return (
                <div key={tunnel.id} className="border border-white/[0.06] bg-black/40 rounded-xl p-3.5 hover:border-[#c8b89a]/30 transition-all">
                  <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-2 mb-3">
                    <div>
                      <h3 className="font-medium text-[#e6e6e6] text-sm flex items-center gap-2 font-mono">
                        <span>{tunnel.tunnelName}</span>
                        <span className="text-[10px] text-[#8a857c]">ID: {tunnel.id}</span>
                      </h3>
                      <div className="flex flex-wrap items-center gap-2 mt-1.5">
                        <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-[#c8b89a]/10 text-[#c8b89a] border border-[#c8b89a]/25">
                          {tunnel.tunnelFlow === 1 ? '单向计费' : '双向计费'}
                        </span>
                        <span className={`px-1.5 py-0.5 rounded text-[10px] font-mono border ${tunnelExpStatus.bg} ${tunnelExpStatus.color}`}>
                          {tunnelExpStatus.text}
                        </span>
                        {tunnel.speedLimitName && tunnel.speedLimitName !== '无限制' && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-[#e0b84f]/10 text-[#e0b84f] border border-[#e0b84f]/25">
                            ⚡ {tunnel.speedLimitName}
                          </span>
                        )}
                        {(tunnel.flowResetTime !== undefined && tunnel.flowResetTime !== null) && (
                          <span className="text-[10px] text-[#8a857c] font-mono">
                            {formatResetTime(tunnel.flowResetTime)}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                  
                  <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 pt-2.5 border-t border-white/[0.04]">
                    <div>
                      <p className="text-[11px] text-[#8a857c] mb-0.5">流量限额</p>
                      <p className="font-mono text-xs text-[#c8b89a]">{formatFlow(tunnel.flow, 'gb')}</p>
                    </div>
                    <div>
                      <p className="text-[11px] text-[#8a857c] mb-0.5">已用流量</p>
                      <p className="font-mono text-xs text-[#e6e6e6]">{formatFlow(calculateTunnelUsedFlow(tunnel))}</p>
                      <div className="mt-1">
                        {renderProgressBar(calculateTunnelFlowPercentage(tunnel), !tunnel.flow || tunnel.flow <= 0 || tunnel.flow === 99999)}
                      </div>
                    </div>
                    <div>
                      <p className="text-[11px] text-[#8a857c] mb-0.5">转发槽位</p>
                      <p className="font-mono text-xs text-[#c8b89a]">{formatNumber(tunnel.num)}</p>
                    </div>
                    <div>
                      <p className="text-[11px] text-[#8a857c] mb-0.5">已用转发</p>
                      <p className="font-mono text-xs text-[#e6e6e6]">{getTunnelUsedForwards(tunnel.tunnelId)}</p>
                      <div className="mt-1">
                        {renderProgressBar(calculateTunnelForwardPercentage(tunnel), !tunnel.num || tunnel.num <= 0 || tunnel.num === 99999)}
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 转发规则实时监控 */}
      <div className="bg-[rgba(16,16,16,0.65)] backdrop-blur-md border border-white/[0.08] hover:border-[#c8b89a]/30 transition-all duration-300 rounded-xl p-4 lg:p-6 shadow-sm">
        <div className="flex items-center justify-between mb-4 pb-3 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-1.5 h-4 bg-[#c8b89a] rounded-sm"></div>
            <h2 className="text-sm lg:text-base font-medium text-[#e6e6e6] tracking-wide">转发规则监控</h2>
          </div>
          <span className="px-2.5 py-0.5 bg-[#c8b89a]/10 text-[#c8b89a] border border-[#c8b89a]/25 rounded text-xs font-mono">
            {forwardList.length} 条转发规则
          </span>
        </div>

        <div>
          {groupedForwards().length === 0 ? (
            <div className="text-center py-12">
              <svg className="w-10 h-10 text-[#555] mx-auto mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
              </svg>
              <p className="text-xs text-[#8a857c] font-mono">暂无已部署的转发规则</p>
            </div>
          ) : (
            <div className="space-y-5">
              {groupedForwards().map((group) => (
                <div key={group.tunnelName} className="border border-white/[0.06] bg-black/40 rounded-xl p-4">
                  <div className="flex items-center justify-between mb-3.5 pb-2.5 border-b border-white/[0.04]">
                    <h3 className="text-xs font-medium text-[#e6e6e6] tracking-wide flex items-center gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-[#c8b89a]"></span>
                      <span>{group.tunnelName}</span>
                    </h3>
                    <span className="text-[11px] text-[#8a857c] font-mono bg-white/5 border border-white/5 px-2 py-0.5 rounded">
                      {group.forwards.length} 个端点
                    </span>
                  </div>
                  
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-3.5">
                    {group.forwards.map((forward) => (
                      <div key={forward.id} className="bg-[rgba(18,18,18,0.7)] backdrop-blur-sm border border-white/[0.06] hover:border-[#c8b89a]/40 rounded-xl p-3.5 transition-all duration-200 shadow-sm flex flex-col justify-between">
                        <div className="space-y-2.5">
                          <div className="flex items-center justify-between gap-1.5">
                            <h4 className="font-medium text-[#e6e6e6] text-xs truncate flex-1 font-mono">{forward.name}</h4>
                            {forward.status === 1 ? (
                              <span className="px-1.5 py-0.5 text-[9px] bg-[#a3b89a]/15 text-[#a3b89a] border border-[#a3b89a]/30 rounded font-mono shrink-0">正常</span>
                            ) : forward.status === 0 ? (
                              <span className="px-1.5 py-0.5 text-[9px] bg-[#e0b84f]/15 text-[#e0b84f] border border-[#e0b84f]/30 rounded font-mono shrink-0">
                                {forward.pauseReason === 'quota' ? '超额' : forward.pauseReason === 'expiry' ? '到期' : forward.pauseReason === 'disabled' ? '停用' : forward.pauseReason === 'manual' ? '手动' : '暂停'}
                              </span>
                            ) : forward.status === -1 ? (
                              <span className="px-1.5 py-0.5 text-[9px] bg-[#c89a9a]/15 text-[#c89a9a] border border-[#c89a9a]/30 rounded font-mono shrink-0">异常</span>
                            ) : null}
                          </div>

                          <div className="space-y-1">
                            <button
                              type="button"
                              disabled={!hasMultipleIps(forward.inIp)}
                              className={`block w-full text-left px-2 py-1 bg-black/60 border border-white/5 hover:border-[#c8b89a]/40 text-[#c8b89a] rounded font-mono text-[11px] truncate transition-colors ${hasMultipleIps(forward.inIp) ? 'cursor-pointer hover:bg-[#c8b89a]/10' : ''}`}
                              onClick={() => showAddressModal(forward.inIp, forward.inPort, '入口地址')}
                              title={formatInAddress(forward.inIp, forward.inPort)}
                            >
                              {formatInAddress(forward.inIp, forward.inPort)}
                            </button>
                            <div className="text-center text-[#555] text-[10px] leading-none">↓</div>
                            <button
                              type="button"
                              disabled={!hasMultipleRemoteAddresses(forward.remoteAddr)}
                              className={`block w-full text-left px-2 py-1 bg-black/60 border border-white/5 hover:border-[#c8b89a]/30 text-[#e6e6e6] rounded font-mono text-[11px] truncate transition-colors ${hasMultipleRemoteAddresses(forward.remoteAddr) ? 'cursor-pointer hover:bg-[#c8b89a]/10' : ''}`}
                              onClick={() => showRemoteAddressModal(forward.remoteAddr, '出口地址')}
                              title={formatRemoteAddress(forward.remoteAddr)}
                            >
                              {formatRemoteAddress(forward.remoteAddr)}
                            </button>
                          </div>
                        </div>
                        
                        <div className="mt-3 pt-2.5 border-t border-white/[0.04]">
                          <div className="grid grid-cols-3 gap-1 text-[11px] font-mono">
                            <div className="text-center">
                              <div className="text-[#8a857c] text-[10px] mb-0.5">上行</div>
                              <div className="font-medium text-[#c8b89a] truncate">{formatFlow(forward.inFlow || 0)}</div>
                            </div>
                            <div className="text-center">
                              <div className="text-[#8a857c] text-[10px] mb-0.5">下行</div>
                              <div className="font-medium text-[#c8b89a] truncate">{formatFlow(forward.outFlow || 0)}</div>
                            </div>
                            <div className="text-center">
                              <div className="text-[#8a857c] text-[10px] mb-0.5">计费</div>
                              <div className="font-medium text-[#e6e6e6] truncate">{formatFlow(calculateForwardBillingFlow(forward))}</div>
                            </div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 地址列表弹窗 */}
      <Modal 
        isOpen={addressModalOpen} 
        onClose={() => setAddressModalOpen(false)} 
        size="2xl" 
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        classNames={{
          base: "bg-[#101010]/95 backdrop-blur-xl border border-[#c8b89a]/25 text-[#e6e6e6] shadow-2xl",
          header: "border-b border-white/5 text-sm font-mono text-[#c8b89a]",
          body: "py-4",
          closeButton: "hover:bg-white/5 active:bg-white/10 text-[#888]"
        }}
      >
        <ModalContent>
          <ModalHeader className="text-sm font-mono text-[#c8b89a] flex items-center justify-between">
            <span>{addressModalTitle}</span>
          </ModalHeader>
          <ModalBody className="pb-6">
            <div className="mb-3 text-right">
              <Button 
                size="sm" 
                variant="flat"
                className="bg-[#c8b89a]/15 text-[#c8b89a] border border-[#c8b89a]/30 hover:bg-[#c8b89a]/25 text-xs font-mono"
                onClick={copyAllAddresses}
              >
                复制全部地址
              </Button>
            </div>
            
            <div className="space-y-2 max-h-60 overflow-y-auto pr-1">
              {addressList.map((item) => (
                <div key={item.id} className="flex justify-between items-center p-2.5 bg-black/40 border border-white/5 hover:border-[#c8b89a]/30 rounded">
                  <code className="text-xs flex-1 mr-3 text-[#e6e6e6] font-mono break-all">{item.address}</code>
                  <Button
                    size="sm"
                    variant="light"
                    className="text-xs text-[#c8b89a] hover:bg-[#c8b89a]/10"
                    isLoading={item.copying}
                    onClick={() => copyAddress(item)}
                  >
                    复制
                  </Button>
                </div>
              ))}
            </div>
          </ModalBody>
        </ModalContent>
      </Modal>
    </div>
  );
}
