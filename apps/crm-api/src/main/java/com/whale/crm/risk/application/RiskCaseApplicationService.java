package com.whale.crm.risk.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.risk.api.dto.ClaimRiskCaseRequest;
import com.whale.crm.risk.api.dto.CloseRiskCaseRequest;
import com.whale.crm.risk.api.dto.ResolveRiskCaseRequest;
import com.whale.crm.risk.api.dto.RiskCaseDetailResponse;
import com.whale.crm.risk.api.dto.RiskCaseHistoryResponse;
import com.whale.crm.risk.api.dto.RiskCaseResponse;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseQuery;
import com.whale.crm.risk.domain.RiskCaseRepository;
import com.whale.crm.risk.domain.RiskCustomerLink;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiskCaseApplicationService {
    private final RiskCaseRepository repository;
    private final RiskAccessService accessService;
    private final RiskSourceSynchronizer sourceSynchronizer;
    private final AuditService auditService;
    private final RiskEvidenceMasker masker;

    public RiskCaseApplicationService(RiskCaseRepository repository, RiskAccessService accessService,
                                      RiskSourceSynchronizer sourceSynchronizer, AuditService auditService,
                                      RiskEvidenceMasker masker) {
        this.repository = repository;
        this.accessService = accessService;
        this.sourceSynchronizer = sourceSynchronizer;
        this.auditService = auditService;
        this.masker = masker;
    }

    @Transactional(readOnly = true)
    public RiskCaseDetailResponse get(CurrentUser user, long id) {
        accessService.require(user, "risk:read");
        RiskCase riskCase = repository.findById(user.tenantId(), id).orElseThrow(this::notFound);
        return detail(riskCase);
    }

    @Transactional
    public PageResponse<RiskCaseResponse> search(CurrentUser user, String status, String severity, String riskType,
                                                 String sourceType, String sourceRef, Long ownerUserId,
                                                 String keyword, int page, int size) {
        accessService.require(user, "risk:read");
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分页参数无效");
        }
        sourceSynchronizer.sync(user);
        RiskCaseQuery query = new RiskCaseQuery(user.tenantId(), status, severity, riskType, sourceType, sourceRef,
                ownerUserId, keyword, (page - 1) * size, size);
        List<RiskCase> riskCases = repository.search(query);
        Map<Long, RiskCustomerLink> customerLinks = customerLinks(user.tenantId(), riskCases);
        return new PageResponse<>(riskCases.stream().map(riskCase -> view(riskCase, customerLinks.get(riskCase.id())))
                .toList(), repository.countSearch(query), page, size);
    }

    @Transactional
    public RiskCaseDetailResponse claim(CurrentUser user, long id, ClaimRiskCaseRequest request) {
        accessService.require(user, "risk:write");
        return transition(user, id, request.version(), "OPEN", "ASSIGNED", "CLAIM",
                optionalMasked(request.reason()));
    }

    @Transactional
    public RiskCaseDetailResponse resolve(CurrentUser user, long id, ResolveRiskCaseRequest request) {
        accessService.require(user, "risk:write");
        return transition(user, id, request.version(), "ASSIGNED", "RESOLVED", "RESOLVE",
                requiredMasked(request.resolution(), "处理结论"));
    }

    @Transactional
    public RiskCaseDetailResponse close(CurrentUser user, long id, CloseRiskCaseRequest request) {
        accessService.require(user, "risk:write");
        return transition(user, id, request.version(), "RESOLVED", "CLOSED", "CLOSE",
                requiredMasked(request.reason(), "关闭理由"));
    }

    private RiskCaseDetailResponse transition(CurrentUser user, long id, Long version, String expectedStatus,
                                              String targetStatus, String action, String note) {
        RiskCase before = repository.findByIdForUpdate(user.tenantId(), id).orElseThrow(this::notFound);
        if (version == null || before.version() != version) {
            throw new BusinessException(ErrorCode.CONFLICT, "风险单版本已变化，请刷新后重试");
        }
        if (!expectedStatus.equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅 " + expectedStatus + " 状态可执行" + action);
        }
        Long owner = before.ownerUserId();
        if ("CLAIM".equals(action)) {
            owner = user.userId();
        }
        String resolution = "RESOLVE".equals(action) ? note : before.resolution();
        Instant resolvedAt = "RESOLVE".equals(action) ? Instant.now() : before.resolvedAt();
        if (repository.updateStatus(user.tenantId(), id, version, expectedStatus, targetStatus, owner,
                resolution, resolvedAt) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "风险单状态已变化，请刷新后重试");
        }
        repository.insertHistory(new RiskCaseHistory(null, user.tenantId(), id, action, before.status(),
                targetStatus, user.userId(), note, null));
        RiskCase after = repository.findById(user.tenantId(), id).orElseThrow(this::notFound);
        auditService.record(user.tenantId(), user.userId(), "RISK_CASE_" + action, "RISK_CASE", Long.toString(id),
                snapshot(before), snapshot(after), note);
        return detail(after);
    }

    private RiskCaseDetailResponse detail(RiskCase riskCase) {
        RiskCustomerLink customerLink = customerLinks(riskCase.tenantId(), List.of(riskCase)).get(riskCase.id());
        return new RiskCaseDetailResponse(view(riskCase, customerLink),
                repository.findHistory(riskCase.tenantId(), riskCase.id())
                        .stream().map(RiskCaseHistoryResponse::of).toList());
    }

    private Map<Long, RiskCustomerLink> customerLinks(long tenantId, List<RiskCase> riskCases) {
        if (riskCases.isEmpty()) {
            return Map.of();
        }
        Map<Long, RiskCustomerLink> links = new LinkedHashMap<>();
        repository.findCustomerLinks(tenantId, riskCases.stream().map(RiskCase::id).toList())
                .forEach(link -> links.put(link.riskCaseId(), link));
        return links;
    }

    private RiskCaseResponse view(RiskCase riskCase, RiskCustomerLink customerLink) {
        return RiskCaseResponse.of(new RiskCase(riskCase.id(), riskCase.tenantId(), riskCase.riskCaseNo(),
                riskCase.riskType(), riskCase.severity(), riskCase.sourceType(), riskCase.sourceRef(), riskCase.status(),
                riskCase.ownerUserId(), masker.mask(riskCase.evidence()), masker.mask(riskCase.resolution()),
                riskCase.resolvedAt(), riskCase.createdAt(), riskCase.updatedAt(), riskCase.version()), customerLink);
    }

    private Map<String, Object> snapshot(RiskCase riskCase) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", riskCase.status());
        snapshot.put("ownerUserId", riskCase.ownerUserId());
        snapshot.put("resolution", riskCase.resolution());
        snapshot.put("resolvedAt", riskCase.resolvedAt());
        snapshot.put("version", riskCase.version());
        snapshot.put("sourceType", riskCase.sourceType());
        snapshot.put("sourceRef", riskCase.sourceRef());
        return snapshot;
    }

    private String requiredMasked(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        return masker.mask(value.strip());
    }

    private String optionalMasked(String value) {
        return value == null || value.isBlank() ? null : masker.mask(value.strip());
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "风险单不存在或不可见");
    }
}
