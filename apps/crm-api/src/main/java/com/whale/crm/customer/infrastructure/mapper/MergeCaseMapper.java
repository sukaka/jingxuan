package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.MergeCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MergeCaseMapper {

    MergeCase selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    int countPair(@Param("tenantId") long tenantId, @Param("primaryCustomerId") long primaryCustomerId,
                  @Param("secondaryCustomerId") long secondaryCustomerId);

    int countOpenBetween(@Param("tenantId") long tenantId, @Param("customerA") long customerA,
                         @Param("customerB") long customerB);

    int insertCase(MergeCase mergeCase);

    int updateStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("toStatus") String toStatus, @Param("reviewerId") long reviewerId,
                     @Param("reviewedAt") Instant reviewedAt);

    List<MergeCase> search(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                           @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                           @Param("status") String status, @Param("offset") int offset, @Param("size") int size);

    long countSearch(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                     @Param("status") String status);
}
