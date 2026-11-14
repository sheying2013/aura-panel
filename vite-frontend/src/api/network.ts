import axios, { AxiosResponse } from 'axios';
import { getPanelAddresses, isWebViewFunc} from '@/utils/panel';
import { shouldInvalidateSessionToken } from '@/pages/nodeHealthView';


interface PanelAddress {
  name: string;
  address: string;   
  inx: boolean;
}

const setPanelAddressesFunc = (newAddress: PanelAddress[]) => {
  newAddress.forEach(item => {
    if (item.inx) {
      baseURL = `${item.address}/api/v1/`;
      axios.defaults.baseURL = baseURL;
    }
  });
}

function getWebViewPanelAddress() {
  (window as any).setAddresses = setPanelAddressesFunc
  getPanelAddresses("setAddresses");
};

let baseURL: string = '';

export const reinitializeBaseURL = () => {
  if (isWebViewFunc()) {
    getWebViewPanelAddress();
  } else {
    baseURL = import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/';
    axios.defaults.baseURL = baseURL;
  }
};

reinitializeBaseURL();


interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
}

// 处理token失效的逻辑
function handleTokenExpired() {
  // 清除localStorage中的token
  window.localStorage.removeItem('token');
  window.localStorage.removeItem('role_id');
  window.localStorage.removeItem('name');
  
  // 跳转到登录页面
  if (window.location.pathname !== '/') {
    window.location.href = '/';
  }
}

// 检查响应是否为token失效
// round51：服务端现在会因「改密/登出/账号停用」返回 401，且文案不止三条；
// 只按固定文案匹配会让这些失效场景留在页面上反复失败，因此统一按 401 处理。
function isTokenExpired(response: ApiResponse) {
  return !!response && response.code === 401;
}

/** 可取消的只读请求选项：卸载/超时时真正中断底层的 XHR，不再让它挂着 */
interface CancellableRequestOptions {
  signal?: AbortSignal;
}

/** 读取被取消：给调用方一个明确的终态，不让 Promise 停在 pending */
const CANCELED_CODE = -2;

const canceledResult = <T,>(): ApiResponse<T> => ({
  code: CANCELED_CODE,
  msg: '读取已取消',
  data: null as T
});

/** 请求身份已变化：丢弃旧响应，不修改当前会话。 */
const staleIdentityResult = <T,>(): ApiResponse<T> => ({
  code: -3,
  msg: '请求身份已变化，请重新读取',
  data: null as T
});

/**
 * 401 的终态处理。
 *
 * <p>旧实现遇到 401 只清本地会话就 return，Promise 永远不 resolve —— 调用方停在 loading，
 * 看门狗也无从结束。这里一律给出明确结果。
 *
 * <p>只有「本次请求携带的 token 仍是当前 token」才清会话：卸载前发出、重新登录后才回来的
 * 旧请求带的是已经作废的 token，它的 401 说明的是那次旧请求的凭据失效，不能把新会话清掉。
 */
function tokenExpiredResult<T>(usedToken: string | null, msg?: string): ApiResponse<T> {
  if (shouldInvalidateSessionToken(usedToken, window.localStorage.getItem('token'))) {
    handleTokenExpired();
  }
  return {
    code: 401,
    msg: msg || '登录已失效，请重新登录',
    data: null as T
  };
}

function isCanceledRequest(error: any): boolean {
  return axios.isCancel(error) || error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError';
}

