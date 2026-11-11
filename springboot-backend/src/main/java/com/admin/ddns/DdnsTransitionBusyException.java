package com.admin.ddns;

/**
 * 某域名正处于「节点尚未确认新地址」的窗口内，此时节点上的真实目标既不是注册表里的旧值、
 * 也还不能算新值；任何按注册表渲染的下发都会把节点改回旧地址，所以必须拒绝。
 */
public class DdnsTransitionBusyException extends IllegalStateException {

    private final String domain;

    public DdnsTransitionBusyException(String domain, String note) {
        super("域名 " + domain + " 正在 DDNS 切换（" + (note == null || note.isEmpty() ? "节点尚未确认新地址" : note)
                + "），已拒绝按旧地址下发；请等该域名确认后再操作");
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }
}
