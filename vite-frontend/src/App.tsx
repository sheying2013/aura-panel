import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import IndexPage from "@/pages/index";
import ChangePasswordPage from "@/pages/change-password";
import DashboardPage from "@/pages/dashboard";
import ForwardPage from "@/pages/forward";
import DdnsManagement from "@/components/DdnsManagement";
import TunnelPage from "@/pages/tunnel";
import NodePage from "@/pages/node";
import UserPage from "@/pages/user";
import ProfilePage from "@/pages/profile";
import LimitPage from "@/pages/limit";
import ConfigPage from "@/pages/config";
import { SettingsPage } from "@/pages/settings";

import AdminLayout from "@/layouts/admin";
import H5Layout from "@/layouts/h5";
import H5SimpleLayout from "@/layouts/h5-simple";

import { invalidateAuthSession, isAuthIdentityStale, isLoggedIn, revalidateAuthSession, useAuthSession } from "@/utils/auth";
import { siteConfig } from "@/config/site";
import { beginReauthentication, getAuthIdentityKey, getReauthenticationNoticeId, subscribeAuthChange, subscribeReauthenticationNotice } from "@/api/network";

// 检测是否为H5模式
const useH5Mode = () => {
  // 立即检测H5模式，避免初始渲染时的闪屏
  const getInitialH5Mode = () => {
    // 检测移动设备或小屏幕
    const isMobile = window.innerWidth <= 768;
    // 检测是否为移动端浏览器
    const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    // 检测URL参数是否包含h5模式
    const urlParams = new URLSearchParams(window.location.search);
    const isH5Param = urlParams.get('h5') === 'true';
    
    return isMobile || isMobileBrowser || isH5Param;
  };

  const [isH5, setIsH5] = useState(getInitialH5Mode);

  useEffect(() => {
    const checkH5Mode = () => {
      // 检测移动设备或小屏幕
      const isMobile = window.innerWidth <= 768;
      // 检测是否为移动端浏览器
      const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
      // 检测URL参数是否包含h5模式
      const urlParams = new URLSearchParams(window.location.search);
      const isH5Param = urlParams.get('h5') === 'true';
      
      setIsH5(isMobile || isMobileBrowser || isH5Param);
    };

    window.addEventListener('resize', checkH5Mode);
    
    return () => window.removeEventListener('resize', checkH5Mode);
  }, []);

  return isH5;
};

// 简化的路由保护组件 - 使用 React Router 导航避免循环
const ProtectedRoute = ({ children, useSimpleLayout = false, skipLayout = false }: { children: React.ReactNode, useSimpleLayout?: boolean, skipLayout?: boolean }) => {
  // 订阅 token 与代次，避免 pending=false 不变时忽略 logout/换账号。
  const identityKey = useSyncExternalStore(subscribeReauthenticationNotice, getAuthIdentityKey, () => '[null,null]');
  // 身份只认已复验的会话；unknown（失效中/复验中）既不放行也不展示数据。
  const session = useAuthSession();
  const authenticated = session.state === "verified" && isLoggedIn();
  const pending = session.state === "unknown";
  const isH5 = useH5Mode();
  const navigate = useNavigate();
  
  useEffect(() => {
    if (!pending && !authenticated) {
      // pending 保留当前路由，避免抢先跳转掩盖显式重登入口。
      navigate('/', { replace: true });
    }
  }, [pending, authenticated, navigate]);

  if (pending) {
    return <div aria-hidden="true" className="min-h-screen bg-white dark:bg-black" />;
  }

  if (!authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-white dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }

  // 如果跳过布局，直接返回子组件
  if (skipLayout) {
    return <>{children}</>;
  }

  // 根据模式和页面类型选择布局
  let Layout;
  if (isH5 && useSimpleLayout) {
    Layout = H5SimpleLayout;
  } else if (isH5) {
    Layout = H5Layout;
  } else {
    Layout = AdminLayout;
  }
  
  return <Layout key={identityKey}>{children}</Layout>;
};