const Network = {
  get: function<T = any>(path: string = '', data: any = {}): Promise<ApiResponse<T>> {
    return new Promise(function(resolve) {
      // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
      if (baseURL === '') {
        resolve({"code": -1, "msg": " - 请先设置面板地址", "data": null as T});
        return;
      }

      axios.get(path, {
        params: data,
        timeout: 30000,
        headers: {
          "Authorization": window.localStorage.getItem('token')
        }
      })
        .then(function(response: AxiosResponse<ApiResponse<T>>) {
          // 检查是否token失效
          if (isTokenExpired(response.data)) {
            handleTokenExpired();
            return;
          }
          resolve(response.data);
        })
                 .catch(function(error: any) {
           console.error('GET请求错误:', error);
           
           // 检查是否是401错误（token失效）
           if (error.response && error.response.status === 401) {
             handleTokenExpired();
             return;
           }

           // 服务端返回非2xx但带有业务错误信息时，优先使用服务端的明确提示
           if (error.response && error.response.data && typeof error.response.data === 'object') {
             const respData = error.response.data;
             if (respData.msg) {
               resolve(respData);
               return;
             }
           }
           
           resolve({"code": -1, "msg": error.message || "网络请求失败", "data": null as T});
         });
    });
  },

  post: function<T = any>(path: string = '', data: any = {}): Promise<ApiResponse<T>> {
    return new Promise(function(resolve) {
      // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
      if (baseURL === '') {
        resolve({"code": -1, "msg": " - 请先设置面板地址", "data": null as T});
        return;
      }

      axios.post(path, data, {
        timeout: 30000,
        headers: {
          "Authorization": window.localStorage.getItem('token'),
          "Content-Type": "application/json"
        }
      })
        .then(function(response: AxiosResponse<ApiResponse<T>>) {
          // 检查是否token失效
          if (isTokenExpired(response.data)) {
            handleTokenExpired();
            return;
          }
          resolve(response.data);
        })
                 .catch(function(error: any) {
           console.error('POST请求错误:', error);
           
           // 检查是否是401错误（token失效）
           if (error.response && error.response.status === 401) {
             handleTokenExpired();
             return;
           }

           // 服务端返回非2xx但带有业务错误信息时，优先使用服务端的明确提示
           if (error.response && error.response.data && typeof error.response.data === 'object') {
             const respData = error.response.data;
             if (respData.msg) {
               resolve(respData);
               return;
             }
           }
           
           resolve({"code": -1, "msg": error.message || "网络请求失败", "data": null as T});
         });
    });
  },

  /**
   * 带取消信号的只读 POST：只给「可能被卸载/超时打断」的只读视图用（节点健康摘要）。
   *
   * <p>与 post 的差别只有三点：signal 能真正中断请求；401/403 一定给出明确结果，
   * 绝不让 Promise 停在 pending；401 只在「请求携带的 token 仍是当前 token」时才清会话，
   * 迟到的旧请求不能把新登录清掉。
   */
  postCancellable: function<T = any>(path: string = '', data: any = {}, options: CancellableRequestOptions = {}): Promise<ApiResponse<T>> {
    // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
    if (baseURL === '') {
      return Promise.resolve({"code": -1, "msg": " - 请先设置面板地址", "data": null as T});
    }

    // 发请求时携带的 token 留个底：迟到的 401 要跟它比对，才知道该不该清会话
    const usedToken = window.localStorage.getItem('token');

    return axios.post(path, data, {
      timeout: 30000,
      signal: options.signal,
      headers: {
        "Authorization": usedToken
      }
    })
      .then(function(response: AxiosResponse<ApiResponse<T>>) {
        if (usedToken !== window.localStorage.getItem('token')) return staleIdentityResult<T>();
        if (options.signal?.aborted) return canceledResult<T>();
        if (response.status && (response.status < 200 || response.status >= 300)) {
          if (response.status === 401) return tokenExpiredResult<T>(usedToken, response.data?.msg);
          if (response.status === 403) return { code: 403, msg: response.data?.msg || '当前账号无该项查看权限', data: null as T };
          return { code: -1, msg: response.data?.msg || `HTTP ${response.status}`, data: null as T };
        }
        if (isTokenExpired(response.data)) {
          return tokenExpiredResult<T>(usedToken, response.data?.msg);
        }
        return response.data;
      })
      .catch(function(error: any) {
        if (usedToken !== window.localStorage.getItem('token')) return staleIdentityResult<T>();
        if (options.signal?.aborted || isCanceledRequest(error)) {
          return canceledResult<T>();
        }

        const status = error?.response?.status;
        const respData = error?.response?.data;
        const respMsg = respData && typeof respData === 'object' ? respData.msg : undefined;

        if (status === 401) {
          return tokenExpiredResult<T>(usedToken, respMsg);
        }
        if (status === 403) {
          return {
            code: 403,
            msg: respMsg || '当前账号无该项查看权限',
            data: null as T
          };
        }
        // HTTP 非 2xx 即使响应体伪称 code:0 也必须作为失败处理。
        return {"code": -1, "msg": respMsg || error.message || "网络请求失败", "data": null as T};
      });
  }
};

export default Network; 