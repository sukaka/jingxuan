package com.whale.crm.integration.wecom.tag.infrastructure;

import com.whale.crm.integration.wecom.tag.domain.WecomCustomerTagPushTask;
import com.whale.crm.integration.wecom.tag.domain.WecomLinkedContactForTagSync;
import com.whale.crm.integration.wecom.tag.domain.WecomTagMapping;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRepository;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRun;
import com.whale.crm.integration.wecom.tag.infrastructure.mapper.WecomTagSyncMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

@Repository
public class MybatisWecomTagSyncRepository implements WecomTagSyncRepository {

    private static final long DEFAULT_LEASE_SECONDS = 300L;
    private static final int MAX_TRANSIENT_ATTEMPTS = 5;
    private static final long RETRY_PARK_NANOS = 2_000_000L;

    private final WecomTagSyncMapper mapper;
    private final TransactionTemplate expireTransactionTemplate;

    public MybatisWecomTagSyncRepository(WecomTagSyncMapper mapper) {
        this.mapper = mapper;
        this.expireTransactionTemplate = null;
    }

    @Autowired
    public MybatisWecomTagSyncRepository(WecomTagSyncMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.expireTransactionTemplate = template;
    }

    @Override
    public List<WecomTagMapping> findMappings(long tenantId, String corpId) {
        return mapper.selectMappings(tenantId, corpId);
    }

    @Override
    public Optional<WecomTagMapping> findMappingByCrmTagCode(long tenantId, String corpId, String crmTagCode) {
        return Optional.ofNullable(mapper.selectMappingByCrmTagCode(tenantId, corpId, crmTagCode));
    }

    @Override
    public void upsertMapping(long tenantId, String corpId, String crmTagCode, String wecomGroupId,
                              String wecomGroupName, String wecomTagId, String wecomTagName, String syncStatus,
                              Instant lastSyncedAt, Long actorUserId) {
        mapper.upsertMapping(tenantId, corpId, crmTagCode, wecomGroupId, wecomGroupName, wecomTagId,
                wecomTagName, syncStatus, lastSyncedAt, actorUserId);
    }

