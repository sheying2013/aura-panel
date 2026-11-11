package com.admin.common.nodehealth;

/**
 * 健康视图读取数据源失败。
 *
 * <p>存在的意义只有一个：把「数据库/服务层异常」显式抛给调用方，而不是在内部吞成空列表。
 * 空列表会被界面解释成「没有节点/没有隧道」，与「查不出来」在语义上完全相反——前者是正常状态，
 * 后者是故障。任何隐藏这种失败的实现都等于把故障伪装成成功。
 *
 * <p>由 {@code GlobalExceptionHandler} 兜底转成 {@code code=-2} 的业务错误响应。
 */
public class NodeHealthDataException extends RuntimeException {

    public NodeHealthDataException(String message) {
        super(message);
    }

    public NodeHealthDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
