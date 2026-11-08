package com.whale.crm.integration.wecom.infrastructure;

import com.whale.crm.integration.wecom.domain.WecomSyncPolicy;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicyRepository;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomSyncPolicyMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisWecomSyncPolicyRepository implements WecomSyncPolicyRepository {
    private final WecomSyncPolicyMapper mapper;

    public MybatisWecomSyncPolicyRepository(WecomSyncPolicyMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(WecomSyncPolicy policy) { mapper.insert(policy); }
    public Optional<WecomSyncPolicy> find(long tenantId, String corpId) { return Optional.ofNullable(mapper.select(tenantId, corpId)); }
    public Optional<WecomSyncPolicy> findForUpdate(long tenantId, String corpId) { return Optional.ofNullable(mapper.selectForUpdate(tenantId, corpId)); }
    public int update(WecomSyncPolicy policy) { return mapper.update(policy); }
    public List<WecomSyncPolicy> findDue(Instant now, int limit) { return mapper.selectDue(now, limit); }
    public int claim(long tenantId, long id, long version, Instant scheduledAt, Instant nextRunAt) { return mapper.claim(tenantId, id, version, scheduledAt, nextRunAt); }
    public int finish(long tenantId, long id, long version, String status, String error) { return mapper.finish(tenantId, id, version, status, error); }
}
