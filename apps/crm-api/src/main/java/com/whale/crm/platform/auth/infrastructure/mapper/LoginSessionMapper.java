package com.whale.crm.platform.auth.infrastructure.mapper;

import com.whale.crm.platform.auth.domain.LoginSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface LoginSessionMapper {

    int insertSession(LoginSession session);

    LoginSession selectByTokenHash(@Param("tokenHash") String tokenHash);

    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("revokedAt") Instant revokedAt);

    int revokeAllByUserId(@Param("tenantId") long tenantId, @Param("userId") long userId,
                          @Param("revokedAt") Instant revokedAt);
}
