package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.YouzanSyncCursorValue;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class YouzanIncrementalSyncService {
    private static final Logger log = LoggerFactory.getLogger(YouzanIncrementalSyncService.class);
    private final YouzanConfigRepository configRepository;
    private final YouzanRepository repository;
    private final YouzanManagementService managementService;
    private final ObjectMapper objectMapper;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public YouzanIncrementalSyncService(YouzanConfigRepository configRepository, YouzanRepository repository,
                                        YouzanManagementService managementService, ObjectMapper objectMapper,
                                        IntegrationRuntimeConfigService runtimeConfig) {
        this.configRepository = configRepository;
        this.repository = repository;
        this.managementService = managementService;
        this.objectMapper = objectMapper;
        this.runtimeConfig = runtimeConfig;
    }

    @Scheduled(fixedDelayString = "${YOUZAN_INCREMENTAL_CHECK_MS:60000}")
    public void runDuePolicies() {
        Instant now = Instant.now();
        for (YouzanShopConfig shop : configRepository.findSyncEnabledShopConfigs()) {
            if (!runtimeConfig.youzanRealEnabled(shop.tenantId())) {
                continue;
            }
            configRepository.findSyncPolicy(shop.tenantId(), shop.shopId())
                    .filter(YouzanSyncPolicy::enabled)
                    .filter(policy -> due(policy, now))
                    .ifPresent(policy -> runOne(shop, policy, now));
        }
    }

    private boolean due(YouzanSyncPolicy policy, Instant now) {
        if (policy.nextRunAt() != null) {
            return !policy.nextRunAt().isAfter(now);
        }
        return policy.lastScheduledAt() == null
                || !policy.lastScheduledAt().plusSeconds(policy.intervalSeconds()).isAfter(now);
    }

    private void runOne(YouzanShopConfig shop, YouzanSyncPolicy policy, Instant now) {
        if (configRepository.markPolicyScheduled(shop.tenantId(), policy.id(), policy.version(), now,
                now.plusSeconds(policy.intervalSeconds())) != 1) {
            return;
        }
        try {
            Instant lastSync = repository.findCursor(shop.tenantId(), shop.shopId())
                    .map(YouzanSyncCursor::lastSyncAt).orElse(policy.initialStartTime());
            Instant start = lastSync.minusSeconds(policy.overlapSeconds());
            if (!start.isBefore(now)) {
                return;
            }
            String cursor = objectMapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", start, now, now, 1));
            managementService.syncScheduled(shop.tenantId(), shop.shopId(), cursor, policy.pageSize());
        } catch (Exception ex) {
            log.warn("有赞增量同步失败，shopId={}，原因={}", shop.shopId(), ex.getMessage());
        }
    }
}
