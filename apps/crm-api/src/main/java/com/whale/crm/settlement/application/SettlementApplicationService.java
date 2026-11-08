package com.whale.crm.settlement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.settlement.api.dto.CreateSettlementBatchRequest;
import com.whale.crm.settlement.api.dto.RecordSettlementPaymentRequest;
import com.whale.crm.settlement.api.dto.SettlementBatchDetail;
import com.whale.crm.settlement.api.dto.VerifySettlementBatchRequest;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementLine;
import com.whale.crm.settlement.domain.SettlementPayment;
import com.whale.crm.settlement.domain.SettlementRedemption;
import com.whale.crm.settlement.domain.SettlementRepository;
import com.whale.crm.settlement.domain.SettlementScope;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SettlementApplicationService {
    private static final String CREATE_SCOPE = "settlement.batch.create";
    private static final String VERIFY_SCOPE = "settlement.batch.verify";
    private static final String PAY_SCOPE = "settlement.payment.record";

    private final SettlementRepository repository;
    private final InstitutionRepository institutionRepository;
    private final InstitutionAccessSupport institutionAccess;
    private final FileApplicationService fileOperations;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SettlementApplicationService(SettlementRepository repository, InstitutionRepository institutionRepository,
                                        InstitutionAccessSupport institutionAccess, FileApplicationService fileOperations,
                                        IdempotencyService idempotencyService, AuditService auditService, OutboxService outboxService,
                                        ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.institutionRepository = institutionRepository;
        this.institutionAccess = institutionAccess;
        this.fileOperations = fileOperations;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<SettlementBatch> search(long tenantId, CurrentUser user, Long institutionId,
                                                String settlementType, String status, int page, int size) {
        SettlementScope scope = SettlementAccess.scope(tenantId, user, institutionId, institutionAccess);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchBatches(tenantId, scope, institutionId, settlementType, status, offset, size),
                repository.countBatches(tenantId, scope, institutionId, settlementType, status), page, size);
    }

    @Transactional(readOnly = true)
    public SettlementBatchDetail detail(long tenantId, CurrentUser user, long batchId) {
        SettlementBatch batch = repository.findBatch(tenantId, batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "结算批次不存在"));
        SettlementAccess.requireVisible(tenantId, user, batch, institutionAccess);
        return detail(tenantId, batch);
    }

    public SettlementBatchDetail create(long tenantId, CurrentUser user, CreateSettlementBatchRequest request,
                                        String idempotencyKey) {
        SettlementAccess.requireEmployeeWrite(user);
        String key = requireKey(idempotencyKey);
        List<Long> redemptionIds = normalizedRedemptionIds(request.redemptionIds());
        validateTypeAndPeriod(request.settlementType(), request.periodKey());
        String payload = canonical(new CreateCanonical(request.institutionId(), request.settlementType(),
                request.periodKey(), redemptionIds));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, user, resourceId(replay.get()));
        }
        try {
            return transactionTemplate.execute(status -> doCreate(tenantId, user, request, redemptionIds, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayCreate(tenantId, user, request, redemptionIds, key, payload));
        }
    }

    public SettlementBatchDetail verify(long tenantId, CurrentUser user, long batchId,
                                        VerifySettlementBatchRequest request, String idempotencyKey) {
        SettlementAccess.requireEmployeeWrite(user);
        String key = requireKey(idempotencyKey);
        String payload = canonical(new VerifyCanonical(batchId, request.version(), request.ruleVersion(), request.grossFen(),
                request.commissionFen(), request.netPayableFen(), request.verificationEvidence()));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, VERIFY_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, user, batchId);
        }
        return transactionTemplate.execute(status -> doVerify(tenantId, user, batchId, request, key, payload));
    }

    public SettlementBatchDetail pay(long tenantId, CurrentUser user, long batchId,
                                     RecordSettlementPaymentRequest request, String idempotencyKey) {
        SettlementAccess.requireEmployeeWrite(user);
        String key = requireKey(idempotencyKey);
        String payload = canonical(new PaymentCanonical(batchId, request.version(), request.amountFen(), request.bankTradeNo(),
                request.paidAt().toString(), request.voucherFileId(), request.evidenceRef()));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, PAY_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, user, batchId);
        }
        try {
            return transactionTemplate.execute(status -> doPay(tenantId, user, batchId, request, key, payload));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "银行流水号或幂等键已存在");
        }
    }

    private SettlementBatchDetail doCreate(long tenantId, CurrentUser user, CreateSettlementBatchRequest request,
                                           List<Long> redemptionIds, String key, String payload) {
        Optional<SettlementBatch> existing = repository.findBatchByPeriod(tenantId, request.institutionId(),
                request.settlementType(), request.periodKey());
        if (existing.isPresent()) {
            return sameBatchOrConflict(tenantId, existing.get(), redemptionIds);
        }
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, user, resourceId(replay.get()));
        }
        InstitutionProfile institution = institutionRepository.findProfile(tenantId, request.institutionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在"));
        if (!request.settlementType().equals(institution.settlementType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次结算方式必须与机构当前结算方式一致");
        }
        List<SettlementRedemption> redemptions = repository.lockRedemptions(tenantId, redemptionIds);
        if (redemptions.size() != redemptionIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分核销不存在或不属于当前租户");
        }
        for (SettlementRedemption redemption : redemptions) {
            if (!"POSTED".equals(redemption.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "只有真实 POSTED 核销才能进入结算批次");
            }
            if (!request.institutionId().equals(redemption.institutionId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "不能将其他机构核销纳入本机构批次");
            }
            if ("MONTHLY".equals(request.settlementType())) {
                YearMonth serviceMonth = YearMonth.from(redemption.serviceAt().atZone(ZoneOffset.UTC));
                if (!request.periodKey().equals(serviceMonth.toString())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "月结核销服务月份与批次期间不一致");
                }
            } else if (!request.periodKey().equals("REDEMPTION:" + redemption.id())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "次结期间必须对应唯一核销 REDEMPTION:{id}");
            }
            if (repository.findLineByRedemption(tenantId, redemption.id()).isPresent()) {
                throw new BusinessException(ErrorCode.CONFLICT, "同一核销不能重复进入月结或次结批次");
            }
        }
        String rateSnapshot = rateSnapshot(institution);
        SettlementBatch draft = new SettlementBatch(null, institution.id(), request.settlementType(), request.periodKey(),
                "DRAFT", "PENDING_VERIFICATION", 0L, 0L, 0L, 0L, null, null, null, null, null);
        repository.insertBatch(tenantId, draft);
        SettlementBatch created = repository.findBatchByPeriod(tenantId, institution.id(), request.settlementType(),
                request.periodKey()).orElseThrow();
        for (Long redemptionId : redemptionIds) {
            repository.insertLine(tenantId, created.id(), redemptionId, rateSnapshot);
        }
        SettlementBatchDetail after = detail(tenantId, created);
        auditService.record(tenantId, user.userId(), "SETTLEMENT_BATCH_CREATE", "SETTLEMENT_BATCH",
                created.id().toString(), null, after, "真实POSTED核销入批，金额待人工核准");
        idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, key, "SETTLEMENT_BATCH",
                created.id().toString(), payload);
        return after;
    }

    private SettlementBatchDetail doVerify(long tenantId, CurrentUser user, long batchId,
                                           VerifySettlementBatchRequest request, String key, String payload) {
        SettlementBatch before = repository.findBatchForUpdate(tenantId, batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "结算批次不存在"));
        SettlementAccess.requireVisible(tenantId, user, before, institutionAccess);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, VERIFY_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, before);
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算批次版本已变化，请刷新后重试");
        }
        if (!"DRAFT".equals(before.status()) || !"PENDING_VERIFICATION".equals(before.amountStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待核准的草稿批次可以人工核准");
        }
        long net;
        try {
            net = Math.subtractExact(request.grossFen(), request.commissionFen());
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "核准金额超出支持范围");
        }
        if (request.grossFen() < 0 || request.commissionFen() < 0 || request.netPayableFen() < 0
                || net != request.netPayableFen()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "人工核准金额必须非负且满足 gross-commission=net 等式");
        }
        if (repository.updateVerification(tenantId, batchId, request.version(), request.grossFen(),
                request.commissionFen(), request.netPayableFen(), request.ruleVersion().strip(),
                request.verificationEvidence().strip()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算批次已被其他操作修改");
        }
        SettlementBatch afterBatch = repository.findBatch(tenantId, batchId).orElseThrow();
        SettlementBatchDetail after = detail(tenantId, afterBatch);
        auditService.record(tenantId, user.userId(), "SETTLEMENT_BATCH_VERIFY", "SETTLEMENT_BATCH",
                Long.toString(batchId), before, after, request.verificationEvidence());
        idempotencyService.recordSuccess(tenantId, VERIFY_SCOPE, key, "SETTLEMENT_BATCH", Long.toString(batchId), payload);
        return after;
    }

    private SettlementBatchDetail doPay(long tenantId, CurrentUser user, long batchId,
                                        RecordSettlementPaymentRequest request, String key, String payload) {
        SettlementBatch before = repository.findBatchForUpdate(tenantId, batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "结算批次不存在"));
        SettlementAccess.requireVisible(tenantId, user, before, institutionAccess);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, PAY_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, before);
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算批次版本已变化，请刷新后重试");
        }
        if (!"VERIFIED".equals(before.amountStatus()) || !List.of("CONFIRMED", "PARTIALLY_PAID").contains(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "待核实金额必须经人工核准后才能付款");
        }
        if (request.amountFen() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "付款金额必须大于0");
        }
        fileOperations.requireCleanUsable(tenantId, user, request.voucherFileId(), FileClassification.PAYMENT, null);
        if (repository.findPaymentByTradeNo(tenantId, request.bankTradeNo().strip()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "银行流水号已登记，禁止重复付款");
        }
        long paid;
        try {
            paid = Math.addExact(before.paidFen(), request.amountFen());
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "累计付款金额超出支持范围");
        }
        if (paid > before.netPayableFen()) {
            throw new BusinessException(ErrorCode.CONFLICT, "分次付款累计金额不能超过核准净额");
        }
        String newStatus = paid == before.netPayableFen() ? "PAID" : "PARTIALLY_PAID";
        SettlementPayment payment = new SettlementPayment(null, batchId, request.amountFen(), request.bankTradeNo().strip(),
                request.paidAt(), request.voucherFileId(), request.evidenceRef().strip(), user.userId(), null);
        repository.insertPayment(tenantId, payment);
        if (repository.updatePayment(tenantId, batchId, request.version(), paid, newStatus) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算批次已被其他付款操作修改");
        }
        SettlementPayment persisted = repository.findPaymentByTradeNo(tenantId, request.bankTradeNo().strip()).orElseThrow();
        outboxService.append(tenantId, "settlement.payment.recorded.v1:" + persisted.id(), "settlement.payment.recorded.v1",
                "settlement_payment", persisted.id().toString(), Map.of("batchId", batchId, "paymentId", persisted.id(),
                        "institutionId", before.institutionId(), "amountFen", request.amountFen(), "bankTradeNo", request.bankTradeNo().strip()));
        SettlementBatchDetail after = detail(tenantId, repository.findBatch(tenantId, batchId).orElseThrow());
        auditService.record(tenantId, user.userId(), "SETTLEMENT_PAYMENT_RECORD", "SETTLEMENT_BATCH",
                Long.toString(batchId), before, after, request.evidenceRef());
        idempotencyService.recordSuccess(tenantId, PAY_SCOPE, key, "SETTLEMENT_BATCH", Long.toString(batchId), payload);
        return after;
    }

    private SettlementBatchDetail replayCreate(long tenantId, CurrentUser user, CreateSettlementBatchRequest request,
                                               List<Long> redemptionIds, String key, String payload) {
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay.isPresent()) {
            return detail(tenantId, user, resourceId(replay.get()));
        }
        SettlementBatch existing = repository.findBatchByPeriod(tenantId, request.institutionId(),
                request.settlementType(), request.periodKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "核销已进入其他结算批次"));
        return sameBatchOrConflict(tenantId, existing, redemptionIds);
    }

    private SettlementBatchDetail sameBatchOrConflict(long tenantId, SettlementBatch existing, List<Long> redemptionIds) {
        List<Long> existingIds = repository.findLines(tenantId, existing.id()).stream()
                .map(SettlementLine::redemptionId).sorted().toList();
        if (!existingIds.equals(redemptionIds)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一机构期间批次已存在但核销内容不同");
        }
        return detail(tenantId, existing);
    }

    private SettlementBatchDetail detail(long tenantId, SettlementBatch batch) {
        return new SettlementBatchDetail(batch, repository.findLines(tenantId, batch.id()),
                repository.findPayments(tenantId, batch.id()));
    }

    private String rateSnapshot(InstitutionProfile institution) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("institutionVersion", institution.version());
        snapshot.put("settlementType", institution.settlementType());
        snapshot.put("onlineRate", institution.onlineRate());
        snapshot.put("offlineRate", institution.offlineRate());
        snapshot.put("platformRate", institution.platformRate());
        snapshot.put("otherRateRule", institution.otherRateRule());
        return json(snapshot);
    }

    private List<Long> normalizedRedemptionIds(List<Long> ids) {
        List<Long> normalized = ids.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        if (normalized.size() != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "核销ID不能重复");
        }
        return normalized;
    }

    private void validateTypeAndPeriod(String type, String periodKey) {
        if ("MONTHLY".equals(type)) {
            try {
                YearMonth.parse(periodKey);
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "月结期间必须为YYYY-MM");
            }
        } else if ("PER_REDEMPTION".equals(type)) {
            if (!periodKey.matches("REDEMPTION:[1-9][0-9]*")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "次结期间必须为REDEMPTION:{id}");
            }
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的结算方式");
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供Idempotency-Key");
        }
        return key.strip();
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private String canonical(Object value) {
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("无法序列化结算请求", ex);
        }
    }

    private record CreateCanonical(long institutionId, String settlementType, String periodKey, List<Long> redemptionIds) {
    }

    private record VerifyCanonical(long batchId, long version, String ruleVersion, long grossFen,
                                   long commissionFen, long netPayableFen, String verificationEvidence) {
    }

    private record PaymentCanonical(long batchId, long version, long amountFen, String bankTradeNo,
                                    String paidAt, long voucherFileId, String evidenceRef) {
    }

}
