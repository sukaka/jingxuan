package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FollowupMapper {

    List<FollowupRepository.FollowupListRow> search(@Param("tenantId") long tenantId,
                                                    @Param("scopeType") String scopeType,
                                                    @Param("teamIds") List<Long> teamIds,
                                                    @Param("selfId") long selfId,
                                                    @Param("customerId") Long customerId,
                                                    @Param("offset") int offset, @Param("size") int size);

    long countSearch(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                     @Param("customerId") Long customerId);

    Followup selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    int insertFollowup(Followup followup);

    int updateFollowup(Followup followup);

    int reassignCustomer(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                         @Param("toCustomerId") long toCustomerId);

    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_followup SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND id IN <foreach item='id' collection='ids' open='(' close=')' separator=','>#{id}</foreach></script>"})
    int reassignSpecific(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids,
                         @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);

    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_followup WHERE tenant_id=#{tenantId} AND customer_id=#{customerId} ORDER BY id")
    List<Long> findIdsByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
}
