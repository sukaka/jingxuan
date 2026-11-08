package com.whale.crm.customer.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerTagMapper {

    List<String> selectTagCodes(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int insertTag(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                  @Param("tagCode") String tagCode, @Param("createdBy") Long createdBy);

    int deleteTag(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                  @Param("tagCode") String tagCode);

    int reassignCustomer(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                         @Param("toCustomerId") long toCustomerId);

    int deleteDuplicatesOf(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                           @Param("toCustomerId") long toCustomerId);

    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_customer_tag SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND tag_code IN <foreach item='code' collection='codes' open='(' close=')' separator=','>#{code}</foreach></script>"})
    int reassignSpecificCodes(@Param("tenantId") long tenantId, @Param("codes") List<String> codes,
                              @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);
}
