package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.ForwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardController extends BaseController {

    /** 参数错误沿用兜底处理器原本的 -2 码，只把 NPE 原始文本换成可读文案，前端只需判 code != 0。 */
    private static final int PARAM_ERROR_CODE = -2;

    /** 转发名：去空格后 1-64 字符（前端输入框同样限制 64）。 */
    private static final int NAME_MAX_LENGTH = 64;

    /** remoteAddr 对应的库字段是 varchar(255)，超长在 MySQL 上会报错或截断。 */
    private static final int REMOTE_ADDR_MAX_LENGTH = 255;

    private static final int PORT_MIN = 1;

    private static final int PORT_MAX = 65535;

    /** 转发类型：1=入口，2=出口。 */
    private static final int TYPE_ENTRY = 1;

    private static final int TYPE_EXIT = 2;

    @Autowired
    private ForwardService forwardService;

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody CreateForwardRequest forwardDto) {
        String paramError = validateForwardParams(forwardDto.getName(), forwardDto.getRemoteAddr(),
                forwardDto.getInPort(), forwardDto.getProtocol(), forwardDto.getType());
        if (paramError != null) {
            return R.err(paramError);
        }
        // protocol 落库口径统一小写；未传时保持 null，沿用节点侧 tcp/udp 各建一份的既有行为
        forwardDto.setProtocol(normalizeProtocol(forwardDto.getProtocol()));
        return forwardService.createForward(forwardDto);
    }

    @LogAnnotation
    @PostMapping("/list")
    public R readAll() {
        return forwardService.getAllForwards();
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody UpdateForwardRequest forwardUpdateDto) {
        String paramError = validateForwardParams(forwardUpdateDto.getName(), forwardUpdateDto.getRemoteAddr(),
                forwardUpdateDto.getInPort(), forwardUpdateDto.getProtocol(), forwardUpdateDto.getType());
        if (paramError != null) {
            return R.err(paramError);
        }
        forwardUpdateDto.setProtocol(normalizeProtocol(forwardUpdateDto.getProtocol()));
        return forwardService.updateForward(forwardUpdateDto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody(required = false) Map<String, Object> params) {
        Long id = parseLongParam(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, paramError(params, "id"));
        }
        return forwardService.deleteForward(id);
    }

    @LogAnnotation
    @PostMapping("/force-delete")
    public R forceDelete(@RequestBody(required = false) Map<String, Object> params) {
        Long id = parseLongParam(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, paramError(params, "id"));
        }
        return forwardService.forceDeleteForward(id);
    }

    @LogAnnotation
    @PostMapping("/pause")
    public R pause(@RequestBody(required = false) Map<String, Object> params) {
        Long id = parseLongParam(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, paramError(params, "id"));
        }
        return forwardService.pauseForward(id);
    }

    @LogAnnotation
    @PostMapping("/resume")
    public R resume(@RequestBody(required = false) Map<String, Object> params) {
        Long id = parseLongParam(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, paramError(params, "id"));
        }
        return forwardService.resumeForward(id);
    }

    /**
     * 转发诊断功能
     * @param params 包含forwardId的参数
     * @return 诊断结果
     */
    @LogAnnotation
    @PostMapping("/diagnose")
    public R diagnoseForward(@RequestBody(required = false) Map<String, Object> params) {
        Long forwardId = parseLongParam(params, "forwardId");
        if (forwardId == null) {
            return R.err(PARAM_ERROR_CODE, paramError(params, "forwardId"));
        }
        return forwardService.diagnoseForward(forwardId);
    }

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数，每个元素包含id和inx
     * @return 更新结果
     */
    @LogAnnotation
    @PostMapping("/update-order")
    public R updateForwardOrder(@RequestBody(required = false) Map<String, Object> params) {
        if (params == null) {
            return R.err(PARAM_ERROR_CODE, "forwards 不能为空");
        }
        return forwardService.updateForwardOrder(params);
    }

    /**
     * Map 取参统一入口：body 为 null、键缺失、值为 null/空白、非数字一律返回 null，
     * 由调用方给可读文案。原先 params.get(x).toString() 缺参就 NPE，
     * 再被 GlobalExceptionHandler 把原始异常文本（Cannot invoke "Object.toString()"...）回给前端。
     */
    private static Long parseLongParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object raw = params.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 与 parseLongParam 配套：区分「没传」和「传了但不是合法 id」，给出可读文案。 */
    private static String paramError(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null || raw.toString().trim().isEmpty()) {
            return key + " 不能为空";
        }
        return key + " 格式不正确";
    }

    /**
     * create / update 共用的入参校验：返回第一条错误文案，全部通过返回 null。
     *
     * <p>只做入口层能判定的格式校验；隧道是否存在、重名、端口占用、配额等业务校验仍由
     * ForwardServiceImpl 负责，这里不重复实现。</p>
     */
    private static String validateForwardParams(String name, String remoteAddr, Integer inPort,
                                                String protocol, Integer type) {
        String nameError = validateName(name);
        if (nameError != null) {
            return nameError;
        }
        String remoteAddrError = validateRemoteAddr(remoteAddr);
        if (remoteAddrError != null) {
            return remoteAddrError;
        }
        String inPortError = validateInPort(inPort);
        if (inPortError != null) {
            return inPortError;
        }
        String protocolError = validateProtocol(protocol);
        if (protocolError != null) {
            return protocolError;
        }
        return validateType(type);
    }

    /** name：去空格后 1-64 字符，且不能含换行（前端列表按行展示，换行会把一条转发拆成两行）。 */
    private static String validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "name 不能为空";
        }
        if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            return "name 不能包含换行符";
        }
        if (name.trim().length() > NAME_MAX_LENGTH) {
            return "name 去空格后只能是 1-" + NAME_MAX_LENGTH + " 个字符";
        }
        return null;
    }

    /** remoteAddr：非空、≤255、不含空白字符（多目标用英文逗号分隔，空格会被当成地址的一部分）。 */
    private static String validateRemoteAddr(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.trim().isEmpty()) {
            return "remoteAddr 不能为空";
        }
        if (remoteAddr.length() > REMOTE_ADDR_MAX_LENGTH) {
            return "remoteAddr 长度不能超过 " + REMOTE_ADDR_MAX_LENGTH + " 个字符";
        }
        for (int i = 0; i < remoteAddr.length(); i++) {
            char c = remoteAddr.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return "remoteAddr 不能包含空白字符";
            }
        }
        return null;
    }

    /** inPort：null 表示由服务端自动分配（既有行为），非 null 时必须是 1-65535。 */
    private static String validateInPort(Integer inPort) {
        if (inPort == null) {
            return null;
        }
        if (inPort < PORT_MIN || inPort > PORT_MAX) {
            return "inPort 必须在 " + PORT_MIN + "-" + PORT_MAX + " 之间";
        }
        return null;
    }

    /** protocol：未传按 null 透传（节点侧 tcp/udp 各建一份）；传了只认 tcp / udp。 */
    private static String validateProtocol(String protocol) {
        if (protocol == null) {
            return null;
        }
        String normalized = normalizeProtocol(protocol);
        if (!"tcp".equals(normalized) && !"udp".equals(normalized)) {
            return "protocol 只支持 tcp / udp";
        }
        return null;
    }

    /** type：未传按 null 透传；传了只认 1（入口）/ 2（出口）。 */
    private static String validateType(Integer type) {
        if (type == null) {
            return null;
        }
        if (type != TYPE_ENTRY && type != TYPE_EXIT) {
            return "type 只支持 1（入口）/ 2（出口）";
        }
        return null;
    }

    /** 统一小写并去空格，落库口径与 TunnelServiceImpl 的 protocol 归一化一致。 */
    private static String normalizeProtocol(String protocol) {
        return protocol == null ? null : protocol.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * /create 的请求体：在既有 ForwardDto 之上接住 protocol / type。
     *
     * <p>ForwardDto 只有 name / tunnelId / remoteAddr / strategy / inPort；前端若带上 protocol 或 type，
     * Jackson 默认静默丢弃，非法值会一路进到业务层。这里用子类接住它们，既不改 ForwardDto
     * （并行开发在动 service 侧），也不改 ForwardService 的方法签名。</p>
     */
    public static class CreateForwardRequest extends ForwardDto {

        /** 只允许 tcp / udp（大小写不敏感），落库前统一转小写。 */
        private String protocol;

        /** 只允许 1（入口）/ 2（出口）。 */
        private Integer type;

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public Integer getType() {
            return type;
        }

        public void setType(Integer type) {
            this.type = type;
        }
    }

    /** /update 的请求体：同 CreateForwardRequest，字段来自 ForwardUpdateDto。 */
    public static class UpdateForwardRequest extends ForwardUpdateDto {

        /** 只允许 tcp / udp（大小写不敏感），落库前统一转小写。 */
        private String protocol;

        /** 只允许 1（入口）/ 2（出口）。 */
        private Integer type;

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public Integer getType() {
            return type;
        }

        public void setType(Integer type) {
            this.type = type;
        }
    }

}
