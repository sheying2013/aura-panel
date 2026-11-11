package com.admin.ddns.service;

import com.admin.common.lang.R;
import com.admin.ddns.DdnsAddressRegistry;
import com.admin.ddns.DdnsForwardApplier;
import com.admin.ddns.DdnsResolver;
import com.admin.ddns.DdnsTarget;
import com.admin.ddns.DdnsTargetRenderer;
import com.admin.ddns.dto.DdnsCreateRequest;
import com.admin.ddns.dto.DdnsUpdateRequest;
import com.admin.ddns.entity.DdnsRecord;
import com.admin.ddns.mapper.DdnsRecordMapper;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「同一条转发上有多个域名 + 渲染映射未就绪」的确定性回归（全 mock，不解析真实 DNS、不连节点）。
 *
 * <p>现场：{@code forward.remoteAddr = app.example.com:8080,backup.example.com:9090}，两个域名都归
 * DDNS 管，节点上当前生效的目标是 {@code 1.1.1.1:8080,9.9.9.9:9090}。stage 只给本次切换的域名显式
 * 指定新地址，另一个域名要靠注册表渲染；注册表没装载好（进程刚起 / 读库失败）时它会被渲染回域名
 * 本身 —— 节点上还能用的 9.9.9.9 被改掉，等映射恢复后对账又把这次改动判成漂移、删了重建。
 *
 * <p>修复后的口径：下发前先尝试把映射装好（装好就按已确认地址渲染），装不好就 fail-closed ——
 * 一个节点都不改、记录与主地址保留、lastError 如实说明原因，映射恢复就绪后重试即可。
 *
 * <p>同时锁住「不误伤」：转发上没有别的域名、或兄弟域名压根不归 DDNS 管时，照常下发。
 */
class DdnsSiblingRenderFailClosedTest {

    private static final String APP = "app.example.com";
    private static final String BACKUP = "backup.example.com";
    private static final String TWO_DOMAINS = APP + ":8080," + BACKUP + ":9090";
    /** 节点上当前真实生效的目标：上一轮按「已确认地址」渲染出来的结果。 */
    private static final String NODE_BEFORE = "1.1.1.1:8080,9.9.9.9:9090";
    private static final String SERVICE = "21_1_5";
    private static final long RECORD_ID = 7L;
    private static final long CREATED_ID = 42L;

    private DdnsRecordMapper mapper;
    private DdnsAddressRegistry registry;
    private DdnsResolver resolver;
    private DdnsForwardApplier applier;
    private ForwardService forwardService;
    private TunnelService tunnelService;
    private ChainTunnelService chainTunnelService;
    private NodeService nodeService;
    private ForwardPortService forwardPortService;
    private UserTunnelService userTunnelService;
    private DdnsService service;

    /** 库里的行（updateById/insert 直接落在这里，便于断言「记录还在、主地址没被改」）。 */
    private final Map<Long, DdnsRecord> rows = new LinkedHashMap<>();
    /** 整表装载看到的内容：模拟 ddns_record 全表。 */
    private List<DdnsRecord> table = List.of();
    /** 非空表示这次读库失败（模拟库连不上 / 启动窗口内表还没就绪）。 */
    private RuntimeException loadFailure;
    /** 真正写进节点的目标列表，按 serviceName@payload 记录。 */
    private final List<String> pushes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(DdnsRecordMapper.class);
        registry = new DdnsAddressRegistry();
        resolver = mock(DdnsResolver.class);
        applier = mock(DdnsForwardApplier.class);
        forwardService = mock(ForwardService.class);
        tunnelService = mock(TunnelService.class);
        chainTunnelService = mock(ChainTunnelService.class);
        nodeService = mock(NodeService.class);
        forwardPortService = mock(ForwardPortService.class);
        userTunnelService = mock(UserTunnelService.class);
        UserService userService = mock(UserService.class);
        service = new DdnsService(mapper, registry, resolver, applier, forwardService,
                tunnelService, chainTunnelService, nodeService, forwardPortService, userTunnelService,
                userService);

