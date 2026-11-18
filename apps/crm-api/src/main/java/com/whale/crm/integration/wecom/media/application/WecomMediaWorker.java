package com.whale.crm.integration.wecom.media.application;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.media.adapter.WecomMediaProperties;
import com.whale.crm.integration.wecom.media.domain.WecomMediaAsset;
import com.whale.crm.integration.wecom.media.domain.WecomMediaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class WecomMediaWorker {
    private final WecomMediaRepository repository;
    private final WecomMediaService service;
    private final WecomMediaProperties properties;
    private final IntegrationRuntimeConfigService runtimeConfig;
    private final TransactionTemplate transactions;
    private final String owner = UUID.randomUUID().toString();

    public WecomMediaWorker(WecomMediaRepository repository, WecomMediaService service, WecomMediaProperties properties,
                            IntegrationRuntimeConfigService runtimeConfig, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.runtimeConfig = runtimeConfig;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.wecom.media.delay-ms:5000}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        if (!properties.workerEnabled()) {
            return 0;
        }
        int processed = 0;
        for (Long tenantId : repository.findPendingTenantIds(Instant.now())) {
            if (!runtimeConfig.wecomArchiveRealEnabled(tenantId)) {
                continue;
            }
            for (WecomMediaAsset candidate : repository.claimable(tenantId, Instant.now(), properties.batchSize())) {
                Instant now = Instant.now();
                Boolean claimed = transactions.execute(status -> repository.claim(tenantId, candidate.id(),
                        candidate.version(), owner, now, now.plusMillis(properties.leaseMs())) == 1);
                if (!Boolean.TRUE.equals(claimed)) {
                    continue;
                }
                processed++;
                try {
                    service.download(tenantId, candidate.id(), owner);
                } catch (Exception ex) {
                    service.fail(tenantId, candidate.id(), owner, ex);
                }
            }
        }
        return processed;
    }
}
