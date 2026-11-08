package com.whale.crm.customer.domain;

import java.util.Optional;

public interface CustomerLegacyRepository {

    Optional<CustomerLegacy> findByCustomerId(long tenantId, long customerId);
}
