package com.whale.crm.institution.application;

import com.whale.crm.institution.api.dto.InstitutionSettlementDefaultResponse;
import com.whale.crm.institution.api.dto.UpdateInstitutionSettlementDefaultRequest;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionSettlementConfig;
import com.whale.crm.institution.domain.InstitutionSettlementConfigRepository;
import com.whale.crm.institution.domain.SettlementDayRule;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class InstitutionSettlementDayService {
    private static final String SAVE_SCOPE = "institution.settlement-default.save";

    private final InstitutionSettlementConfigRepository repository;
    private final InstitutionSupport support;
    private final AuditService auditService;
    private final TransactionTemplate transactions;

    public InstitutionSettlementDayService(InstitutionSettlementConfigRepository repository, InstitutionSupport support,
                                           AuditService auditService, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.support = support;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public InstitutionSettlementDefaultResponse get(long tenantId) {
        return repository.find(tenantId).map(this::response)
                .orElseGet(() -> new InstitutionSettlementDefaultResponse(
                        SettlementDayRule.BUILT_IN_DEFAULT, 0, false, null));
    }

    @Transactional(readOnly = true)
    public SettlementDayRule.MonthlySnapshot monthlySnapshot(long tenantId, InstitutionProfile profile,
                                                              YearMonth period) {
        String defaultDay = repository.find(tenantId)
                .map(InstitutionSettlementConfig::defaultSettlementDay)
                .orElse(SettlementDayRule.BUILT_IN_DEFAULT);
        return SettlementDayRule.monthlySnapshot(period, defaultDay, profile.settlementDay());
    }

    public InstitutionSettlementDefaultResponse save(long tenantId, CurrentUser user,
                                                      UpdateInstitutionSettlementDefaultRequest request,
                                                      String idempotencyKey) {
        support.requireEmployeeWrite(tenantId, user);
        String key = support.requireKey(idempotencyKey);
        String defaultDay = SettlementDayRule.normalize(request.defaultSettlementDay());
        String payload = support.jsonText(new DefaultCanonical(defaultDay, request.version()));
        IdempotencyRecord replay = support.lookup(tenantId, SAVE_SCOPE, key, payload);
        if (replay != null) {
            return get(tenantId);
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> doSave(tenantId, user, defaultDay,
                    request.version(), key, payload)));
        } catch (DuplicateKeyException ex) {
            if (support.lookup(tenantId, SAVE_SCOPE, key, payload) != null) {
                return get(tenantId);
            }
            throw conflict();
        }
    }

    private InstitutionSettlementDefaultResponse doSave(long tenantId, CurrentUser user, String defaultDay,
                                                         long version, String key, String payload) {
        InstitutionSettlementConfig existing = repository.findForUpdate(tenantId).orElse(null);
        if (support.lookup(tenantId, SAVE_SCOPE, key, payload) != null) {
            return get(tenantId);
        }
        if ((existing == null && version != 0) || (existing != null && existing.version() != version)) {
            throw conflict();
        }
        InstitutionSettlementConfig changed = new InstitutionSettlementConfig(
                existing == null ? null : existing.id(), tenantId, defaultDay, user.userId(), null, null,
                existing == null ? 1 : existing.version());
        if (existing == null) {
            repository.insert(tenantId, changed);
        } else if (repository.update(tenantId, existing.id(), existing.version(), changed) != 1) {
            throw conflict();
        }
        InstitutionSettlementConfig saved = repository.find(tenantId).orElseThrow();
        auditService.record(tenantId, user.userId(), "INSTITUTION_SETTLEMENT_DEFAULT_SAVE",
                "INSTITUTION_SETTLEMENT_CONFIG", Long.toString(saved.id()), snapshot(existing), snapshot(saved),
                "仅影响之后生成的月结批次");
        support.record(tenantId, SAVE_SCOPE, key, "INSTITUTION_SETTLEMENT_CONFIG", saved.id(), payload);
        return response(saved);
    }

    private InstitutionSettlementDefaultResponse response(InstitutionSettlementConfig config) {
        return new InstitutionSettlementDefaultResponse(config.defaultSettlementDay(), config.version(), true,
                config.updatedAt());
    }

    private Map<String, Object> snapshot(InstitutionSettlementConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("defaultSettlementDay", config.defaultSettlementDay());
        snapshot.put("version", config.version());
        return snapshot;
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "默认结算日已变化，请刷新后重试");
    }

    private record DefaultCanonical(String defaultSettlementDay, long version) {
    }
}
