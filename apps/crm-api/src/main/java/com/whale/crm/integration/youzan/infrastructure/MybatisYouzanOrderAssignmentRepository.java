package com.whale.crm.integration.youzan.infrastructure;

import com.whale.crm.integration.youzan.domain.YouzanOrderAssignment;
import com.whale.crm.integration.youzan.domain.YouzanOrderAssignmentRepository;
import com.whale.crm.integration.youzan.domain.YouzanStewardAlias;
import com.whale.crm.integration.youzan.infrastructure.mapper.YouzanOrderAssignmentMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisYouzanOrderAssignmentRepository implements YouzanOrderAssignmentRepository {

    private final YouzanOrderAssignmentMapper mapper;

    public MybatisYouzanOrderAssignmentRepository(YouzanOrderAssignmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<YouzanOrderAssignment> findByOrder(long tenantId, String shopId, String externalOrderNo) {
        return Optional.ofNullable(mapper.selectByOrder(tenantId, shopId, externalOrderNo));
    }

    @Override
    public Optional<YouzanOrderAssignment> findByOrderForUpdate(long tenantId, String shopId, String externalOrderNo) {
        return Optional.ofNullable(mapper.selectByOrderForUpdate(tenantId, shopId, externalOrderNo));
    }

    @Override
    public List<YouzanOrderAssignment> findByOrders(long tenantId, String shopId, Collection<String> externalOrderNos) {
        if (externalOrderNos == null || externalOrderNos.isEmpty()) {
            return List.of();
        }
        return mapper.selectByOrders(tenantId, shopId, externalOrderNos);
    }

    @Override
    public void upsertOrderAssignment(long tenantId, YouzanOrderAssignment assignment) {
        mapper.upsertOrderAssignment(tenantId, assignment);
    }

    @Override
    public int updateManualAssignment(long tenantId, long id, long expectedVersion,
                                      Long assignedStewardId, Long assignedTeamId,
                                      String assignmentStatus, String assignmentSource,
                                      String assignmentReason, Long assignedBy, Instant assignedAt) {
        return mapper.updateManualAssignment(tenantId, id, expectedVersion, assignedStewardId, assignedTeamId,
                assignmentStatus, assignmentSource, assignmentReason, assignedBy, assignedAt);
    }

    @Override
    public int backfillPendingByAccountKey(long tenantId, String memoAccountKey,
                                           long stewardUserId, Long stewardTeamId,
                                           String assignmentReason, Instant assignedAt) {
        return mapper.backfillPendingByAccountKey(tenantId, memoAccountKey, stewardUserId, stewardTeamId,
                assignmentReason, assignedAt);
    }

    @Override
    public List<YouzanStewardAlias> findAliases(long tenantId) {
        return mapper.selectAliases(tenantId);
    }

    @Override
    public Optional<YouzanStewardAlias> findAliasById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectAliasById(tenantId, id));
    }

    @Override
    public Optional<YouzanStewardAlias> findAliasByKey(long tenantId, String aliasKey) {
        return Optional.ofNullable(mapper.selectAliasByKey(tenantId, aliasKey));
    }

    @Override
    public Optional<YouzanStewardAlias> findAliasByKeyForUpdate(long tenantId, String aliasKey) {
        return Optional.ofNullable(mapper.selectAliasByKeyForUpdate(tenantId, aliasKey));
    }

    @Override
    public void insertAlias(long tenantId, YouzanStewardAlias alias) {
        mapper.insertAlias(tenantId, alias);
    }

    @Override
    public int updateAlias(long tenantId, long id, long expectedVersion, String aliasName, String aliasKey,
                           long stewardUserId, String status, String remark, long updatedBy) {
        return mapper.updateAlias(tenantId, id, expectedVersion, aliasName, aliasKey, stewardUserId,
                status, remark, updatedBy);
    }

    @Override
    public int deleteAlias(long tenantId, long id) {
        return mapper.deleteAlias(tenantId, id);
    }
}
