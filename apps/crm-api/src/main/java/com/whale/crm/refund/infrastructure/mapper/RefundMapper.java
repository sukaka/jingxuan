package com.whale.crm.refund.infrastructure.mapper;

import com.whale.crm.refund.domain.RefundRepository;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RefundMapper extends RefundRepository {
    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_refund WHERE tenant_id=#{tenantId} AND order_id=#{id} LIMIT 1 FOR UPDATE")
    Long lockOrderReference(@org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("id") long id);
    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_refund_item WHERE tenant_id=#{tenantId} AND entitlement_id=#{id} LIMIT 1 FOR UPDATE")
    Long lockEntitlementReference(@org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("id") long id);
}
