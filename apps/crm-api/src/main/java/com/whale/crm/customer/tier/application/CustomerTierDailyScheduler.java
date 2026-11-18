package com.whale.crm.customer.tier.application;

import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.platform.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class CustomerTierDailyScheduler {
    private static final Logger log = LoggerFactory.getLogger(CustomerTierDailyScheduler.class);
    private static final int BATCH_SIZE = 200;

    private final CustomerTierRepository repository;
    private final CustomerTierRecalculationService recalculationService;

    public CustomerTierDailyScheduler(CustomerTierRepository repository,
                                      CustomerTierRecalculationService recalculationService) {
        this.repository = repository;
        this.recalculationService = recalculationService;
    }

    @Scheduled(cron = "${app.customer-tier.daily-cron:0 20 2 * * *}")
    public void runDaily() {
        Instant now = Instant.now();
        for (CustomerTierRule rule : repository.findAllActiveRules()) {
            LocalDate localDate = now.atZone(ZoneId.of(rule.zoneId())).toLocalDate();
            long afterId = 0;
            List<Long> customerIds;
            do {
                customerIds = repository.findCustomerIdsAfter(rule.tenantId(), afterId, BATCH_SIZE);
                for (Long customerId : customerIds) {
                    recalculateCustomer(rule, customerId, now, localDate);
                }
                if (!customerIds.isEmpty()) {
                    afterId = customerIds.getLast();
                }
            } while (customerIds.size() == BATCH_SIZE);
        }
    }

    private void recalculateCustomer(CustomerTierRule rule, long customerId, Instant now, LocalDate localDate) {
        try {
            recalculationService.recalculate(rule.tenantId(), customerId, now, "DAILY",
                    "daily:" + rule.id() + ":" + localDate + ":" + customerId, null);
        } catch (BusinessException ex) {
            log.warn("客户每日分级重算未完成: tenantId={}, customerId={}, code={}",
                    rule.tenantId(), customerId, ex.errorCode());
        }
    }
}
