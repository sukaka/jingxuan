package com.whale.crm.integration.youzan.infrastructure.mapper;

import com.whale.crm.integration.youzan.domain.YouzanOrderAssignment;
import com.whale.crm.integration.youzan.domain.YouzanStewardAlias;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Mapper
public interface YouzanOrderAssignmentMapper {

    YouzanOrderAssignment selectByOrder(@Param("tenantId") long tenantId,
                                        @Param("shopId") String shopId,
                                        @Param("externalOrderNo") String externalOrderNo);

    YouzanOrderAssignment selectByOrderForUpdate(@Param("tenantId") long tenantId,
                                                 @Param("shopId") String shopId,
                                                 @Param("externalOrderNo") String externalOrderNo);

    List<YouzanOrderAssignment> selectByOrders(@Param("tenantId") long tenantId,
                                               @Param("shopId") String shopId,
                                               @Param("externalOrderNos") Collection<String> externalOrderNos);

    void upsertOrderAssignment(@Param("tenantId") long tenantId,
                               @Param("entity") YouzanOrderAssignment entity);

    int updateManualAssignment(@Param("tenantId") long tenantId,
                               @Param("id") long id,
                               @Param("expectedVersion") long expectedVersion,
                               @Param("assignedStewardId") Long assignedStewardId,
                               @Param("assignedTeamId") Long assignedTeamId,
                               @Param("assignmentStatus") String assignmentStatus,
                               @Param("assignmentSource") String assignmentSource,
                               @Param("assignmentReason") String assignmentReason,
                               @Param("assignedBy") Long assignedBy,
                               @Param("assignedAt") Instant assignedAt);

    int backfillPendingByAccountKey(@Param("tenantId") long tenantId,
                                    @Param("memoAccountKey") String memoAccountKey,
                                    @Param("stewardUserId") long stewardUserId,
                                    @Param("stewardTeamId") Long stewardTeamId,
                                    @Param("assignmentReason") String assignmentReason,
                                    @Param("assignedAt") Instant assignedAt);

    List<YouzanStewardAlias> selectAliases(@Param("tenantId") long tenantId);

    YouzanStewardAlias selectAliasById(@Param("tenantId") long tenantId, @Param("id") long id);

    YouzanStewardAlias selectAliasByKey(@Param("tenantId") long tenantId, @Param("aliasKey") String aliasKey);

    YouzanStewardAlias selectAliasByKeyForUpdate(@Param("tenantId") long tenantId, @Param("aliasKey") String aliasKey);

    void insertAlias(@Param("tenantId") long tenantId, @Param("entity") YouzanStewardAlias entity);

    int updateAlias(@Param("tenantId") long tenantId,
                    @Param("id") long id,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("aliasName") String aliasName,
                    @Param("aliasKey") String aliasKey,
                    @Param("stewardUserId") long stewardUserId,
                    @Param("status") String status,
                    @Param("remark") String remark,
                    @Param("updatedBy") long updatedBy);

    int deleteAlias(@Param("tenantId") long tenantId, @Param("id") long id);
}