        when(mapper.selectById(any())).thenAnswer(inv -> rows.get(((Number) inv.getArgument(0)).longValue()));
        when(mapper.updateById(any(DdnsRecord.class))).thenAnswer(inv -> {
            DdnsRecord incoming = inv.getArgument(0);
            rows.put(incoming.getId(), incoming);
            return 1;
        });
        when(mapper.insert(any(DdnsRecord.class))).thenAnswer(inv -> {
            DdnsRecord incoming = inv.getArgument(0);
            incoming.setId(CREATED_ID);
            rows.put(CREATED_ID, incoming);
            return 1;
        });
        when(mapper.deleteById(any())).thenAnswer(inv -> {
            rows.remove(((Number) inv.getArgument(0)).longValue());
            return 1;
        });
        // loadRegistry() 传 null 走整表装载；创建时的域名查重带 wrapper，按「不重复」处理
        when(mapper.selectList(any())).thenAnswer(inv -> {
            if (inv.getArgument(0) != null) {
                return List.of();
            }
            if (loadFailure != null) {
                throw loadFailure;
            }
            return table;
        });
        when(applier.isOnline(anyLong())).thenReturn(true);
        // 这里记录的就是真正写进 forwarder.nodes[].addr 的地址
        when(applier.update(any(DdnsTarget.class))).thenAnswer(inv -> {
            DdnsTarget target = inv.getArgument(0);
            pushes.add(target.serviceName() + "@" + String.join(",", target.renderedTargets()));
            return "OK";
        });
        when(userService.getById(any(Integer.class))).thenReturn(user(1));
    }

    @AfterEach
    void tearDown() {
        DdnsTargetRenderer.reset();
    }

    @Test
    @DisplayName("手动 checkNow：映射能装好时，同一条转发上的另一个 DDNS 域名必须保持已确认地址")
    void checkNowKeepsSiblingAddressWhenRegistryBecomesReady() throws Exception {
        seedRecord("1.1.1.1");
        table = List.of(record(APP, "1.1.1.1"), record(BACKUP, "9.9.9.9"));
        oneForwardWithOneEntryNode(TWO_DOMAINS);
        when(resolver.resolve(APP)).thenReturn(List.of("2.2.2.2"));

        R response = service.checkNow(RECORD_ID);

        assertEquals(0, response.getCode(), response.getMsg());
        assertEquals(List.of(SERVICE + "@2.2.2.2:8080,9.9.9.9:9090"), pushes,
                "backup.example.com 必须继续用已确认的 9.9.9.9 下发，不能被渲染回域名（节点原本是 "
                        + NODE_BEFORE + "）；实际下发=" + pushes);
        assertTrue(registry.isReady(), "下发前应已把映射装好");
        assertEquals("2.2.2.2", rows.get(RECORD_ID).getPrimaryIp());
    }

    @Test
    @DisplayName("手动 checkNow：映射装不好时必须 fail-closed —— 零下发、主地址不动、如实报错")
    void checkNowFailsClosedWhenRegistryCannotLoad() throws Exception {
        seedRecord("1.1.1.1");
        loadFailure = new IllegalStateException("CommunicationsException: 连不上库");
        oneForwardWithOneEntryNode(TWO_DOMAINS);
        when(resolver.resolve(APP)).thenReturn(List.of("2.2.2.2"));

        R response = service.checkNow(RECORD_ID);

        assertNotEquals(0, response.getCode(), "装不好映射却报成功，等于把 backup 的 IP 静默改回域名");
        assertTrue(pushes.isEmpty(), "一个节点都不能改；实际下发=" + pushes + "（节点原本是 " + NODE_BEFORE + "）");
        assertTrue(response.getMsg().contains("未就绪") || response.getMsg().contains("装载"),
                "必须说明是渲染映射没就绪：" + response.getMsg());
        assertTrue(response.getMsg().contains("未改动任何节点"), response.getMsg());
        assertTrue(response.getMsg().contains("重试"), "要能说清楚恢复后可重试：" + response.getMsg());
        assertEquals("1.1.1.1", rows.get(RECORD_ID).getPrimaryIp(), "失败不得写主地址");
        assertTrue(rows.get(RECORD_ID).getLastError().contains(BACKUP),
                "lastError 要指出是哪个兄弟域名没法确认：" + rows.get(RECORD_ID).getLastError());
        assertNull(registry.get(APP), "失败不得往注册表写新值");
    }

    @Test
    @DisplayName("创建即检查：映射装不好时记录照常创建，但零下发并把原因留在 lastError")
    void createKeepsRecordButDoesNotPushWhenRegistryCannotLoad() throws Exception {
        loadFailure = new IllegalStateException("CommunicationsException: 连不上库");
        oneForwardWithOneEntryNode(TWO_DOMAINS);
        when(resolver.resolve(APP)).thenReturn(List.of("2.2.2.2"));

        DdnsCreateRequest request = new DdnsCreateRequest();
        request.setDomain(APP);
        request.setIntervalSeconds(60);
        request.setEnabled(1);
        R response = service.create(request);

        assertEquals(0, response.getCode(), "记录本身要建成功：" + response.getMsg());
        DdnsRecord saved = rows.get(CREATED_ID);
        assertNotNull(saved, "记录必须保留");
        assertTrue(pushes.isEmpty(), "一个节点都不能改；实际下发=" + pushes);
        assertTrue(saved.getLastError().contains(BACKUP),
                "创建接口靠 lastError 明确告知用户：" + saved.getLastError());
        assertTrue(saved.getPrimaryIp() == null || saved.getPrimaryIp().isBlank(),
                "没下发成功就不能写主地址：" + saved.getPrimaryIp());
    }

    @Test
    @DisplayName("update 触发检查：映射装不好时零下发，响应里的 lastError 说明原因")
    void updateTriggeredCheckFailsClosedWhenRegistryCannotLoad() throws Exception {
        seedRecord("");
        loadFailure = new IllegalStateException("CommunicationsException: 连不上库");
        oneForwardWithOneEntryNode(TWO_DOMAINS);
        when(resolver.resolve(APP)).thenReturn(List.of("2.2.2.2"));

        DdnsUpdateRequest request = new DdnsUpdateRequest();
        request.setId(RECORD_ID);
        request.setEnabled(1);
        R response = service.update(request);

        assertEquals(0, response.getCode(), "改配置本身要成功：" + response.getMsg());
        assertTrue(pushes.isEmpty(), "一个节点都不能改；实际下发=" + pushes);
        DdnsRecord shown = (DdnsRecord) response.getData();
        assertTrue(shown != null && shown.getLastError().contains(BACKUP),
                "响应里要带出原因：" + (shown == null ? "null" : shown.getLastError()));
    }

    @Test
    @DisplayName("delete：映射装不好时保留记录、不删库、不把兄弟域名渲染回域名")
    void deleteKeepsRecordWhenRegistryCannotLoad() throws Exception {
        seedRecord("1.1.1.1");
        loadFailure = new IllegalStateException("CommunicationsException: 连不上库");
        oneForwardWithOneEntryNode(TWO_DOMAINS);

        R response = service.delete(RECORD_ID);

        assertNotEquals(0, response.getCode(), "回落不了还删记录，节点就永久停在域名上：");
        assertTrue(pushes.isEmpty(), "一个节点都不能改；实际下发=" + pushes);
        verify(mapper, never()).deleteById(any());
        assertNotNull(rows.get(RECORD_ID), "记录必须保留");
        assertTrue(response.getMsg().contains("已保留记录"), response.getMsg());
        assertTrue(response.getMsg().contains(BACKUP), "要指出是哪个域名没法确认：" + response.getMsg());
    }

    @Test
    @DisplayName("不误伤：转发里只有本域名时，映射没就绪也照常下发")
    void singleDomainForwardStillAppliesWithoutRegistry() throws Exception {
        seedRecord("1.1.1.1");
        loadFailure = new IllegalStateException("CommunicationsException: 连不上库");
        oneForwardWithOneEntryNode(APP + ":8080");
        when(resolver.resolve(APP)).thenReturn(List.of("2.2.2.2"));

        R response = service.checkNow(RECORD_ID);

        assertEquals(0, response.getCode(), response.getMsg());
        assertEquals(List.of(SERVICE + "@2.2.2.2:8080"), pushes,
                "没有兄弟域名就没有可降级的东西，不该被 fail-closed 挡住：" + pushes);
        assertEquals("2.2.2.2", rows.get(RECORD_ID).getPrimaryIp());
    }

    @Test
    @DisplayName("不误伤：兄弟域名不归 DDNS 管时，原样保留它自己的域名")
    void nonDdnsSiblingIsRenderedAsIs() throws Exception {
        seedRecord("1.1.1.1");
        table = List.of(record(APP, "1.1.1.1"));
        oneForwardWithOneEntryNode(TWO_DOMAINS);
        when(resolver.resolve(APP)).thenReturn(List.of("2.2.2.2"));

        R response = service.checkNow(RECORD_ID);

        assertEquals(0, response.getCode(), response.getMsg());
        assertEquals(List.of(SERVICE + "@2.2.2.2:8080," + BACKUP + ":9090"), pushes,
                "外部域名本来就该原样下发：" + pushes);
    }

    // ---------------------------------------------------------------- 夹具

    /** 库里已存在的一条记录，返回同一实例便于断言没被改。 */
    private DdnsRecord seedRecord(String primaryIp) {
        DdnsRecord record = record(APP, primaryIp);
        record.setId(RECORD_ID);
        record.setResolvedIps(primaryIp == null || primaryIp.isBlank() ? "" : primaryIp);
        rows.put(RECORD_ID, record);
        return record;
    }

    private static DdnsRecord record(String domain, String primaryIp) {
        DdnsRecord record = new DdnsRecord();
        record.setDomain(domain);
        record.setPrimaryIp(primaryIp);
        record.setResolvedIps(primaryIp);
        record.setIntervalSeconds(60);
        record.setEnabled(1);
        record.setFailCount(0);
        record.setLastError("");
        return record;
    }

    private void oneForwardWithOneEntryNode(String remoteAddr) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(16L);
        tunnel.setType(1);
        when(tunnelService.getById(16)).thenReturn(tunnel);

        Forward forward = new Forward();
        forward.setId(21L);
        forward.setUserId(1);
        forward.setTunnelId(16);
        forward.setStatus(1);
        forward.setRemoteAddr(remoteAddr);
        forward.setStrategy("round");
        when(forwardService.list(any())).thenReturn(List.of(forward));

        ChainTunnel entry = new ChainTunnel();
        entry.setId(101L);
        entry.setTunnelId(16L);
        entry.setChainType(1);
        entry.setNodeId(1L);
        entry.setPort(30200);
        when(chainTunnelService.list(any())).thenReturn(new ArrayList<>(List.of(entry)));

        Node node = new Node();
        node.setId(1L);
        node.setName("node-1");
        when(nodeService.getById(1L)).thenReturn(node);

        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(5);
        userTunnel.setUserId(1);
        userTunnel.setTunnelId(16);
        userTunnel.setSpeedId(3);
        when(userTunnelService.getOne(any(), eq(false))).thenReturn(userTunnel);

        ForwardPort port = new ForwardPort();
        port.setForwardId(21L);
        port.setNodeId(1L);
        port.setPort(30200);
        when(forwardPortService.getOne(any(), eq(false))).thenReturn(port);
    }

    private static User user(int roleId) {
        User user = new User();
        user.setId(1L);
        user.setRoleId(roleId);
        return user;
    }
}
