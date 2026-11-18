package com.whale.crm.integration.wecom.media.infrastructure.mapper;

import com.whale.crm.integration.wecom.media.domain.WecomMediaAsset;
import com.whale.crm.integration.wecom.media.domain.WecomMediaRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface WecomMediaMapper extends WecomMediaRepository {
    @Override
    void insert(@Param("e") WecomMediaAsset asset);

    @Override
    Optional<WecomMediaAsset> findById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<WecomMediaAsset> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    List<WecomMediaAsset> findByMessage(@Param("tenantId") long tenantId, @Param("messageId") long messageId);

    @Override
    List<Long> findPendingTenantIds(@Param("now") Instant now);

    @Override
    List<WecomMediaAsset> claimable(@Param("tenantId") long tenantId, @Param("now") Instant now,
                                    @Param("size") int size);

    @Override
    int claim(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
              @Param("owner") String owner, @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    @Override
    int markStored(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                   @Param("owner") String owner, @Param("objectKey") String objectKey, @Param("sha256") String sha256,
                   @Param("actualSize") long actualSize, @Param("now") Instant now);

    @Override
    int markFailure(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                    @Param("owner") String owner, @Param("status") String status, @Param("error") String error,
                    @Param("nextRetryAt") Instant nextRetryAt, @Param("now") Instant now);
}
