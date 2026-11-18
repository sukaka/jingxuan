package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.AiReplySuggestion;
import com.whale.crm.ai.domain.AiReplySuggestionRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AiReplySuggestionMapper extends AiReplySuggestionRepository {
    @Override
    void insert(@Param("e") AiReplySuggestion suggestion);

    @Override
    Optional<AiReplySuggestion> findById(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                         @Param("id") long id);

    @Override
    Optional<AiReplySuggestion> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                                  @Param("id") long id);

    @Override
    Optional<AiReplySuggestion> findByGenerationKey(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                                    @Param("key") String key);

    @Override
    Optional<AiReplySuggestion> findByDecisionKey(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                                  @Param("key") String key);

    @Override
    int decide(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId, @Param("id") long id,
               @Param("version") long version, @Param("status") String status, @Param("accepted") boolean accepted,
               @Param("edited") boolean edited, @Param("finalContentCipher") String finalContentCipher,
               @Param("reason") String reason, @Param("decisionBy") long decisionBy,
               @Param("decisionAt") Instant decisionAt, @Param("decisionKey") String decisionKey,
               @Param("decisionHash") String decisionHash);

    @Override
    List<AiReplySuggestion> findForCustomer(@Param("tenantId") long tenantId, @Param("employeeId") long employeeId,
                                            @Param("customerId") long customerId, @Param("size") int size);
}
