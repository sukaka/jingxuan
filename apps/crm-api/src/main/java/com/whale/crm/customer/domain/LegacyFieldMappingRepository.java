package com.whale.crm.customer.domain;

import java.util.List;

public interface LegacyFieldMappingRepository {

    /**
     * 全部启用映射，按 sort_order 升序（基线 23 项）。
     */
    List<LegacyFieldMapping> findEnabled(long tenantId);
}
