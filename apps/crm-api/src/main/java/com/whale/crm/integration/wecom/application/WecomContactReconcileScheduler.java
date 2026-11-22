package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.platform.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "app.wecom.official", name = "legacy-reconcile-enabled", havingValue = "true", matchIfMissing = false)
public class WecomContactReconcileScheduler {
    private final WecomContactSyncService service;
    private final WecomOfficialProperties officialProperties;

    public WecomContactReconcileScheduler(WecomContactSyncService service,
                                          WecomOfficialProperties officialProperties) {
        this.service = service;
        this.officialProperties = officialProperties;
    }

    @Scheduled(cron = "${app.wecom.official.reconcile-cron:0 30 2 * * *}")
    public void reconcile() {
        long tenantId = officialProperties.allowedTenantId();
        if (tenantId <= 0) {
            return;
        }
        service.sync(new CurrentUser(0L, tenantId, "system", "系统对账", "ENTERPRISE", null,
                Set.of(), Set.of("wecom:monitor"), Set.of()), "RECONCILE");
    }
}
