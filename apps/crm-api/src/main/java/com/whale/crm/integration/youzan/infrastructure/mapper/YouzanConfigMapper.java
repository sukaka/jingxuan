package com.whale.crm.integration.youzan.infrastructure.mapper;

import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncJob;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface YouzanConfigMapper {
    void insertShopConfig(@Param("tenantId") long tenantId, @Param("entity") YouzanShopConfig entity);
    YouzanShopConfig selectShopConfig(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    YouzanShopConfig selectShopConfigForUpdate(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    YouzanShopConfig selectShopConfigByClientAndShop(@Param("clientId") String clientId, @Param("shopId") String shopId);
    List<YouzanShopConfig> selectShopConfigs(@Param("tenantId") long tenantId);
    List<YouzanShopConfig> selectSyncEnabledShopConfigs();
    int updateShopConfig(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                         @Param("entity") YouzanShopConfig entity);
    int updateShopTokens(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                         @Param("entity") YouzanShopConfig entity);
    int updateShopConnectionStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                                   @Param("version") long version, @Param("status") String status,
                                   @Param("error") String error, @Param("connectedAt") Instant connectedAt);
    int acquireShopLease(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                         @Param("owner") String owner, @Param("expiresAt") Instant expiresAt);
    int releaseShopLease(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                         @Param("owner") String owner);
    int releaseExpiredShopLeases(@Param("now") Instant now);
    int expireRunningJobs(@Param("now") Instant now);

    void insertSyncPolicy(@Param("tenantId") long tenantId, @Param("entity") YouzanSyncPolicy entity);
    YouzanSyncPolicy selectSyncPolicy(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    int updateSyncPolicy(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                         @Param("entity") YouzanSyncPolicy entity);
    int markPolicyScheduled(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                            @Param("scheduledAt") Instant scheduledAt, @Param("nextRunAt") Instant nextRunAt);

    void insertSyncJob(@Param("tenantId") long tenantId, @Param("entity") YouzanSyncJob entity);
    YouzanSyncJob selectSyncJob(@Param("tenantId") long tenantId, @Param("id") long id);
    YouzanSyncJob selectSyncJobByLeaseOwner(@Param("tenantId") long tenantId, @Param("leaseOwner") String leaseOwner);
    YouzanSyncJob selectSyncJobForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<YouzanSyncJob> selectSyncJobs(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                       @Param("offset") int offset, @Param("size") int size);
    long countSyncJobs(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    int updateSyncJob(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                      @Param("entity") YouzanSyncJob entity);
}
