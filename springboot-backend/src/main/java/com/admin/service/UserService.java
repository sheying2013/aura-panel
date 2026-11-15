package com.admin.service;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface UserService extends IService<User> {

    R login(LoginDto loginDto);

    /**
     * 退出登录：仅吊销当前票据；新票据使用 sid，旧票据使用摘要。
     * 不自增用户级 token_version，避免旧请求迟到时影响新会话。
     */
    R logout();

    R createUser(UserDto userDto);

    R getAllUsers();

    R updateUser(UserUpdateDto userUpdateDto);

    R deleteUser(Long id);

    R getUserPackageInfo();
    
    R updatePassword(ChangePasswordDto changePasswordDto);

    R reset(ResetFlowDto resetFlowDto);
}
