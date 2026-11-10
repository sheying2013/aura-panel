package com.admin.compat;

import com.admin.common.utils.GostUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 服务摘要口径（round37 扩展）与监听地址规范化的纯单元回归。
 *
 * <p>为什么要有这一层：摘要串是"节点上报"和"后端按库计算"唯一的对齐方式，
 * 口径一改就是全局行为——写错一个字符，要么永远判漂移把服务反复删了重建，
 * 要么该发现的漂移永远发现不了。所以这里把每条规则逐字钉死。
 */
class GostUtilDigestTest {

    @Test
    @DisplayName("通配监听地址只输出端口：老数据/老节点零抖动")
    void wildcardListenPrintsPortOnly() {
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigestList("0.0.0.0:30200", "tcp", null, List.of()));
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigestList("[::]:30200", "TCP", null, null));
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigestList("*:30200", "tcp", "", List.of(" ")));
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigestList("30200", "tcp", null, List.of()));
    }

    @Test
    @DisplayName("具体监听地址必须带 host：0.0.0.0 改成 127.0.0.1 要判不等")
    void specificListenKeepsHost() {
        assertEquals("127.0.0.1:30200|tcp|-|-",
                GostUtil.normalizeServiceDigestList("127.0.0.1:30200", "tcp", null, List.of()));
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigestList("0.0.0.0:30200", "tcp", null, List.of()));
    }

    @Test
    @DisplayName("IPv6 具体地址保留方括号，且与不带方括号的写法等价")
    void ipv6ListenIsBracketed() {
        assertEquals("[fe80::1]:30200|tcp|-|-",
                GostUtil.normalizeServiceDigestList("[fe80::1]:30200", "tcp", null, List.of()));
        assertEquals("[fe80::1]:30200|tcp|-|-",
                GostUtil.normalizeServiceDigestList("[FE80::1]:30200", "tcp", null, List.of()));
    }

    @Test
    @DisplayName("单目标多参版本与老四参版本逐字一致：升级不能让存量节点集体重建")
    void singleTargetMatchesLegacyFormat() {
        assertEquals("30200|tcp|chains_7|backend:6365",
                GostUtil.normalizeServiceDigest("[::]:30200", "TCP", "chains_7", "backend:6365"));
        assertEquals("30200|tcp|chains_7|backend:6365",
                GostUtil.normalizeServiceDigestList("[::]:30200", "TCP", "chains_7", List.of("backend:6365")));
        // 老版本 target 传 null/空串写 "-"
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigest("[::]:30200", "tcp", null, null));
        assertEquals("30200|tcp|-|-", GostUtil.normalizeServiceDigestList("[::]:30200", "tcp", null, List.of()));
    }

    @Test
    @DisplayName("多目标按顺序逗号连接，顺序不同即不同摘要")
    void multiTargetsAreOrderedAndCommaJoined() {
        assertEquals("30200|tcp|-|a:1,b:2",
                GostUtil.normalizeServiceDigestList("[::]:30200", "tcp", null, List.of("a:1", "b:2")));
        assertEquals("30200|tcp|-|b:2,a:1",
                GostUtil.normalizeServiceDigestList("[::]:30200", "tcp", null, List.of("b:2", "a:1")));
    }

    @Test
    @DisplayName("目标逐个 trim、丢空，全空写 \"-\"")
    void targetsAreTrimmedAndEmptiesDropped() {
        assertEquals("30200|tcp|-|a:1,b:2",
                GostUtil.normalizeServiceDigestList("[::]:30200", "tcp", null, List.of(" a:1 ", "", "  ", "b:2")));
        assertEquals("30200|tcp|-|-",
                GostUtil.normalizeServiceDigestList("[::]:30200", "tcp", null, List.of("  ", "")));
    }

    @Test
    @DisplayName("canonicalListenAddr：空白兜底 0.0.0.0，裸 IPv6 补方括号，已带括号原样")
    void canonicalListenAddrForms() {
        assertEquals("0.0.0.0", GostUtil.canonicalListenAddr(null));
        assertEquals("0.0.0.0", GostUtil.canonicalListenAddr("  "));
        assertEquals("127.0.0.1", GostUtil.canonicalListenAddr(" 127.0.0.1 "));
        assertEquals("[::]", GostUtil.canonicalListenAddr("::"));
        assertEquals("[fe80::1]", GostUtil.canonicalListenAddr("fe80::1"));
        assertEquals("[::]", GostUtil.canonicalListenAddr("[::]"));
        assertEquals("localhost", GostUtil.canonicalListenAddr("localhost"));
    }

    @Test
    @DisplayName("下发 forwarder 时目标要去空格、丢空项（\"a:1, b:2,\" 不能下发成带空格的目标）")
    void forwarderTargetsAreTrimmed() throws Exception {
        Method createForwarder = GostUtil.class.getDeclaredMethod("createForwarder", String.class, String.class);
        createForwarder.setAccessible(true);
        JSONObject forwarder = (JSONObject) createForwarder.invoke(null, "a:1, b:2,,", "round");
        JSONArray nodes = forwarder.getJSONArray("nodes");
        assertEquals(2, nodes.size(), "空目标不该下发");
        assertEquals("a:1", nodes.getJSONObject(0).getString("addr"));
        assertEquals("b:2", nodes.getJSONObject(1).getString("addr"), "前导空格必须去掉");
    }

    @Test
    @DisplayName("目标全是空白时按原样兜一个：空 nodes 数组会被 gost 拒掉整条服务")
    void forwarderKeepsOneRawTargetWhenAllBlank() throws Exception {
        Method createForwarder = GostUtil.class.getDeclaredMethod("createForwarder", String.class, String.class);
        createForwarder.setAccessible(true);
        JSONObject forwarder = (JSONObject) createForwarder.invoke(null, " ,  ,", "fifo");
        JSONArray nodes = forwarder.getJSONArray("nodes");
        assertEquals(1, nodes.size(), "不能下发空 nodes 数组");
        assertEquals(" ,  ,", nodes.getJSONObject(0).getString("addr"), "兜底值必须是原始串");
    }
}
