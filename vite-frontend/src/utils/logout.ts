import { clearLocalSessionForLogout, readAuthSnapshot, sendLogoutForToken } from '@/api/network';
import type { LocalLogoutResult } from '@/api/network';

/** 本地提交完成后，以操作开始时捕获的旧 token 尽力注销；服务端结果未确认。 */
export const safeLogout = (expected?: ReturnType<typeof readAuthSnapshot>): Promise<LocalLogoutResult> => {
  const started = expected ?? readAuthSnapshot();
  return clearLocalSessionForLogout(() => {
    if (started.token) sendLogoutForToken(started.token);
  }, started);
};
