package com.whale.crm.customer.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerAttentionProjectMapper {
    List<Long> selectProjectIds(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int deleteByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int insert(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
               @Param("projectId") long projectId, @Param("actorUserId") Long actorUserId);
}
