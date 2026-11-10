package com.admin.compat;

import com.admin.common.dto.ChangePasswordDto;
import com.admin.common.dto.UserDto;
import com.admin.common.dto.UserUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户名唯一性 + 密码常量时间比较（round49）的本地夹具锁定。
 *
 * <p>真机缺陷：6 并发 POST /api/v1/user/create 同名，user 表落下 3 条重复行（id 8,9,10），
 * 表上没有唯一索引。createUser 是 "count 预查 + save" 的非原子实现，预查与写入之间两条请求都能过。
 * 这里锁住五件事：① 写库撞唯一键要翻译成「用户名已存在」而不是 500；
 * ② 其它写库异常必须原样报失败（带最内层原因），绝不能变成成功或谎报重名；
 * ③ 编辑改名撞唯一键同样翻译成「用户名已存在」；④ 改密里的改名撞唯一键同理；
 * ⑤ 密码比较走常量时间实现（null / 长度不等恒返回 false）。
 */
class UserUniqueGuardTest {

    private static final int USER_ID = 5;
    private static final String OLD_NAME = "old_user";
    private static final String OLD_PWD = "oldpwd123";
    private static final String NEW_NAME = "new_user";
    private static final String NEW_PWD = "newpwd123";

    private UserServiceImpl userService;
    private UserMapper userMapper;
    private ForwardService forwardService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        forwardService = mock(ForwardService.class);

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);

        // 预查放行 + 写库成功是常态；各用例只覆盖自己要验证的那一条
        when(userMapper.selectCount(any())).thenReturn(0);
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenReturn(storedUser());
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(forwardService.resumeExpiryDisabledPausedForUser(any())).thenReturn(new ArrayList<String>());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createDuplicateKeyMapsToUsernameExists() {
        when(userMapper.insert(any(User.class)))
                .thenThrow(new DuplicateKeyException("UNIQUE constraint failed: user.user"));

        R result = userService.createUser(createDto());

        assertEquals(-1, result.getCode(), "并发抢先写入后不能报成功");
        assertEquals("用户名已存在", result.getMsg(), "唯一键冲突要翻译成友好提示");
        verify(userMapper, times(1)).insert(any(User.class));
    }

    @Test
    void createOtherSqlErrorPassesThrough() {
        when(userMapper.insert(any(User.class))).thenThrow(new RuntimeException("disk I/O error"));

        R result = userService.createUser(createDto());

        assertEquals(-1, result.getCode(), "写库失败绝不能返回成功");
        assertTrue(result.getMsg().startsWith("用户创建失败："), result.getMsg());
        assertTrue(result.getMsg().contains("disk I/O error"), "错误信息要带最内层原因：" + result.getMsg());
    }

    @Test
    void createSqliteConstraintPrefixIsNotReportedAsDuplicate() {
        // SQLITE_CONSTRAINT_NOTNULL 与 UNIQUE 同属 SQLITE_CONSTRAINT_*，把它说成「用户名已存在」会藏起真实故障
        when(userMapper.insert(any(User.class))).thenThrow(
                new RuntimeException("[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: user.pwd)"));

        R result = userService.createUser(createDto());

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().startsWith("用户创建失败："), result.getMsg());
        assertTrue(result.getMsg().contains("NOT NULL constraint failed"), result.getMsg());
    }

    @Test
    void updateRenameConflictMapsToUsernameExists() {
        when(userMapper.updateById(any(User.class)))
                .thenThrow(new DuplicateKeyException("UNIQUE constraint failed: user.user"));

        R result = userService.updateUser(updateDto());

        assertEquals(-1, result.getCode(), "改名撞唯一键不能报成功");
        assertEquals("用户名已存在", result.getMsg());
        verify(userMapper, times(1)).updateById(any(User.class));
    }

    @Test
    void updatePasswordRenameConflictMapsToUsernameExists() {
        authenticate(USER_ID);
        // round51：改密走 update(entity, wrapper)，版本号用 setSql 原子自增
        when(userMapper.update(any(User.class), any()))
                .thenThrow(new DuplicateKeyException("UNIQUE constraint failed: user.user"));

        R result = userService.updatePassword(changePasswordDto());

        assertEquals(-1, result.getCode(), "改密顺带改名撞唯一键不能报成功");
        assertEquals("用户名已存在", result.getMsg());
        verify(userMapper, times(1)).update(any(User.class), any());
    }

    @Test
    void saveFalseReportsFailure() {
        when(userMapper.insert(any(User.class))).thenReturn(0);

        R result = userService.createUser(createDto());

        assertEquals(-1, result.getCode(), "写入 0 行必须报失败");
        assertEquals("用户创建失败", result.getMsg());
    }

    @Test
    void constantTimeEqualsTrueFalseNull() {
        String hash = Md5Util.md5(OLD_PWD);
        assertTrue(Md5Util.constantTimeEquals(hash, Md5Util.md5(OLD_PWD)), "相同哈希必须判等");
        assertFalse(Md5Util.constantTimeEquals(hash, Md5Util.md5(NEW_PWD)), "不同哈希必须判不等");
        assertFalse(Md5Util.constantTimeEquals(hash, null), "null 必须判不等，不能抛异常");
        assertFalse(Md5Util.constantTimeEquals(null, hash), "null 必须判不等，不能抛异常");
        assertFalse(Md5Util.constantTimeEquals(null, null), "双 null 也判不等");
        assertFalse(Md5Util.constantTimeEquals("abc", "abcd"), "长度不等必须判不等");
        assertTrue(Md5Util.constantTimeEquals("", ""), "空串与空串判等");
    }

    // ========== 测试脚手架 ==========

    private void authenticate(int userId) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":" + userId + ",\"role_id\":1,\"name\":\"" + OLD_NAME + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private UserDto createDto() {
        UserDto dto = new UserDto();
        dto.setUser(NEW_NAME);
        dto.setPwd(NEW_PWD);
        dto.setFlow(1000L);
        dto.setNum(5);
        dto.setExpTime(1800000000L);
        dto.setFlowResetTime(1700000000L);
        return dto;
    }

    private UserUpdateDto updateDto() {
        UserUpdateDto dto = new UserUpdateDto();
        dto.setId((long) USER_ID);
        dto.setUser(OLD_NAME + "_renamed");
        dto.setFlow(2000L);
        dto.setNum(6);
        dto.setExpTime(1800000000L);
        dto.setFlowResetTime(1700000000L);
        return dto;
    }

    private ChangePasswordDto changePasswordDto() {
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setNewUsername(NEW_NAME);
        dto.setCurrentPassword(OLD_PWD);
        dto.setNewPassword(NEW_PWD);
        dto.setConfirmPassword(NEW_PWD);
        return dto;
    }

    private User storedUser() {
        User user = new User();
        user.setId((long) USER_ID);
        user.setUser(OLD_NAME);
        user.setPwd(Md5Util.md5(OLD_PWD));
        user.setStatus(1);
        user.setRoleId(1);
        user.setFlow(1000L);
        user.setNum(5);
        user.setExpTime(1800000000L);
        user.setFlowResetTime(1700000000L);
        return user;
    }
}
