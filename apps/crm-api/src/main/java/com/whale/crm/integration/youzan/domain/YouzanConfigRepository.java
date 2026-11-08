package com.whale.crm.integration.youzan.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface YouzanConfigRepository {
    void insertShopConfig(long tenantId, YouzanShopConfig config);
    Optional<YouzanShopConfig> findShopConfig(long tenantId, String shopId);
    Optional<YouzanShopConfig> findShopConfigForUpdate(long tenantId, String shopId);
    Optional<YouzanShopConfig> findShopConfigByClientAndShop(String clientId, String shopId);
    List<YouzanShopConfig> findShopConfigs(long tenantId);
    List<YouzanShopConfig> findSyncEnabledShopConfigs();
    int updateShopConfig(long tenantId, long id, long version, YouzanShopConfig config);
    int updateShopTokens(long tenantId, long id, long version, YouzanShopConfig config);
    int updateShopConnectionStatus(long tenantId, long id, long version, String status, String error,
                                   Instant connectedAt);
    int acquireShopLease(long tenantId, String shopId, String owner, Instant expiresAt);
    int releaseShopLease(long tenantId, String shopId, String owner);
    int releaseExpiredShopLeases(Instant now);
    int expireRunningJobs(Instant now);

    void insertSyncPolicy(long tenantId, YouzanSyncPolicy policy);
    Optional<YouzanSyncPolicy> findSyncPolicy(long tenantId, String shopId);
    int updateSyncPolicy(long tenantId, long id, long version, YouzanSyncPolicy policy);
    int markPolicyScheduled(long tenantId, long id, long version, Instant scheduledAt, Instant nextRunAt);

    void insertSyncJob(long tenantId, YouzanSyncJob job);
    Optional<YouzanSyncJob> findSyncJob(long tenantId, long id);
    Optional<YouzanSyncJob> findSyncJobByLeaseOwner(long tenantId, String leaseOwner);
    Optional<YouzanSyncJob> findSyncJobForUpdate(long tenantId, long id);
    List<YouzanSyncJob> findSyncJobs(long tenantId, String shopId, int offset, int size);
    long countSyncJobs(long tenantId, String shopId);
    int updateSyncJob(long tenantId, long id, long version, YouzanSyncJob job);
}
