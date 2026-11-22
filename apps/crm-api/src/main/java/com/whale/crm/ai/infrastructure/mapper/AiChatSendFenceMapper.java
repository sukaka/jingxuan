package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.AiChatSendFence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface AiChatSendFenceMapper {
    AiChatSendFence selectForUpdate(@Param("tenantId") long tenantId, @Param("runId") long runId);

    void insert(@Param("e") AiChatSendFence fence);

    int markStarted(@Param("tenantId") long tenantId, @Param("runId") long runId,
                    @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);

    int blockTenant(@Param("tenantId") long tenantId, @Param("now") Instant now);

    int blockCustomer(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                      @Param("now") Instant now);

    long countInFlightForTenant(@Param("tenantId") long tenantId);

    long countInFlightForCustomer(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int finish(@Param("tenantId") long tenantId, @Param("runId") long runId,
               @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);

    int releaseForRetry(@Param("tenantId") long tenantId, @Param("runId") long runId,
                        @Param("expectedVersion") long expectedVersion, @Param("now") Instant now);

    int recover(@Param("tenantId") long tenantId, @Param("runId") long runId, @Param("now") Instant now);
}
