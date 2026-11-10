package com.admin.compat;

import com.admin.common.dto.UserUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.Md5Util;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.admin.service.impl.UserServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷7：POST /api/v1/user/list 曾经把用户密码 MD5 哈希原样返回
 * （真机 evidence: {"user":"sjuser1","pwd":"9d81e3d218c2e3da17d16ed1d304c550"}）。
 *
 * <p>反例同样重要：前端编辑弹窗靠「密码留空 = 不改密码」，所以脱敏后必须保证
 * 管理员带新密码更新仍然生效、不带密码更新不会把密码清掉。</p>
 */
class UserListPrivacyTest {

    private static final String SJUSER1_MD5 = "9d81e3d218c2e3da17d16ed1d304c550";

    private UserMapper userMapper;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        // round20：updateUser 现在会在写库后调用续期恢复；这里给一个空实现的协作者，
        // 避免直接 new 出来的 ServiceImpl 带着 null 引用跑业务路径。
        ForwardService forwardService = mock(ForwardService.class);
        when(forwardService.resumeExpiryDisabledPausedForUser(any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(userService, "forwardService", forwardService);
    }

    private User user(Long id, String name, String pwd, Integer roleId) {
        User u = new User();
        u.setId(id);
        u.setUser(name);
        u.setPwd(pwd);
        u.setRoleId(roleId);
        u.setStatus(0);
        u.setNum(3);
        return u;
    }

    @Test
    @DisplayName("列表接口不再回传可用哈希，且不破坏其它字段")
    void getAllUsersStripsPasswordHashes() throws Exception {
        User sj = user(2L, "sjuser1", SJUSER1_MD5, 1);
        User other = user(3L, "beta-user", Md5Util.md5("otherpass"), 1);
        when(userMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(sj, other)));

        R result = userService.getAllUsers();

        assertEquals(0, result.getCode());
        @SuppressWarnings("unchecked")
        List<User> users = (List<User>) result.getData();
        assertEquals(2, users.size());
        for (User u : users) {
            assertNull(u.getPwd(), "列表返回的 pwd 必须为 null，不能是可撞库的 MD5");
        }
        assertEquals("sjuser1", users.get(0).getUser());
        assertEquals(Integer.valueOf(3), users.get(0).getNum());
        assertEquals(Integer.valueOf(1), users.get(0).getRoleId());

        String json = new ObjectMapper().writeValueAsString(result.getData());
        assertFalse(json.contains(SJUSER1_MD5), "序列化结果里不能出现真实哈希: " + json);
        assertTrue(json.contains("sjuser1"), "用户名仍要返回，否则前端列表会空: " + json);
    }

    @Test
    @DisplayName("反例：管理员带新密码更新 → 仍然写入新密码的 MD5")
    void updateUserWithNewPasswordWritesHash() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "sjuser1", SJUSER1_MD5, 1));
        when(userMapper.selectCount(any())).thenReturn(0);
        // round51：改密与 token_version 自增合并成一条 update(entity, wrapper)，
        // 不再走 updateById —— 测试跟着改成对 update 打桩，否则返回 0 会被当成「用户不存在」。
        when(userMapper.update(any(User.class), any())).thenReturn(1);

        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(2L);
        dto.setUser("sjuser1");
        dto.setPwd("brand-new-pass");

        R result = userService.updateUser(dto);

        assertEquals(0, result.getCode());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).update(captor.capture(), any());
        assertNotNull(captor.getValue().getPwd());
        assertEquals(Md5Util.md5("brand-new-pass"), captor.getValue().getPwd());
    }

    @Test
    @DisplayName("反例：密码留空（脱敏后的常态）更新 → pwd 为 null，NOT_NULL 策略跳过，不会清空密码")
    void updateUserWithBlankPasswordKeepsExisting() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "sjuser1", SJUSER1_MD5, 1));
        when(userMapper.selectCount(any())).thenReturn(0);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(2L);
        dto.setUser("sjuser1");
        dto.setPwd("");

        userService.updateUser(dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertNull(captor.getValue().getPwd(), "空密码必须转成 null（不改该列），否则会把用户密码清空");
    }
}
