package com.admin.common.dto;

import com.admin.entity.UserTunnel;
import com.admin.service.impl.ForwardServiceImpl;
import lombok.Data;

@Data
public class UserPermissionResult {
    public boolean hasError;
    private String errorMessage;
    private Integer limiter;
    private UserTunnel userTunnel;

    public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
        UserPermissionResult result = new UserPermissionResult();
        result.setLimiter(limiter);
        result.setUserTunnel(userTunnel);
        return result;
    }

    public static UserPermissionResult error(String errorMessage) {
        UserPermissionResult result = new UserPermissionResult();
        result.setErrorMessage(errorMessage);
        return result;
    }
}
