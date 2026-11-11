package com.admin.ddns;

import java.util.List;

/** 解析目标域名的当前地址；失败必须抛异常，不能返回空列表冒充“没有地址”。 */
public interface DdnsResolver {

    List<String> resolve(String domain) throws Exception;
}
