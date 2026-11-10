import { Button } from "@heroui/button";
import { Modal, ModalContent, ModalHeader, ModalBody } from "@heroui/modal";
import { useState, useEffect } from "react";
import toast from 'react-hot-toast';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

import { getUserPackageInfo } from "@/api";

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

  useEffect(() => {
    loadPackageData();
    localStorage.setItem('e', '/dashboard');
  }, []);

  const loadPackageData = async () => {
    try {
      const res = await getUserPackageInfo();
      if (res.code === 0) {
        const data = res.data;
        setUserInfo(data.userInfo || {});
        setUserTunnels(data.tunnelPermissions || []);
        setForwardList(data.forwards || []);
        setStatisticsFlows(data.statisticsFlows || []);
        checkExpirationNotifications(data.userInfo, data.tunnelPermissions || []);
      }
    } catch (error) {
      console.error('获取面板数据失败:', error);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    await loadPackageData();
    toast.success("数据已刷新", {
      style: { background: '#141414', color: '#c8b89a', border: '1px solid rgba(200,184,154,0.25)' }
    });
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
            {forwardList.length} 规则在线
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
                            <code 
                              className={`block px-2 py-1 bg-black/60 border border-white/5 hover:border-[#c8b89a]/40 text-[#c8b89a] rounded font-mono text-[11px] truncate transition-colors ${hasMultipleIps(forward.inIp) ? 'cursor-pointer hover:bg-[#c8b89a]/10' : ''}`}
                              onClick={() => hasMultipleIps(forward.inIp) && showAddressModal(forward.inIp, forward.inPort, '入口地址')}
                              title={formatInAddress(forward.inIp, forward.inPort)}
                            >
                              {formatInAddress(forward.inIp, forward.inPort)}
                            </code>
                            <div className="text-center text-[#555] text-[10px] leading-none">↓</div>
                            <code 
                              className={`block px-2 py-1 bg-black/60 border border-white/5 hover:border-[#c8b89a]/30 text-[#e6e6e6] rounded font-mono text-[11px] truncate transition-colors ${hasMultipleRemoteAddresses(forward.remoteAddr) ? 'cursor-pointer hover:bg-[#c8b89a]/10' : ''}`}
                              onClick={() => hasMultipleRemoteAddresses(forward.remoteAddr) && showRemoteAddressModal(forward.remoteAddr, '出口地址')}
                              title={formatRemoteAddress(forward.remoteAddr)}
                            >
                              {formatRemoteAddress(forward.remoteAddr)}
                            </code>
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
