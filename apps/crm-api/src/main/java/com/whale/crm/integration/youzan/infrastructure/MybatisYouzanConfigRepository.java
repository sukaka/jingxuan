package com.whale.crm.integration.youzan.infrastructure;

import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncJob;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import com.whale.crm.integration.youzan.infrastructure.mapper.YouzanConfigMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisYouzanConfigRepository implements YouzanConfigRepository {
    private final YouzanConfigMapper mapper;

    public MybatisYouzanConfigRepository(YouzanConfigMapper mapper) {
        this.mapper = mapper;
    }

    public void insertShopConfig(long tenantId, YouzanShopConfig config) { mapper.insertShopConfig(tenantId, config); }
    public Optional<YouzanShopConfig> findShopConfig(long tenantId, String shopId) { return Optional.ofNullable(mapper.selectShopConfig(tenantId, shopId)); }
    public Optional<YouzanShopConfig> findShopConfigForUpdate(long tenantId, String shopId) { return Optional.ofNullable(mapper.selectShopConfigForUpdate(tenantId, shopId)); }
    public Optional<YouzanShopConfig> findShopConfigByClientAndShop(String clientId, String shopId) { return Optional.ofNullable(mapper.selectShopConfigByClientAndShop(clientId, shopId)); }
    public List<YouzanShopConfig> findShopConfigs(long tenantId) { return mapper.selectShopConfigs(tenantId); }
    public List<YouzanShopConfig> findSyncEnabledShopConfigs() { return mapper.selectSyncEnabledShopConfigs(); }
    public int updateShopConfig(long tenantId, long id, long version, YouzanShopConfig config) { return mapper.updateShopConfig(tenantId, id, version, config); }
    public int updateShopTokens(long tenantId, long id, long version, YouzanShopConfig config) { return mapper.updateShopTokens(tenantId, id, version, config); }
    public int updateShopConnectionStatus(long tenantId, long id, long version, String status, String error, Instant connectedAt) { return mapper.updateShopConnectionStatus(tenantId, id, version, status, error, connectedAt); }
    public int acquireShopLease(long tenantId, String shopId, String owner, Instant expiresAt) { return mapper.acquireShopLease(tenantId, shopId, owner, expiresAt); }
    public int releaseShopLease(long tenantId, String shopId, String owner) { return mapper.releaseShopLease(tenantId, shopId, owner); }
    public int releaseExpiredShopLeases(Instant now) { return mapper.releaseExpiredShopLeases(now); }
    public int expireRunningJobs(Instant now) { return mapper.expireRunningJobs(now); }
    public void insertSyncPolicy(long tenantId, YouzanSyncPolicy policy) { mapper.insertSyncPolicy(tenantId, policy); }
    public Optional<YouzanSyncPolicy> findSyncPolicy(long tenantId, String shopId) { return Optional.ofNullable(mapper.selectSyncPolicy(tenantId, shopId)); }
    public int updateSyncPolicy(long tenantId, long id, long version, YouzanSyncPolicy policy) { return mapper.updateSyncPolicy(tenantId, id, version, policy); }
    public int markPolicyScheduled(long tenantId, long id, long version, Instant scheduledAt, Instant nextRunAt) { return mapper.markPolicyScheduled(tenantId, id, version, scheduledAt, nextRunAt); }
    public void insertSyncJob(long tenantId, YouzanSyncJob job) { mapper.insertSyncJob(tenantId, job); }
    public Optional<YouzanSyncJob> findSyncJob(long tenantId, long id) { return Optional.ofNullable(mapper.selectSyncJob(tenantId, id)); }
    public Optional<YouzanSyncJob> findSyncJobByLeaseOwner(long tenantId, String leaseOwner) { return Optional.ofNullable(mapper.selectSyncJobByLeaseOwner(tenantId, leaseOwner)); }
    public Optional<YouzanSyncJob> findSyncJobForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectSyncJobForUpdate(tenantId, id)); }
    public List<YouzanSyncJob> findSyncJobs(long tenantId, String shopId, int offset, int size) { return mapper.selectSyncJobs(tenantId, shopId, offset, size); }
    public long countSyncJobs(long tenantId, String shopId) { return mapper.countSyncJobs(tenantId, shopId); }
    public int updateSyncJob(long tenantId, long id, long version, YouzanSyncJob job) { return mapper.updateSyncJob(tenantId, id, version, job); }
}
