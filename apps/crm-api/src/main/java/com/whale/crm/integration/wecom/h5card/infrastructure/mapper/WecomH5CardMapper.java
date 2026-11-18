package com.whale.crm.integration.wecom.h5card.infrastructure.mapper;

import com.whale.crm.integration.wecom.h5card.domain.WecomH5Card;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5CardRepository;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5CardUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface WecomH5CardMapper extends WecomH5CardRepository {
    @Override
    void insert(@Param("e") WecomH5Card card);

    @Override
    Optional<WecomH5Card> findById(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                   @Param("id") long id);

    @Override
    Optional<WecomH5Card> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                            @Param("id") long id);

    @Override
    Optional<WecomH5Card> findByShareKey(@Param("shareKey") String shareKey);

    @Override
    List<WecomH5Card> findForCustomer(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                      @Param("customerId") long customerId, @Param("size") int size);

    @Override
    int disable(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId, @Param("id") long id,
                @Param("version") long version, @Param("reason") String reason, @Param("disabledBy") long disabledBy,
                @Param("disabledAt") Instant disabledAt);

    @Override
    void insertUsage(@Param("e") WecomH5CardUsage usage);

    @Override
    Optional<WecomH5CardUsage> findUsageByKey(@Param("tenantId") long tenantId, @Param("key") String key);
}
