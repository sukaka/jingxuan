package com.whale.crm.integration.wecom.infrastructure.mapper;

import com.whale.crm.integration.wecom.domain.WecomRecallEvent;
import com.whale.crm.integration.wecom.domain.WecomRecallRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface WecomRecallMapper extends WecomRecallRepository {
    @Override
    void insert(@Param("e") WecomRecallEvent event);

    @Override
    Optional<WecomRecallEvent> findByRecallMessageId(@Param("tenantId") long tenantId, @Param("corpId") String corpId,
                                                     @Param("recallMessageId") String recallMessageId);

    @Override
    Optional<WecomRecallEvent> findPendingByOriginalMessageId(@Param("tenantId") long tenantId,
                                                              @Param("corpId") String corpId,
                                                              @Param("originalMessageId") String originalMessageId);

    @Override
    int markApplied(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                    @Param("originalMessageRef") long originalMessageRef, @Param("appliedAt") Instant appliedAt);
}
