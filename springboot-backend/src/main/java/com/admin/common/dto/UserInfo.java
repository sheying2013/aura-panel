package com.admin.common.dto;

import lombok.Data;

@Data
public class UserInfo {
    private final Integer userId;
    private final Integer roleId;
    private final String userName;
}
