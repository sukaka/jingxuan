package com.whale.crm.followup.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.followup.api.dto.CreateMonthlyBatchRequest;
import com.whale.crm.followup.api.dto.MonthlyBatchActionRequest;
import com.whale.crm.followup.api.dto.MonthlyBatchCandidateResponse;
import com.whale.crm.followup.api.dto.MonthlyBatchItemResponse;
import com.whale.crm.followup.api.dto.MonthlyBatchPreviewResponse;
import com.whale.crm.followup.api.dto.MonthlyBatchResponse;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import com.whale.crm.followup.domain.FollowupBatchItem;
import com.whale.crm.followup.domain.FollowupCandidate;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class MonthlyBatchService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CREATE_SCOPE = "followup.batch.create";
    private static final String EXECUTE_SCOPE = "followup.batch.execute";
    private static final String RETRY_SCOPE = "followup.batch.retry";

    private final FollowupAutomationRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final MonthlyBatchExecutionService executionService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public MonthlyBatchService(FollowupAutomationRepository repository, CustomerScopeSupport scopeSupport,
                               MonthlyBatchExecutionService executionService, AuditService auditService,
                               IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.executionService = executionService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MonthlyBatchPreviewResponse preview(long tenantId, CurrentUser user, String month, int page, int size) {
        YearMonth orderMonth = parseMonth(month);
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new MonthlyBatchPreviewResponse(month, orderMonth.plusMonths(3).toString(),
                    List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        LocalDateTime from = utcStart(orderMonth);
        LocalDateTime to = utcStart(orderMonth.plusMonths(1));
        List<MonthlyBatchCandidateResponse> items = repository.findCandidates(tenantId, from, to,
                        query.scopeType(), query.teamIds(), query.selfId(), (page - 1) * size, size)
                .stream().map(this::toCandidateResponse).toList();
        long total = repository.countCandidates(tenantId, from, to,
                query.scopeType(), query.teamIds(), query.selfId());
        return new MonthlyBatchPreviewResponse(month, orderMonth.plusMonths(3).toString(), items, total, page, size);
    }

    @Transactional
    public MonthlyBatchResponse create(long tenantId, CurrentUser user,
                                       CreateMonthlyBatchRequest request, String idempotencyKey) {
        YearMonth orderMonth = parseMonth(request.orderMonth());
        String key = requireKey(idempotencyKey);
        String payload = canonical(request);
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, key, payload).orElse(null);
        if (replay != null) {
            requireBatchResource(replay, null);
            return detail(tenantId, user, Long.parseLong(replay.getResourceId()));
        }
        FollowupBatch existing = repository.findBatchByMonth(tenantId, request.orderMonth()).orElse(null);
        if (existing != null) {
            MonthlyBatchResponse response = detail(tenantId, user, existing.id());
            idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, key, "FOLLOWUP_BATCH",
                    Long.toString(existing.id()), payload);
            return response;
        }

        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号没有可创建回访批次的数据范围");
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        List<FollowupCandidate> candidates = repository.findCandidates(tenantId, utcStart(orderMonth),
                        utcStart(orderMonth.plusMonths(1)), query.scopeType(), query.teamIds(), query.selfId(), null, null)
                .stream().map(this::toCandidate).toList();
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该订单月没有数据范围内的回访候选");
        }
        repository.insertBatch(tenantId, request.orderMonth(), user.userId());
        FollowupBatch batch = repository.findBatchByMonth(tenantId, request.orderMonth()).orElseThrow();
        for (FollowupCandidate candidate : candidates) {
            repository.insertItem(tenantId, batch.id(), candidate);
            FollowupBatchItem item = repository.findItemByCustomer(
                    tenantId, batch.id(), candidate.customerId()).orElseThrow();
            for (Long orderId : candidate.orderIds()) {
                repository.insertBatchOrder(tenantId, batch.id(), item.id(), orderId);
            }
        }
        auditService.record(tenantId, user.userId(), "FOLLOWUP_BATCH_CREATE", "FOLLOWUP_BATCH",
                Long.toString(batch.id()), null,
                Map.of("orderMonth", batch.orderMonth(), "candidateCount", candidates.size()), "预览后创建批次快照");
        idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, key, "FOLLOWUP_BATCH",
                Long.toString(batch.id()), payload);
        return detail(tenantId, user, batch.id());
    }

    @Transactional(readOnly = true)
    public MonthlyBatchResponse detail(long tenantId, CurrentUser user, long batchId) {
        FollowupBatch batch = repository.findBatchById(tenantId, batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "回访批次不存在或不可见"));
        List<FollowupBatchItem> items = repository.findItems(tenantId, batchId);
        for (FollowupBatchItem item : items) {
            scopeSupport.requireVisible(tenantId, item.customerId(), user);
        }
        List<MonthlyBatchItemResponse> responses = items.stream()
                .map(item -> new MonthlyBatchItemResponse(item.id(), item.customerId(), item.customerName(),
                        item.ownerStaffId(), item.teamId(), item.dueAt(), item.taskId(), item.status(), item.reason(),
                        item.version(), repository.findOrderIds(tenantId, batchId, item.id()))).toList();
        return MonthlyBatchResponse.of(batch.id(), batch.orderMonth(), batch.status(), batch.version(),
                batch.retryCount(), batch.nextRetryAt(), batch.lastErrorMasked(), responses);
    }

    public MonthlyBatchResponse execute(long tenantId, CurrentUser user, long batchId,
                                        MonthlyBatchActionRequest request, String idempotencyKey) {
        return execute(tenantId, user, batchId, request, idempotencyKey, false);
    }

    public MonthlyBatchResponse retry(long tenantId, CurrentUser user, long batchId,
                                      MonthlyBatchActionRequest request, String idempotencyKey) {
        return execute(tenantId, user, batchId, request, idempotencyKey, true);
    }

    private MonthlyBatchResponse execute(long tenantId, CurrentUser user, long batchId,
                                         MonthlyBatchActionRequest request, String idempotencyKey, boolean retry) {
        detail(tenantId, user, batchId);
        String scope = retry ? RETRY_SCOPE : EXECUTE_SCOPE;
        String key = requireKey(idempotencyKey);
        String payload = canonical(new ActionCanonical(batchId, request.version()));
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, scope, key, payload).orElse(null);
        if (replay != null) {
            requireBatchResource(replay, batchId);
            return detail(tenantId, user, batchId);
        }
        FollowupBatch batch = repository.findBatchById(tenantId, batchId).orElseThrow();
        String requiredStatus = retry ? FollowupBatch.FAILED : FollowupBatch.READY;
        if (!requiredStatus.equals(batch.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    retry ? "只有失败批次可重试" : "只有待执行批次可执行");
        }
        if (batch.version() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次已被他人修改，请刷新后重试");
        }
        executionService.run(batch, user,
                new MonthlyBatchExecutionService.ExecutionIdempotency(scope, key, payload));
        return detail(tenantId, user, batchId);
    }

    private FollowupCandidate toCandidate(FollowupAutomationRepository.CandidateRow row) {
        Instant dueAt = row.firstOrderedAt().atZone(BUSINESS_ZONE).plusMonths(3).toInstant();
        List<Long> orderIds = Arrays.stream(row.orderIds().split(",")).map(Long::parseLong).toList();
        return new FollowupCandidate(row.customerId(), row.customerName(), row.ownerStaffId(), row.teamId(),
                dueAt, orderIds);
    }

    private MonthlyBatchCandidateResponse toCandidateResponse(FollowupAutomationRepository.CandidateRow row) {
        FollowupCandidate candidate = toCandidate(row);
        return new MonthlyBatchCandidateResponse(candidate.customerId(), candidate.customerName(),
                candidate.ownerStaffId(), candidate.teamId(), candidate.dueAt(), candidate.orderIds());
    }

    private LocalDateTime utcStart(YearMonth month) {
        return LocalDateTime.ofInstant(month.atDay(1).atStartOfDay(BUSINESS_ZONE).toInstant(), java.time.ZoneOffset.UTC);
    }

    private YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "orderMonth 格式必须为 YYYY-MM");
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "orderMonth 格式必须为 YYYY-MM");
        }
    }

    private void requireBatchResource(IdempotencyRecord record, Long expectedBatchId) {
        if (!"FOLLOWUP_BATCH".equals(record.getResourceType())
                || expectedBatchId != null && !Long.toString(expectedBatchId).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于该回访批次");
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String canonical(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("回访批次请求规范化失败", ex);
        }
    }

    private record ActionCanonical(long batchId, long version) {
    }
}
