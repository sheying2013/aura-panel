import Network from '@/api/network';

/**
 * 安全退出登录函数
 * 清除登录相关数据，但保留用户偏好设置（如主题）
 *
 * round51：服务端签发的 token 现在带版本号，登出要把它作废，否则本地清掉后
 * 旧 token 仍能用满 90 天。这里 fire-and-forget：网络失败也必须让本地立即退出。
 */
export const safeLogout = () => {
  try {
    Network.post('/user/logout', {}).catch(() => {});
  } catch (e) {
    // 退出登录不能因为网络异常卡住本地清理
  }
  localStorage.clear();
};
