package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.LegacyFieldMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LegacyFieldMappingMapper {

    List<LegacyFieldMapping> selectEnabled(@Param("tenantId") long tenantId);
}
