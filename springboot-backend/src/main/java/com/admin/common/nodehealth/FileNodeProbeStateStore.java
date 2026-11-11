package com.admin.common.nodehealth;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把探测节奏账本写到磁盘，供进程重启后继续节流。
 *
 * <p>两个刻意的设计：
 * ① 写盘走「同目录临时文件 + 原子改名」，避免读到半截 JSON；
 * ② 读取失败（无权限、是目录、内容畸形、字段缺失）一律 <b>fail-closed</b>：返回带
 * {@link #LOAD_ERROR_PREFIX} 标记的状态，绝不当成「从未探测」。调用方必须据此跳过本轮，
 * 直到文件恢复可读——把「读不出来」当成「没测过」会让每次启动都对节点重复打点。
 *
 * <p>只有 {@link NoSuchFileException}（文件确实不存在）才等于「从未探测」；新装机器因此不必白等一个间隔。
 *
 * <p><b>字段演进</b>：{@code lastRound} 是后来加上的可选字段，{@link #SCHEMA_VERSION} 保持 1。
 * 老账本没有它 → 读成 {@code null}（没有上一轮摘要，不补造）；老程序读新账本 → 忽略这个未知字段，
 * 节流照常。轮次摘要本身是「软失败」：块坏掉只丢摘要，绝不因此把整份账本判成不可读。
 *
 * <p><b>多 JVM 限制</b>：这里只有单文件 + 原子改名，没有跨进程锁。两个进程共用同一个状态文件时，
 * 可能同时读到同一个 {@code lastAttemptAt} 并各跑一轮，也互不感知对方的预占。节流只在单进程内是硬保证，
 * 跨进程只是「尽最大努力」；要严格排他需要外部单实例部署或文件锁，本类不提供这种承诺。
 *
 * <p>文件位置由 {@code flux.node-probe.state-file} 指定，默认与数据库同目录的
 * {@code node-probe-state.json}。
 */
@Slf4j
public class FileNodeProbeStateStore implements NodeProbeStateStore {

    static final int SCHEMA_VERSION = 1;

    /** 结论集的条数上限：账本只保存「最近一轮」，超出这个量说明文件被写坏了。 */
    static final int MAX_RESULTS = 4096;

    /** 单条 detail 落盘时的长度上限，避免一条异常信息把账本撑大。 */
    static final int MAX_DETAIL_LENGTH = 512;

    /** 读取失败时写在 {@code lastTrigger} 的标记，供调用方识别账本不可信。 */
    static final String LOAD_ERROR_PREFIX = "state-load-error: ";

    private final Path file;

    public FileNodeProbeStateStore(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("节点探测状态文件路径不能为空");
        }
        this.file = file.toAbsolutePath();
    }

    public Path file() {
        return file;
    }

    /** 该状态是否代表「账本读不出来」，而不是一份可用的节流记录。 */
    static boolean isLoadError(NodeProbeState state) {
        return state != null && state.lastTrigger() != null
                && state.lastTrigger().startsWith(LOAD_ERROR_PREFIX);
    }

    /** 读取失败的可读原因；非失败状态返回 null。 */
    static String loadErrorMessage(NodeProbeState state) {
        if (!isLoadError(state)) {
            return null;
        }
        return state.lastTrigger().substring(LOAD_ERROR_PREFIX.length());
    }

    @Override
    public NodeProbeState load() {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            // 文件确实不存在 = 从来没写过账本，可以立刻探测
            return NodeProbeState.empty();
        } catch (IOException | RuntimeException e) {
            return loadFailure(e);
        }
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return loadFailure(e);
        }
    }

    private static NodeProbeState parse(String text) {
        JSONObject json = JSON.parseObject(text);
        if (json == null) {
            throw new IllegalArgumentException("状态文件内容为空");
        }
        Object version = json.get("schemaVersion");
        if (version != null
                && (!(version instanceof Number) || ((Number) version).doubleValue() != SCHEMA_VERSION)) {
            throw new IllegalArgumentException("不支持的 schemaVersion：" + version);
        }
        long lastAttemptAt = requiredLong(json, "lastAttemptAt");
        long lastCompletedAt = requiredLong(json, "lastCompletedAt");
        Object trigger = json.get("lastTrigger");
        if (trigger != null && !(trigger instanceof String)) {
            throw new IllegalArgumentException("lastTrigger 不是字符串");
        }
        return new NodeProbeState(lastAttemptAt, lastCompletedAt, (String) trigger,
                parseResults(json.get("results")), parseRound(json.get("lastRound")));
    }

    /**
     * 字段必须存在且是数字：空对象 {@code {}}、缺字段、字符串数字都不能被读成 0——
     * 那等于把「账本畸形」伪装成「从未探测」。负值同样不可信。
     */
    private static long requiredLong(JSONObject json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("字段缺失或类型不是数字：" + key);
        }
        long parsed = ((Number) value).longValue();
        if (parsed < 0L) {
            throw new IllegalArgumentException("字段为负值：" + key + "=" + parsed);
        }
        return parsed;
    }

    /**
     * 解析结论集。刻意「软失败」：单条记录类型不对就丢掉那一条，整块不是数组就当空集，
     * <b>绝不</b>因此把整份账本判成不可读——节流记录仍然有效，而结论丢了只会回到 UNKNOWN，
     * 这是安全的一侧。反过来（因为结论畸形就拒绝启动探测）等于让一个坏字段停掉整条巡检。
     */
    private static List<NodeProbeEntry> parseResults(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof JSONArray)) {
            log.warn("节点探测账本里的 results 不是数组，忽略该字段（节流记录照常使用）");
            return List.of();
        }
        List<NodeProbeEntry> entries = new ArrayList<>();
        for (Object item : (JSONArray) raw) {
            NodeProbeEntry entry = parseEntry(item);
            if (entry == null) {
                continue;
            }
            entries.add(entry);
            if (entries.size() >= MAX_RESULTS) {
                log.warn("节点探测账本里的结论条数超过 {}，其余条目忽略", MAX_RESULTS);
                break;
            }
        }
        return entries;
    }

    private static NodeProbeEntry parseEntry(Object item) {
        if (!(item instanceof JSONObject)) {
            return null;
        }
        JSONObject json = (JSONObject) item;
        Object nodeIdValue = json.get("nodeId");
        if (!(nodeIdValue instanceof Number) || ((Number) nodeIdValue).longValue() <= 0L) {
            return null;
        }
        Object statusValue = json.get("status");
        if (!(statusValue instanceof String)) {
            return null;
        }
        NodeProbeStatus status = NodeProbeStatus.fromCode((String) statusValue);
        if (!status.code().equals(statusValue)) {
            // 未知结论一律不认：fromCode 会把陌生 code 兜成 UNKNOWN，这里要的是「这一条不可信」
            return null;
        }
        Object checkedAtValue = json.get("checkedAt");
        if (!(checkedAtValue instanceof Number) || ((Number) checkedAtValue).longValue() < 0L) {
            return null;
        }
        Object latencyValue = json.get("latencyMs");
        long latencyMs = latencyValue instanceof Number ? ((Number) latencyValue).longValue() : -1L;
        Object detailValue = json.get("detail");
        String detail = detailValue instanceof String ? truncate((String) detailValue) : null;
        Object identityValue = json.get("nodeIdentity");
        String identity = identityValue instanceof String ? (String) identityValue : null;
        return new NodeProbeEntry(((Number) nodeIdValue).longValue(), identity,
                new NodeProbeResult(status, ((Number) checkedAtValue).longValue(), latencyMs, detail));
    }

    private static String truncate(String detail) {
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
    }

    /**
     * 解析上一轮摘要，和结论集一样「软失败」：字段坏掉就当作没有摘要（返回 null），
     * 不把整份账本判成不可读——节流记录仍然可信，少一行历史不影响巡检。
     *
     * <p>只接受「完整跑完的一轮」的形态：计数齐全且四项之和等于 total、起止时间有序、
     * {@code interrupted} 必须明确为 false。被中断的轮次本来就不该以「已完成」的样子落盘，
     * 出现了就是这块数据不可信，宁可不显示。
     */
    private static NodeProbeService.ProbeRound parseRound(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof JSONObject)) {
            log.warn("节点探测账本里的 lastRound 不是对象，忽略该字段（节流记录照常使用）");
            return null;
        }
        JSONObject json = (JSONObject) raw;
        Object trigger = json.get("trigger");
        if (!(trigger instanceof String) || ((String) trigger).isBlank()) {
            return null;
        }
        Long startedAt = nonNegative(json.get("startedAt"));
        Long finishedAt = nonNegative(json.get("finishedAt"));
        if (startedAt == null || finishedAt == null || startedAt <= 0L || startedAt > finishedAt) {
            return null;
        }
        Long total = nonNegative(json.get("total"));
        Long online = nonNegative(json.get("online"));
        Long degraded = nonNegative(json.get("degraded"));
        Long offline = nonNegative(json.get("offline"));
        Long unknown = nonNegative(json.get("unknown"));
        if (total == null || online == null || degraded == null || offline == null || unknown == null
                || total > Integer.MAX_VALUE || online + degraded + offline + unknown != total) {
            return null;
        }
        if (!Boolean.FALSE.equals(json.get("interrupted"))) {
            return null;
        }
        return new NodeProbeService.ProbeRound((String) trigger, startedAt, finishedAt,
                total.intValue(), online.intValue(), degraded.intValue(), offline.intValue(),
                unknown.intValue(), false);
    }

    /** 非负整数才认；缺失、类型不对、负数、带小数的值一律返回 null。 */
    private static Long nonNegative(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        long parsed = number.longValue();
        if (parsed < 0L || number.doubleValue() != (double) parsed) {
            return null;
        }
        return parsed;
    }

    private static JSONObject toJsonRound(NodeProbeService.ProbeRound round) {
        if (round == null) {
            return null;
        }
        JSONObject json = new JSONObject();
        json.put("trigger", round.trigger());
        json.put("startedAt", round.startedAt());
        json.put("finishedAt", round.finishedAt());
        json.put("total", round.total());
        json.put("online", round.online());
        json.put("degraded", round.degraded());
        json.put("offline", round.offline());
        json.put("unknown", round.unknown());
        json.put("interrupted", round.interrupted());
        return json;
    }

    private static JSONArray toJsonArray(List<NodeProbeEntry> entries) {
        JSONArray array = new JSONArray();
        if (entries == null) {
            return array;
        }
        for (NodeProbeEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("nodeId", entry.nodeId());
            item.put("nodeIdentity", entry.nodeIdentity());
            item.put("status", entry.result().status().code());
            item.put("checkedAt", entry.result().checkedAt());
            item.put("latencyMs", entry.result().latencyMs());
            item.put("detail", entry.result().detail());
            array.add(item);
        }
        return array;
    }

    private NodeProbeState loadFailure(Exception e) {
        long fallback = lastModified();
        if (fallback <= 0L) {
            // 连 mtime 都拿不到时用当前时间兜底：0 会被当成「从未探测」，是 fail-open
            fallback = System.currentTimeMillis();
        }
        String detail = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
        String message = file + "（" + detail + "）";
        log.error("节点探测状态文件不可读，按 fail-closed 处理（保守时间 {}）：{}", fallback, message);
        return new NodeProbeState(fallback, 0L, LOAD_ERROR_PREFIX + message);
    }

    @Override
    public void save(NodeProbeState state) {
        if (state == null) {
            throw new NodeProbeStateException("节点探测状态不能为 null");
        }
        Path temp = null;
        try {
            Path parent = file.getParent();
            Path dir = parent == null ? Paths.get(".").toAbsolutePath() : parent;
            Files.createDirectories(dir);
            // 每次写用独立临时文件：并发写共享同一个 .tmp，先到的那份会被后来的写截断或搬走
            temp = Files.createTempFile(dir, file.getFileName().toString() + ".", ".tmp");
            JSONObject json = new JSONObject();
            json.put("schemaVersion", SCHEMA_VERSION);
            json.put("lastAttemptAt", state.lastAttemptAt());
            json.put("lastCompletedAt", state.lastCompletedAt());
            json.put("lastTrigger", state.lastTrigger());
            json.put("results", toJsonArray(state.results()));
            json.put("lastRound", toJsonRound(state.lastRound()));
            Files.writeString(temp, json.toJSONString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException | RuntimeException e) {
            throw new NodeProbeStateException("写入节点探测状态文件失败：" + file + "（" + e.getMessage() + "）", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 残留的临时文件不影响读取：正式文件永远是原子改名后的完整内容
                }
            }
        }
    }

    private long lastModified() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }
}
