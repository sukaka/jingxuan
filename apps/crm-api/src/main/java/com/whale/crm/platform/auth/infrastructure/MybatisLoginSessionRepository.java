package com.whale.crm.platform.auth.infrastructure;

import com.whale.crm.platform.auth.domain.LoginSession;
import com.whale.crm.platform.auth.domain.LoginSessionRepository;
import com.whale.crm.platform.auth.infrastructure.mapper.LoginSessionMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class MybatisLoginSessionRepository implements LoginSessionRepository {

    private final LoginSessionMapper loginSessionMapper;

    public MybatisLoginSessionRepository(LoginSessionMapper loginSessionMapper) {
        this.loginSessionMapper = loginSessionMapper;
    }

    @Override
    public void insert(LoginSession session) {
        loginSessionMapper.insertSession(session);
    }

    @Override
    public Optional<LoginSession> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(loginSessionMapper.selectByTokenHash(tokenHash));
    }

    @Override
    public int revokeByTokenHash(String tokenHash, Instant revokedAt) {
        return loginSessionMapper.revokeByTokenHash(tokenHash, revokedAt);
    }

    @Override
    public int revokeAllByUserId(long tenantId, long userId, Instant revokedAt) {
        return loginSessionMapper.revokeAllByUserId(tenantId, userId, revokedAt);
    }
}
