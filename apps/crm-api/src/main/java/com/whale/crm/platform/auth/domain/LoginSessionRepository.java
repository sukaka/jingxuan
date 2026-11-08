package com.whale.crm.platform.auth.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * sys_login_session 存取。token_hash 全局唯一，按哈希定位属认证引导，定位后以行内 tenantId 为准。
 */
public interface LoginSessionRepository {

    void insert(LoginSession session);

    /**
     * 按令牌哈希取会话（认证引导查询）；有效性（未吊销/未过期）由调用方判定。
     */
    Optional<LoginSession> findByTokenHash(String tokenHash);

    int revokeByTokenHash(String tokenHash, Instant revokedAt);

    /**
     * 吊销用户全部有效会话（停用账号/回收权限时用）。
     */
    int revokeAllByUserId(long tenantId, long userId, Instant revokedAt);
}
