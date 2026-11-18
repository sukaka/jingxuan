package com.whale.crm.institution.domain;

import java.util.Optional;

public interface InstitutionSettlementConfigRepository {
    Optional<InstitutionSettlementConfig> find(long tenantId);
    Optional<InstitutionSettlementConfig> findForUpdate(long tenantId);
    void insert(long tenantId, InstitutionSettlementConfig config);
    int update(long tenantId, long id, long version, InstitutionSettlementConfig config);
}
