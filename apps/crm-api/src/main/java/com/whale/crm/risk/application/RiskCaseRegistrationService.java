package com.whale.crm.risk.application;

import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseRepository;
import com.whale.crm.risk.domain.RiskResolvedIds;
import com.whale.crm.risk.domain.RiskSourceRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RiskCaseRegistrationService {
    private final RiskCaseRepository repository;
    private final RiskEvidenceMasker masker;
    private final AuditService auditService;

    public RiskCaseRegistrationService(RiskCaseRepository repository, RiskEvidenceMasker masker,
                                       AuditService auditService) {
        this.repository = repository;
        this.masker = masker;
        this.auditService = auditService;
    }

    @Transactional
    public RiskCase register(RiskSourceRecord source, Long actorUserId) {
        return registerOutcome(source, actorUserId).riskCase();
    }

    @Transactional
    public RegistrationResult registerOutcome(RiskSourceRecord source, Long actorUserId) {
        var existing = repository.findBySource(source.tenantId(), source.sourceType(), source.sourceRef(),
                source.riskType());
        if (existing.isPresent()) {
            RiskCase current = existing.get();
            if (!"CLOSED".equals(current.status())
                    && (current.customerId() == null || current.orderId() == null)) {
                RiskResolvedIds resolved = resolveSourceLink(source.tenantId(), source.sourceType(),
                        source.sourceRef());
                if (resolved != null && ((current.customerId() == null && resolved.customerId() != null)
                        || (current.orderId() == null && resolved.orderId() != null))) {
                    repository.backfillLinkIfAbsent(source.tenantId(), current.id(),
                            resolved.customerId(), resolved.orderId());
                    current = repository.findById(source.tenantId(), current.id()).orElse(current);
                }
            }
            return new RegistrationResult(current, false);
        }
        RiskResolvedIds resolved = resolveSourceLink(source.tenantId(), source.sourceType(), source.sourceRef());
        RiskCase input = new RiskCase(null, source.tenantId(), "RC" + UlidCreator.getUlid(),
                source.riskType(), source.severity(), source.sourceType(), source.sourceRef(),
                resolved == null ? null : resolved.customerId(), resolved == null ? null : resolved.orderId(),
                "OPEN", null, masker.mask(source.evidence()), null, null, null, null, 0);
        try {
            repository.insert(input);
        } catch (DuplicateKeyException ex) {
            RiskCase raced = repository.findBySourceForUpdate(source.tenantId(), source.sourceType(),
                    source.sourceRef(), source.riskType()).orElseThrow(() -> ex);
            return new RegistrationResult(raced, false);
        }
        RiskCase created = repository.findBySource(source.tenantId(), source.sourceType(), source.sourceRef(),
                source.riskType()).orElseThrow();
        repository.insertHistory(new RiskCaseHistory(null, source.tenantId(), created.id(), "REGISTER", null,
                "OPEN", actorUserId, "真实异常来源登记", null));
        auditService.record(source.tenantId(), actorUserId, "RISK_CASE_REGISTER", "RISK_CASE",
                created.id().toString(), null, snapshot(created),
                "真实来源幂等登记: " + source.sourceType() + "/" + source.sourceRef());
        return new RegistrationResult(created, true);
    }

    static Long parsePositiveLong(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        if (ref.charAt(0) < '1' || ref.charAt(0) > '9') {
            return null;
        }
        for (int i = 1; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            long value = Long.parseLong(ref);
            return value > 0L ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private RiskResolvedIds resolveSourceLink(long tenantId, String sourceType, String sourceRef) {
        Long sourceId = parsePositiveLong(sourceRef);
        if (sourceId == null || sourceType == null) {
            return null;
        }
        return switch (sourceType) {
            case "ORDER" -> repository.resolveOrderLink(tenantId, sourceId).orElse(null);
            case "YOUZAN_SYNC_ISSUE" -> repository.resolveYouzanIssueLink(tenantId, sourceId).orElse(null);
            case "REFUND" -> repository.resolveRefundLink(tenantId, sourceId).orElse(null);
            case "ENTITLEMENT" -> repository.resolveEntitlementLink(tenantId, sourceId).orElse(null);
            case "REDEMPTION" -> repository.resolveRedemptionLink(tenantId, sourceId).orElse(null);
            default -> null;
        };
    }

    public record RegistrationResult(RiskCase riskCase, boolean created) {
    }

    private Map<String, Object> snapshot(RiskCase riskCase) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("riskCaseNo", riskCase.riskCaseNo());
        snapshot.put("riskType", riskCase.riskType());
        snapshot.put("severity", riskCase.severity());
        snapshot.put("sourceType", riskCase.sourceType());
        snapshot.put("sourceRef", riskCase.sourceRef());
        snapshot.put("status", riskCase.status());
        snapshot.put("evidence", riskCase.evidence());
        snapshot.put("version", riskCase.version());
        return snapshot;
    }
}