// 显式重登只改变当前标签的路由，不清除共享的本地会话。
const LoginRoute = () => {
  // 曾显示匿名登录表单时，等待登录处理器导航，避免覆盖强制改密分支。
  const startedLogin = useRef(false);
  const session = useAuthSession();
  if (session.state === "anonymous") startedLogin.current = true;
  const authenticated = session.state === "verified" && isLoggedIn() && !startedLogin.current;
  const navigate = useNavigate();
  const location = useLocation();
  const reauth = new URLSearchParams(location.search).get("reauth") === "1";
  
  useEffect(() => {
    if (authenticated && !reauth) {
      // 使用 React Router 导航，避免无限跳转
      navigate('/dashboard', { replace: true });
    }
  }, [authenticated, navigate, reauth]);
  
  if (authenticated && !reauth) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100 dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }

  if (session.state === "unknown" && !reauth && !startedLogin.current) {
    return <div aria-hidden="true" className="min-h-screen bg-gray-100 dark:bg-black" />;
  }
  
  return <IndexPage />;
};

const ReauthenticationNotice = () => {
  const noticeId = useSyncExternalStore(subscribeReauthenticationNotice, getReauthenticationNoticeId, () => 0);
  const session = useAuthSession();
  const pending = session.state === "unknown";
  const navigate = useNavigate();
  const location = useLocation();
  if ((!noticeId && !pending) || location.pathname === "/") return null;

  if (pending) return (
    <div role="alert" className="fixed z-[100] top-4 left-4 right-4 mx-auto max-w-xl rounded border border-amber-500 bg-neutral-950 p-4 text-white shadow-lg">
      <p>登录状态正在验证。若状态持续不变，可在当前标签重新登录。</p>
      <button type="button" className="mt-2 rounded border border-white px-3 py-1 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white" onClick={() => {
        navigate("/?reauth=1");
      }}>重新登录</button>
    </div>
  );

  return (
    <div role="alert" className="fixed z-[100] top-4 left-4 right-4 mx-auto max-w-xl rounded border border-amber-500 bg-neutral-950 p-4 text-white shadow-lg">
      <p>登录状态已失效。可在当前标签重新登录，其他标签的会话不会被自动清除。</p>
      <button type="button" className="mt-2 rounded border border-white px-3 py-1 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white" onClick={() => {
        if (beginReauthentication(noticeId)) navigate("/?reauth=1");
      }}>重新登录</button>
    </div>
  );
};

function App() {
  // 会话身份：任何变更（本标签事件、跨标签 storage、pageshow、focus）先失效再异步复验。
  // 原始身份没有变化时只跳过重建，不做多余作用域重置。
  useEffect(() => {
    void revalidateAuthSession();
    return subscribeAuthChange(() => {
      if (!isAuthIdentityStale()) return;
      invalidateAuthSession();
      void revalidateAuthSession();
    });
  }, []);

  // 立即设置页面标题（使用已从缓存读取的配置）
  useEffect(() => {
    document.title = siteConfig.name;
    
    // 异步检查是否有配置更新
    const checkTitleUpdate = async () => {
      try {
        // 引入必要的函数
        const { getCachedConfig } = await import('@/config/site');
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName && cachedAppName !== document.title) {
          document.title = cachedAppName;
        }
      } catch (error) {
        console.warn('检查标题更新失败:', error);
      }
    };

    // 延迟检查，避免阻塞初始渲染
    const timer = setTimeout(checkTitleUpdate, 100);

    return () => clearTimeout(timer);
  }, []);

  return (
    <>
    <ReauthenticationNotice />
    <Routes>
      <Route path="/" element={<LoginRoute />} />
      <Route 
        path="/change-password" 
        element={
          <ProtectedRoute skipLayout={true}>
            <ChangePasswordPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/dashboard" 
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/forward" 
        element={
          <ProtectedRoute>
            <ForwardPage />
          </ProtectedRoute>
        } 
      />
      <Route path="/ddns" element={<ProtectedRoute><DdnsManagement /></ProtectedRoute>} />
      <Route 
        path="/tunnel" 
        element={
          <ProtectedRoute>
            <TunnelPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/node" 
        element={
          <ProtectedRoute>
            <NodePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/user" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <UserPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/profile" 
        element={
          <ProtectedRoute>
            <ProfilePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/limit" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <LimitPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/config" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <ConfigPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/settings" 
        element={<SettingsPage />}
      />
    </Routes>
    </>
  );
}

export default App;
