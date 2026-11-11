package com.admin.ddns;

/** 节点下发接缝：生产实现走 GostUtil.WebSocketServer，测试用假实现记录调用顺序。 */
public interface DdnsForwardApplier {

    boolean isOnline(Long nodeId);

    /** 返回节点原始响应；只有 OK 或 OK 开头（OK；...）视为成功。 */
    String update(DdnsTarget target);
}
