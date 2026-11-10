package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.MapParamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
@RequestMapping("/api/v1/user")
public class UserController extends BaseController {

    private static final int PARAM_ERROR_CODE = -2;

    @LogAnnotation
    @PostMapping("/login")
    public R login(@Validated @RequestBody LoginDto loginDto) {
        return userService.login(loginDto);
    }

    /**
     * 退出登录：不需要任何角色，任何已登录用户都可以吊销自己的 token（round51）。
     */
    @LogAnnotation
    @PostMapping("/logout")
    public R logout() {
        return userService.logout();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R readAll() {
        return userService.getAllUsers();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody UserUpdateDto userUpdateDto) {
        return userService.updateUser(userUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = MapParamUtils.toLong(params, "id");
        if (id == null) {
            return R.err(PARAM_ERROR_CODE, MapParamUtils.error(params, "id"));
        }
        return userService.deleteUser(id);
    }

    @LogAnnotation
    @PostMapping("/package")
    public R getUserPackageInfo() {
        return userService.getUserPackageInfo();
    }

    @LogAnnotation
    @PostMapping("/updatePassword")
    public R updatePassword(@Validated @RequestBody ChangePasswordDto changePasswordDto) {
        return userService.updatePassword(changePasswordDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reset")
    public R reset(@Validated @RequestBody ResetFlowDto resetFlowDto) {
        return userService.reset(resetFlowDto);
    }



}
