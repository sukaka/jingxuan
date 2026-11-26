package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.adapter.WecomContactClient;
import com.whale.crm.integration.wecom.adapter.WecomContactClientRouter;
import com.whale.crm.integration.wecom.domain.WecomContactProfile;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRun;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.integration.wecom.tag.application.WecomTagSyncService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WecomContactSyncService {
    private static final int MAX_PAGES = 10000;
    private static final long DEFAULT_LEASE_SECONDS = 300L;
    private static final Logger log = LoggerFactory.getLogger(WecomContactSyncService.class);
    private final WecomContactSyncRepository repository;
    private final WecomContactClientRouter clients;
    private final AuditService auditService;
    private final TransactionTemplate transactions;
    private final long syncLeaseSeconds;
    private final ObjectProvider<WecomTagSyncService> tagSyncProvider;

    @Autowired
    public WecomContactSyncService(WecomContactSyncRepository repository, WecomContactClientRouter clients,
                                   AuditService auditService, PlatformTransactionManager transactionManager,
                                   @Value("${app.wecom.official.sync-lease-seconds:300}") long syncLeaseSeconds,
                                   ObjectProvider<WecomTagSyncService> tagSyncProvider) {
        this.repository = repository;
        this.clients = clients;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.syncLeaseSeconds = syncLeaseSeconds > 0 ? syncLeaseSeconds : DEFAULT_LEASE_SECONDS;
        this.tagSyncProvider = tagSyncProvider;
    }

    public WecomContactSyncService(WecomContactSyncRepository repository, WecomContactClientRouter clients,
                                   AuditService auditService, PlatformTransactionManager transactionManager,
                                   long syncLeaseSeconds) {
        this(repository, clients, auditService, transactionManager, syncLeaseSeconds, null);
    }

    public WecomContactSyncService(WecomContactSyncRepository repository, WecomContactClientRouter clients,
                                   AuditService auditService, PlatformTransactionManager transactionManager) {
        this(repository, clients, auditService, transactionManager, DEFAULT_LEASE_SECONDS, null);
    }

    public WecomContactSyncRun sync(CurrentUser user, String requestedType) {
        WecomContactClient client = client(user.tenantId());
        String type = requestedType == null ? "FULL" : requestedType;
        Instant now = Instant.now();
        long runId = repository.startRun(user.tenantId(), client.corpId(), type, user.userId(),
                now, now.plusSeconds(syncLeaseSeconds));
        if (runId == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该企业已有客户同步正在运行");
        }
        Set<String> syncedExternalUserids = new LinkedHashSet<>();
        try {
            WecomContactSyncRun run = transactions.execute(status ->
                    executeFull(user.tenantId(), client, type, runId, syncedExternalUserids));
            pullTagsForContactsSafely(user.tenantId(), syncedExternalUserids, "CONTACT_SYNC");
            return run;
        } catch (RuntimeException ex) {
            repository.finishRun(user.tenantId(), runId, "ERROR", null, 0, 0, 0,
                    masked(ex), Instant.now());
            if (ex instanceof BusinessException business) {
                throw business;
            }
            throw new BusinessException(ErrorCode.CONFLICT, "企微客户同步失败，本次未执行对账失效");
        }
    }

    private WecomContactSyncRun executeFull(long tenantId, WecomContactClient client, String type, long runId,
                                            Set<String> syncedExternalUserids) {
        List<WecomStaffAccount> staff = repository.enabledStaff(tenantId, client.corpId());
        if (staff.isEmpty()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "没有启用的企微成员映射，拒绝执行全量或对账");
        }
        int received = 0;
        String cursor = null;
        int pages = 0;
        for (int start = 0; start < staff.size(); start += 100) {
            List<String> userids = staff.subList(start, Math.min(start + 100, staff.size())).stream()
                    .map(WecomStaffAccount::wecomUserid).toList();
            cursor = null;
            do {
                WecomContactClient.WecomContactBatchPage page = client.batchGetByUser(userids, cursor, 100);
                for (WecomContactProfile profile : page.contacts()) {
                    WecomStaffAccount mapping = repository.staff(tenantId, client.corpId(), profile.wecomUserid())
                            .filter(WecomStaffAccount::enabled)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "企微成员未映射到启用的CRM员工"));
                    repository.upsertContact(tenantId, mapping.staffUserId(), runId, Instant.now(), profile);
                    syncedExternalUserids.add(profile.externalUserid());
                    received++;
                }
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank() && ++pages < MAX_PAGES);
            if (cursor != null && !cursor.isBlank()) {
                throw new BusinessException(ErrorCode.CONFLICT, "企微客户分页超过安全上限");
            }
        }
        int deactivated = "RECONCILE".equals(type) ? repository.deactivateMissing(tenantId, client.corpId(), runId) : 0;
        if (repository.finishRun(tenantId, runId, "SUCCESS", cursor, received, received, deactivated,
                null, Instant.now()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微同步租约已过期，本次结果未提交");
        }
        auditService.record(tenantId, null, "WECOM_CONTACT_SYNC", "WECOM_CONTACT_SYNC_RUN",
                Long.toString(runId), null, Map.of("runType", type, "received", received, "deactivated", deactivated), null);
        return repository.runs(tenantId, client.corpId()).stream().filter(run -> run.id() == runId).findFirst().orElseThrow();
    }

    public void refreshContact(long tenantId, String externalUserid) {
        WecomContactClient client = client(tenantId);
        Instant now = Instant.now();
        long runId = repository.startRun(tenantId, client.corpId(), "CALLBACK", null,
                now, now.plusSeconds(syncLeaseSeconds));
        if (runId == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "无法创建客户回调同步记录");
        }
        try {
            int[] count = {0};
            transactions.executeWithoutResult(status -> {
                for (WecomContactProfile profile : client.getContact(externalUserid)) {
                    WecomStaffAccount mapping = repository.staff(tenantId, client.corpId(), profile.wecomUserid())
                            .filter(WecomStaffAccount::enabled)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "回调成员未映射到启用的CRM员工"));
                    repository.upsertContact(tenantId, mapping.staffUserId(), runId, Instant.now(), profile);
                    count[0]++;
                }
                if (repository.finishRun(tenantId, runId, "SUCCESS", null, count[0], count[0], 0,
                        null, Instant.now()) != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "企微回调同步租约已过期，本次结果未提交");
                }
            });
            pullTagsForContactsSafely(tenantId, Set.of(externalUserid), "CALLBACK");
        } catch (RuntimeException ex) {
            repository.finishRun(tenantId, runId, "ERROR", null, 0, 0, 0, masked(ex), Instant.now());
            throw ex;
        }
    }

    private void pullTagsForContactsSafely(long tenantId, Set<String> externalUserids, String triggerSource) {
        if (tagSyncProvider == null || externalUserids == null || externalUserids.isEmpty()) {
            return;
        }
        WecomTagSyncService tagSync = tagSyncProvider.getIfAvailable();
        if (tagSync == null) {
            return;
        }
        for (String externalUserid : externalUserids) {
            try {
                tagSync.pullCustomerTagsForExternalUser(tenantId, externalUserid, triggerSource);
            } catch (RuntimeException ex) {
                log.warn("联系人同步后回流企微客户标签失败，已忽略以保护联系人同步: tenantId={}", tenantId);
            }
        }
    }

    public void deleteFollow(long tenantId, String wecomUserid, String externalUserid, String status) {
        WecomContactClient client = client(tenantId);
        WecomStaffAccount staff = repository.staff(tenantId, client.corpId(), wecomUserid)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "回调成员未映射到CRM员工"));
        repository.updateFollowStatus(tenantId, client.corpId(), externalUserid, staff.staffUserId(), status);
    }

    public List<WecomContactSyncRun> runs(long tenantId) {
        return repository.runs(tenantId, client(tenantId).corpId());
    }

    public String corpId(long tenantId) { return client(tenantId).corpId(); }

    private WecomContactClient client(long tenantId) {
        WecomContactClient client = clients.client(tenantId);
        if (client == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微客户联系官方能力未配置或未启用");
        }
        return client;
    }

    private String masked(RuntimeException ex) {
        if (ex instanceof BusinessException business) {
            return business.getMessage();
        }
        return "企微客户同步处理失败，未包含供应商响应或敏感参数";
    }
}