    @Override
    public long startRun(long tenantId, String corpId, String syncScope, String direction, String triggerSource,
                         String adapterName, Long actorUserId, Instant now, Instant leaseUntil) {
        Instant effectiveLease = leaseUntil == null ? now.plusSeconds(DEFAULT_LEASE_SECONDS) : leaseUntil;
        expireStaleRunsWithRetry(tenantId, corpId, syncScope, now);
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            WecomTagSyncMapper.StartRunParam param = new WecomTagSyncMapper.StartRunParam(
                    tenantId, corpId, syncScope, direction, triggerSource, adapterName, actorUserId,
                    now, effectiveLease);
            try {
                if (mapper.insertRun(param) != 1 || param.getId() == null) {
                    return 0L;
                }
                return param.getId();
            } catch (DuplicateKeyException ex) {
                return 0L;
            } catch (TransientDataAccessException ex) {
                if (attempt == MAX_TRANSIENT_ATTEMPTS) {
                    throw ex;
                }
                LockSupport.parkNanos(RETRY_PARK_NANOS * attempt);
            }
        }
        return 0L;
    }

    @Override
    public int finishRun(long tenantId, long runId, String status, int pulledCount, int pushedCount,
                         int skippedCount, int failedCount, String errorMasked, Instant finishedAt) {
        return mapper.finishRun(tenantId, runId, status, pulledCount, pushedCount, skippedCount, failedCount,
                errorMasked, finishedAt);
    }

    @Override
    public Optional<WecomTagSyncRun> findRunById(long tenantId, long runId) {
        return Optional.ofNullable(mapper.selectRunById(tenantId, runId));
    }

    @Override
    public List<WecomTagSyncRun> findRecentRuns(long tenantId, String corpId, int limit) {
        return mapper.selectRecentRuns(tenantId, corpId, limit);
    }

    @Override
    public long insertPushTask(long tenantId, String corpId, long customerId, long wecomContactId, String wecomUserid,
                               String externalUserid, String addTagIdsJson, String removeTagIdsJson, int maxAttempts,
                               Instant nextRetryAt, String triggerSource, Long actorUserId) {
        WecomTagSyncMapper.PushTaskInsertParam param = new WecomTagSyncMapper.PushTaskInsertParam(
                tenantId, corpId, customerId, wecomContactId, wecomUserid, externalUserid, addTagIdsJson,
                removeTagIdsJson, maxAttempts, nextRetryAt, triggerSource, actorUserId);
        mapper.insertPushTask(param);
        return param.getId() == null ? 0L : param.getId();
    }

    @Override
    public Optional<WecomCustomerTagPushTask> findPushTaskById(long tenantId, long taskId) {
        return Optional.ofNullable(mapper.selectPushTaskById(tenantId, taskId));
    }

    @Override
    public List<WecomCustomerTagPushTask> findPushTasks(long tenantId, String corpId, String status, int limit) {
        return mapper.selectPushTasks(tenantId, corpId, status, limit);
    }

    @Override
    public int recoverExpiredPushTasks(Instant now, String errorMasked) {
        return mapper.recoverExpiredPushTasks(now, errorMasked);
    }

    @Override
    public List<WecomCustomerTagPushTask> findPendingPushTasks(Instant now, int limit) {
        return mapper.selectPendingPushTasks(now, limit);
    }

    @Override
    public int claimPushTask(long tenantId, long taskId, int expectedAttempts, Instant now, Instant leaseUntil) {
        return mapper.claimPushTask(tenantId, taskId, expectedAttempts, now, leaseUntil);
    }

    @Override
    public int finishPushTask(long tenantId, long taskId, int expectedAttempts, String status, Instant nextRetryAt,
                              String errorMasked) {
        return mapper.finishPushTask(tenantId, taskId, expectedAttempts, status, nextRetryAt, errorMasked);
    }

    @Override
    public int resetPushTaskForRetry(long tenantId, long taskId, Instant now) {
        return mapper.resetPushTaskForRetry(tenantId, taskId, now);
    }

    @Override
    public List<WecomLinkedContactForTagSync> findLinkedContactsByCustomerId(long tenantId, String corpId,
                                                                             long customerId) {
        return mapper.selectLinkedContactsByCustomerId(tenantId, corpId, customerId);
    }

    @Override
    public List<WecomLinkedContactForTagSync> findLinkedContactsByExternalUserid(long tenantId, String corpId,
                                                                                 String externalUserid) {
        return mapper.selectLinkedContactsByExternalUserid(tenantId, corpId, externalUserid);
    }

    @Override
    public List<WecomLinkedContactForTagSync> findAllLinkedContacts(long tenantId, String corpId) {
        return mapper.selectAllLinkedContacts(tenantId, corpId);
    }

    @Override
    public int updateContactTagSnapshot(long tenantId, long wecomContactId, String tagSnapshotJson) {
        return mapper.updateContactTagSnapshot(tenantId, wecomContactId, tagSnapshotJson);
    }

    private void expireStaleRunsWithRetry(long tenantId, String corpId, String syncScope, Instant now) {
        Instant legacyCutoff = now.minusSeconds(DEFAULT_LEASE_SECONDS);
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                if (expireTransactionTemplate != null) {
                    expireTransactionTemplate.executeWithoutResult(status ->
                            mapper.expireStaleRuns(tenantId, corpId, syncScope, now, legacyCutoff,
                                    "企微标签同步租约已过期，系统已自动回收"));
                } else {
                    mapper.expireStaleRuns(tenantId, corpId, syncScope, now, legacyCutoff,
                            "企微标签同步租约已过期，系统已自动回收");
                }
                return;
            } catch (TransientDataAccessException ex) {
                if (attempt == MAX_TRANSIENT_ATTEMPTS) {
                    throw ex;
                }
                LockSupport.parkNanos(RETRY_PARK_NANOS * attempt);
            }
        }
    }
}
