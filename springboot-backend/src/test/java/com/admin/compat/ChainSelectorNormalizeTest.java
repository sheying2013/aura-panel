package com.admin.compat;

import com.admin.entity.ChainTunnel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 期望 selector 与节点上报口径必须逐字对齐（round36 回归）。
 *
 * <p>节点侧 chainSelectorSegment 会 TrimSpace(strategy)（go-gost/x/socket/websocket_reporter.go:1177-1191），
 * 后端若直接拿库里带前后空格的 strategy 拼期望值，就会永久判漂移：每次上报都 DeleteChains + AddChains，
 * 配合 NodeReconcileTask 的 5 分钟轮询等于反复重建整条中继链。这里把口径钉死。
 */
class ChainSelectorNormalizeTest {

    private static String expectedSelectorOf(String strategy) throws Exception {
        ChainTunnel hop = new ChainTunnel();
        hop.setStrategy(strategy);
        Method m = Class.forName("com.admin.service.impl.ForwardServiceImpl")
                .getDeclaredMethod("expectedChainSelector", List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, List.of(hop));
    }

    @Test
    void surroundingWhitespaceIsTrimmedLikeNodeDoes() throws Exception {
        assertEquals("round|1|600000000000", expectedSelectorOf("  round  "));
        assertEquals("fifo|1|600000000000", expectedSelectorOf("\tfifo\n"));
    }

    @Test
    void blankStrategyFallsBackToDash() throws Exception {
        assertEquals("-|1|600000000000", expectedSelectorOf("   "));
        assertEquals("-|1|600000000000", expectedSelectorOf(null));
    }

    @Test
    void plainStrategyIsUnchanged() throws Exception {
        assertEquals("round|1|600000000000", expectedSelectorOf("round"));
    }
}
