package com.whale.crm.risk.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.risk.api.dto.RiskSyncResponse;
import com.whale.crm.risk.domain.RiskSourceRecord;
import com.whale.crm.settlement.application.ReconciliationService;
import com.whale.crm.settlement.api.dto.ReconciliationResponse;
import com.whale.crm.settlement.domain.ReconciliationIssue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RiskSourceSynchronizer {
    private static final int SOURCE_PAGE_SIZE = 100;
    private static final int MAX_SYNC_PAGES = 100;

    private final YouzanManagementService youzanService;
    private final ReconciliationService reconciliationService;
    private final RiskCaseRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    public RiskSourceSynchronizer(YouzanManagementService youzanService,
                                  ReconciliationService reconciliationService,
                                  RiskCaseRegistrationService registrationService,
                                  ObjectMapper objectMapper) {
        this.youzanService = youzanService;
        this.reconciliationService = reconciliationService;
        this.registrationService = registrationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RiskSyncResponse sync(CurrentUser user) {
        int createdCount = importYouzanIssues(user.tenantId());
        ReconciliationResponse reconciliation = reconciliationService.reconcile(user.tenantId(), user, null);
        for (ReconciliationIssue issue : reconciliation.issues()) {
            if (issue.resourceId() == null) {
                continue;
            }
            RiskCaseRegistrationService.RegistrationResult outcome = registrationService.registerOutcome(
                    new RiskSourceRecord(user.tenantId(), issue.checkType(),
                            reconciliationSeverity(issue.checkType()), reconciliationSourceType(issue.checkType()),
                            issue.resourceId().toString(), reconciliationEvidence(issue)),
                    null);
            if (outcome != null && outcome.created()) {
                createdCount++;
            }
        }
        return new RiskSyncResponse(createdCount, 0, Instant.now());
    }

    private int importYouzanIssues(long tenantId) {
        int createdCount = 0;
        for (int page = 1; page <= MAX_SYNC_PAGES; page++) {
            PageResponse<YouzanSyncIssue> result = youzanService.issues(tenantId, null, page, SOURCE_PAGE_SIZE);
            for (YouzanSyncIssue issue : result.items()) {
                if ("RESOLVED".equals(issue.status())) {
                    continue;
                }
                RiskCaseRegistrationService.RegistrationResult outcome = registrationService.registerOutcome(
                        new RiskSourceRecord(tenantId, issue.issueType(),
                                youzanSeverity(issue.issueType()), "YOUZAN_SYNC_ISSUE", issue.id().toString(),
                                youzanEvidence(issue)),
                        null);
                if (outcome != null && outcome.created()) {
                    createdCount++;
                }
            }
            long consumed = (long) page * SOURCE_PAGE_SIZE;
            if (result.items().isEmpty() || result.items().size() < SOURCE_PAGE_SIZE || consumed >= result.total()) {
                break;
            }
        }
        return createdCount;
    }

    private String youzanEvidence(YouzanSyncIssue issue) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("issueId", issue.id());
        evidence.put("eventId", issue.eventId());
        evidence.put("issueType", issue.issueType());
        evidence.put("sourceStatus", issue.status());
        evidence.put("detail", issue.detailMasked());
        evidence.put("lastError", issue.lastError());
        evidence.put("attempts", issue.attempts());
        evidence.put("nextRetryAt", issue.nextRetryAt());
        return json(evidence);
    }

    private String reconciliationEvidence(ReconciliationIssue issue) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("checkType", issue.checkType());
        evidence.put("resourceId", issue.resourceId());
        evidence.put("institutionId", issue.institutionId());
        evidence.put("expectedCount", issue.expectedCount());
        evidence.put("actualCount", issue.actualCount());
        evidence.put("expectedFen", issue.expectedFen());
        evidence.put("actualFen", issue.actualFen());
        evidence.put("detail", issue.detail());
        return json(evidence);
    }

    private String reconciliationSourceType(String checkType) {
        return switch (checkType) {
            case "ENTITLEMENT_LEDGER" -> "ENTITLEMENT";
            case "ORDER_PAYMENT_CASH", "ORDER_REFUND_CASH" -> "ORDER";
            case "REFUND_ALLOCATION" -> "REFUND";
            case "SETTLEMENT_PAYMENT" -> "SETTLEMENT_BATCH";
            case "UNBATCHED_REDEMPTION" -> "REDEMPTION";
            default -> "RECONCILIATION";
        };
    }

    private String youzanSeverity(String issueType) {
        return switch (issueType) {
            case "PAYMENT_MISMATCH", "DEAD_LETTER" -> "HIGH";
            case "RETRY" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private String reconciliationSeverity(String checkType) {
        return switch (checkType) {
            case "UNBATCHED_REDEMPTION" -> "MEDIUM";
            default -> "HIGH";
        };
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("风险来源证据序列化失败", ex);
        }
    }
}
