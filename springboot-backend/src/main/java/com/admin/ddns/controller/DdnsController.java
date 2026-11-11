package com.admin.ddns.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.MapParamUtils;
import com.admin.controller.BaseController;
import com.admin.ddns.dto.DdnsCreateRequest;
import com.admin.ddns.dto.DdnsUpdateRequest;
import com.admin.ddns.service.DdnsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** DDNS 记录管理；所有接口都要管理员权限，域名是全局转发目标，不能按普通用户隔离。 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/ddns")
public class DdnsController extends BaseController {

    private static final int PARAM_ERROR_CODE = -2;

    @Autowired
    private DdnsService ddnsService;

    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R list() {
        return R.ok(ddnsService.list());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody DdnsCreateRequest request) {
        return ddnsService.create(request);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody DdnsUpdateRequest request) {
        return ddnsService.update(request);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody(required = false) Map<String, Object> params) {
        Long id = MapParamUtils.toLong(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "id"));
        }
        return ddnsService.delete(id);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/check")
    public R check(@RequestBody(required = false) Map<String, Object> params) {
        Long id = MapParamUtils.toLong(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "id"));
        }
        return ddnsService.checkNow(id);
    }
}
