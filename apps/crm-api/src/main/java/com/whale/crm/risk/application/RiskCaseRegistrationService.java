package com.whale.crm.risk.application;

import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseRepository;
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
        var existing = repository.findBySource(source.tenantId(), source.sourceType(), source.sourceRef(),
                source.riskType());
        if (existing.isPresent()) {
            return existing.get();
        }
        RiskCase input = new RiskCase(null, source.tenantId(), "RC" + UlidCreator.getUlid(),
                source.riskType(), source.severity(), source.sourceType(), source.sourceRef(), "OPEN", null,
                masker.mask(source.evidence()), null, null, null, null, 0);
        try {
            repository.insert(input);
        } catch (DuplicateKeyException ex) {
            return repository.findBySourceForUpdate(source.tenantId(), source.sourceType(), source.sourceRef(),
                    source.riskType()).orElseThrow(() -> ex);
        }
        RiskCase created = repository.findBySource(source.tenantId(), source.sourceType(), source.sourceRef(),
                source.riskType()).orElseThrow();
        repository.insertHistory(new RiskCaseHistory(null, source.tenantId(), created.id(), "REGISTER", null,
                "OPEN", actorUserId, "真实异常来源登记", null));
        auditService.record(source.tenantId(), actorUserId, "RISK_CASE_REGISTER", "RISK_CASE",
                created.id().toString(), null, snapshot(created),
                "真实来源幂等登记: " + source.sourceType() + "/" + source.sourceRef());
        return created;
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
