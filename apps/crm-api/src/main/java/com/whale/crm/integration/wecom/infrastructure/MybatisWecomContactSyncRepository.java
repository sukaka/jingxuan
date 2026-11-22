package com.whale.crm.integration.wecom.infrastructure;

import com.whale.crm.integration.wecom.domain.WecomContactProfile;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRun;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.integration.wecom.domain.WecomStaffIdentity;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomContactSyncMapper;
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
public class MybatisWecomContactSyncRepository implements WecomContactSyncRepository {
    private static final long DEFAULT_LEASE_SECONDS = 300L;
    private static final int MAX_TRANSIENT_ATTEMPTS = 5;
    private static final long RETRY_PARK_NANOS = 2_000_000L;
    private final WecomContactSyncMapper mapper;
    private final TransactionTemplate expireTransactionTemplate;

    public MybatisWecomContactSyncRepository(WecomContactSyncMapper mapper) {
        this.mapper = mapper;
        this.expireTransactionTemplate = null;
    }

    @Autowired
    public MybatisWecomContactSyncRepository(WecomContactSyncMapper mapper,
                                             PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.expireTransactionTemplate = template;
    }

    public List<WecomStaffAccount> enabledStaff(long t, String c) { return mapper.selectEnabledStaff(t, c); }
    public Optional<WecomStaffAccount> staff(long t, String c, String u) { return Optional.ofNullable(mapper.selectStaff(t, c, u)); }
    public List<WecomStaffIdentity> activeStaffForAuth(long t, String c, String u) { return mapper.selectActiveStaffForAuth(t, c, u); }
    public List<WecomStaffAccount> staffByUserId(long t, String c, long u) { return mapper.selectStaffByUserId(t, c, u); }

    @Override
    public long startRun(long t, String c, String type, Long actor, Instant now) {
        return startRun(t, c, type, actor, now, now.plusSeconds(DEFAULT_LEASE_SECONDS));
    }

    @Override
    public long startRun(long t, String c, String type, Long actor, Instant now, Instant leaseUntil) {
        Instant effectiveLease = leaseUntil == null ? now.plusSeconds(DEFAULT_LEASE_SECONDS) : leaseUntil;
        expireStaleRunsWithRetry(t, c, now);
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            WecomContactSyncMapper.RunInsertParam param = new WecomContactSyncMapper.RunInsertParam(
                    t, c, type, actor, now, effectiveLease);
            try {
                if (mapper.insertRun(param) != 1 || param.getId() == null) {
                    return 0;
                }
                return param.getId();
            } catch (DuplicateKeyException ex) {
                return 0;
            } catch (TransientDataAccessException ex) {
                if (attempt == MAX_TRANSIENT_ATTEMPTS) {
                    throw ex;
                }
                LockSupport.parkNanos(RETRY_PARK_NANOS * attempt);
            }
        }
        return 0;
    }

    private void expireStaleRunsWithRetry(long tenantId, String corpId, Instant now) {
        Instant legacyCutoff = now.minusSeconds(DEFAULT_LEASE_SECONDS);
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                if (expireTransactionTemplate != null) {
                    expireTransactionTemplate.executeWithoutResult(status ->
                            mapper.expireStaleRuns(tenantId, corpId, now, legacyCutoff,
                                    "企微同步租约已过期，系统已自动回收"));
                } else {
                    mapper.expireStaleRuns(tenantId, corpId, now, legacyCutoff,
                            "企微同步租约已过期，系统已自动回收");
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

    @Override
    public int finishRun(long t, long id, String s, String c, int r, int u, int d, String e, Instant f) {
        return mapper.finishRun(t, id, s, c, r, u, d, e, f);
    }
    public List<WecomContactSyncRun> runs(long t, String c) { return mapper.selectRuns(t, c); }
    public void upsertContact(long t, long staff, long run, Instant now, WecomContactProfile p) { mapper.upsertContact(t, staff, run, now, p); }
    public int deactivateMissing(long t, String c, long run) { return mapper.deactivateMissing(t, c, run); }
    public void updateFollowStatus(long t, String c, String e, long staff, String s) { mapper.updateFollowStatus(t, c, e, staff, s); }
}
