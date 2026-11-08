package com.whale.crm.integration.wecom.application;

import com.whale.crm.platform.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "app.wecom.official", name = "legacy-reconcile-enabled", havingValue = "true", matchIfMissing = false)
public class WecomContactReconcileScheduler {
    private final WecomContactSyncService service;

    public WecomContactReconcileScheduler(WecomContactSyncService service) { this.service = service; }

    @Scheduled(cron = "${app.wecom.official.reconcile-cron:0 30 2 * * *}")
    public void reconcile() {
        service.sync(new CurrentUser(1L, 1L, "system", "系统对账", "ENTERPRISE", null,
                Set.of(), Set.of("wecom:monitor"), Set.of()), "RECONCILE");
    }
}
