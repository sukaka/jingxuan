package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.LegacyFieldMapping;
import com.whale.crm.customer.domain.LegacyFieldMappingRepository;
import com.whale.crm.customer.infrastructure.mapper.LegacyFieldMappingMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisLegacyFieldMappingRepository implements LegacyFieldMappingRepository {

    private final LegacyFieldMappingMapper mapper;

    public MybatisLegacyFieldMappingRepository(LegacyFieldMappingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<LegacyFieldMapping> findEnabled(long tenantId) {
        return mapper.selectEnabled(tenantId);
    }
}
