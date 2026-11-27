package com.whale.crm.integration.youzan.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface YouzanOrderAssignmentRepository {

    Optional<YouzanOrderAssignment> findByOrder(long tenantId, String shopId, String externalOrderNo);

    Optional<YouzanOrderAssignment> findByOrderForUpdate(long tenantId, String shopId, String externalOrderNo);

    List<YouzanOrderAssignment> findByOrders(long tenantId, String shopId, Collection<String> externalOrderNos);

    void upsertOrderAssignment(long tenantId, YouzanOrderAssignment assignment);

    int updateManualAssignment(long tenantId, long id, long expectedVersion,
                               Long assignedStewardId, Long assignedTeamId,
                               String assignmentStatus, String assignmentSource,
                               String assignmentReason, Long assignedBy, Instant assignedAt);

    int backfillPendingByAccountKey(long tenantId, String memoAccountKey,
                                    long stewardUserId, Long stewardTeamId,
                                    String assignmentReason, Instant assignedAt);

    List<YouzanStewardAlias> findAliases(long tenantId);

    Optional<YouzanStewardAlias> findAliasById(long tenantId, long id);

    Optional<YouzanStewardAlias> findAliasByKey(long tenantId, String aliasKey);

    Optional<YouzanStewardAlias> findAliasByKeyForUpdate(long tenantId, String aliasKey);

    void insertAlias(long tenantId, YouzanStewardAlias alias);

    int updateAlias(long tenantId, long id, long expectedVersion, String aliasName, String aliasKey,
                    long stewardUserId, String status, String remark, long updatedBy);

    int deleteAlias(long tenantId, long id);
}
