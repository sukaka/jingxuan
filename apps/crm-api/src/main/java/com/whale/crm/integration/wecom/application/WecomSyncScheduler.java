package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.api.dto.WecomSyncRequest;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicy;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicyRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class WecomSyncScheduler {
    private final WecomSyncPolicyRepository repository;
    private final WecomContactSyncService contactSyncService;
    private final WecomArchiveService archiveService;
    private final CurrentUserService currentUserService;

    public WecomSyncScheduler(WecomSyncPolicyRepository repository, WecomContactSyncService contactSyncService,
                              WecomArchiveService archiveService, CurrentUserService currentUserService) {
        this.repository = repository;
        this.contactSyncService = contactSyncService;
        this.archiveService = archiveService;
        this.currentUserService = currentUserService;
    }

    @Scheduled(fixedDelayString = "${app.wecom.sync-policy.check-ms:30000}")
    public void runDuePolicies() {
        Instant now = Instant.now();
        for (WecomSyncPolicy policy : repository.findDue(now, 100)) {
            if (repository.claim(policy.tenantId(), policy.id(), policy.version(), now,
                    now.plusSeconds(policy.intervalSeconds())) != 1) {
                continue;
            }
            execute(policy);
        }
    }

    private void execute(WecomSyncPolicy policy) {
        List<String> errors = new ArrayList<>();
        int attempted = 0;
        CurrentUser actor = currentUserService.loadFirstActiveByRole(policy.tenantId(), "ADMIN")
                .orElse(null);
        if (actor == null) {
            errors.add("当前租户未配置同步操作员");
        } else {
            try {
                if (!policy.corpId().equals(contactSyncService.corpId(policy.tenantId()))) {
                    errors.add("策略企业ID与当前企微客户端配置不一致");
                } else {
                    if (Boolean.TRUE.equals(policy.contactSyncEnabled())) {
                        attempted++;
                        try {
                            contactSyncService.sync(actor, "RECONCILE");
                        } catch (Exception ex) {
                            errors.add("客户资料：" + masked(ex));
                        }
                    }
                    if (Boolean.TRUE.equals(policy.archiveSyncEnabled())) {
                        attempted++;
                        try {
                            archiveService.sync(actor, new WecomSyncRequest(policy.corpId(), null, 50));
                        } catch (Exception ex) {
                            errors.add("会话归档：" + masked(ex));
                        }
                    }
                }
            } catch (Exception ex) {
                errors.add("企微配置：" + masked(ex));
            }
        }
        if (attempted == 0 && errors.isEmpty()) {
            errors.add("未启用任何同步类型");
        }
        String status = errors.isEmpty() ? "SUCCESS" : attempted > errors.size() ? "PARTIAL" : "ERROR";
        repository.finish(policy.tenantId(), policy.id(), policy.version() + 1, status,
                errors.isEmpty() ? null : String.join("；", errors));
    }

    private static String masked(Exception error) {
        if (error instanceof com.whale.crm.platform.web.BusinessException business) {
            String message = business.getMessage();
            return message.length() <= 400 ? message : message.substring(0, 400);
        }
        return "执行失败，未包含供应商响应或敏感参数";
    }
}
