package com.whale.crm.platform.context;

import com.whale.crm.platform.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前请求的租户上下文。
 *
 * P0-1 起从已认证主体（CurrentUser）解析 tenantId；接口一律不得接受客户端传入的
 * tenantId 作为可信事实。无认证主体的场景（如单元测试直调 Service）回退默认租户 1。
 */
public final class TenantContext {

    public static final long DEFAULT_TENANT_ID = 1L;

    private TenantContext() {
    }

    public static long currentTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser.tenantId();
        }
        return DEFAULT_TENANT_ID;
    }
}
