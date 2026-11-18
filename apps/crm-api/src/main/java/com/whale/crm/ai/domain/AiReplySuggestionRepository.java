package com.whale.crm.ai.domain;

import java.util.List;
import java.util.Optional;

public interface AiReplySuggestionRepository {
    void insert(AiReplySuggestion suggestion);

    Optional<AiReplySuggestion> findById(long tenantId, long employeeId, long id);

    Optional<AiReplySuggestion> findByIdForUpdate(long tenantId, long employeeId, long id);

    Optional<AiReplySuggestion> findByGenerationKey(long tenantId, long employeeId, String key);

    Optional<AiReplySuggestion> findByDecisionKey(long tenantId, long employeeId, String key);

    int decide(long tenantId, long employeeId, long id, long version, String status, boolean accepted,
               boolean edited, String finalContentCipher, String reason, long decisionBy,
               java.time.Instant decisionAt, String decisionKey, String decisionHash);

    List<AiReplySuggestion> findForCustomer(long tenantId, long employeeId, long customerId, int size);
}
