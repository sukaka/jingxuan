package com.whale.crm.platform.security;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 提取 CurrentUser 的入口。认证过滤器只在校验通过时放入 CurrentUser 主体。
 */
public final class SecuritySupport {

    private SecuritySupport() {
    }

    /**
     * 当前登录主体；未认证时抛 401（业务接口均有 authenticated() 前置，正常不会走到这里）。
     */
    public static CurrentUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "未认证或凭证已失效");
    }
}
