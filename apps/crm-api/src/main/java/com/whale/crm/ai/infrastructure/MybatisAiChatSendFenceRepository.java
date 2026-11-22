package com.whale.crm.ai.infrastructure;

import com.whale.crm.ai.domain.AiChatSendFence;
import com.whale.crm.ai.domain.AiChatSendFenceRepository;
import com.whale.crm.ai.infrastructure.mapper.AiChatSendFenceMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class MybatisAiChatSendFenceRepository implements AiChatSendFenceRepository {
    private final AiChatSendFenceMapper mapper;

    public MybatisAiChatSendFenceRepository(AiChatSendFenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AiChatSendFence> findForUpdate(long tenantId, long runId) {
        return Optional.ofNullable(mapper.selectForUpdate(tenantId, runId));
    }

    @Override
    public void insert(AiChatSendFence fence) {
        mapper.insert(fence);
    }

    @Override
    public int markStarted(long tenantId, long runId, long expectedVersion, Instant now) {
        return mapper.markStarted(tenantId, runId, expectedVersion, now);
    }

    @Override
    public int blockTenant(long tenantId, Instant now) {
        return mapper.blockTenant(tenantId, now);
    }

    @Override
    public int blockCustomer(long tenantId, long customerId, Instant now) {
        return mapper.blockCustomer(tenantId, customerId, now);
    }

    @Override
    public long countInFlightForTenant(long tenantId) {
        return mapper.countInFlightForTenant(tenantId);
    }

    @Override
    public long countInFlightForCustomer(long tenantId, long customerId) {
        return mapper.countInFlightForCustomer(tenantId, customerId);
    }

    @Override
    public int finish(long tenantId, long runId, long expectedVersion, Instant now) {
        return mapper.finish(tenantId, runId, expectedVersion, now);
    }

    @Override
    public int releaseForRetry(long tenantId, long runId, long expectedVersion, Instant now) {
        return mapper.releaseForRetry(tenantId, runId, expectedVersion, now);
    }

    @Override
    public int recover(long tenantId, long runId, Instant now) {
        return mapper.recover(tenantId, runId, now);
    }
}
