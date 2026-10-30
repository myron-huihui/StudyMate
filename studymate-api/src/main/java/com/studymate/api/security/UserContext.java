package com.studymate.api.security;

import com.studymate.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 从安全上下文取当前登录用户 */
public final class UserContext {

    private UserContext() {
    }

    public static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new BusinessException(401, "未登录或登录已过期");
    }
}
