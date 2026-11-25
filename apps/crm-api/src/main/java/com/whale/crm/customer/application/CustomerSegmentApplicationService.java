package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.customer.api.dto.CreateCustomerSegmentRequest;
import com.whale.crm.customer.api.dto.CreateSegmentOperationBatchRequest;
import com.whale.crm.customer.api.dto.CustomerListItemResponse;
import com.whale.crm.customer.api.dto.CustomerSegmentBatchResponse;
import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.api.dto.CustomerSegmentResponse;
import com.whale.crm.customer.api.dto.PreviewCustomerSegmentRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerSegmentRequest;
import com.whale.crm.customer.domain.CustomerSegment;
import com.whale.crm.customer.domain.CustomerSegmentBatch;
import com.whale.crm.customer.domain.CustomerSegmentRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class CustomerSegmentApplicationService {
    private static final String CREATE_SCOPE = "customer:segment:create";
    private static final String BATCH_SCOPE = "customer:segment:operation_batch";

    private final CustomerSegmentRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final CustomerApplicationService customerService;
    private final CustomerProjectSupport projectSupport;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CustomerSegmentApplicationService(CustomerSegmentRepository repository, CustomerScopeSupport scopeSupport,
                                             CustomerApplicationService customerService,
                                             CustomerProjectSupport projectSupport,
                                             IdempotencyService idempotencyService, AuditService auditService,
                                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.customerService = customerService;
        this.projectSupport = projectSupport;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerSegmentResponse> search(long tenantId, String keyword, int page, int size) {
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.search(tenantId, keyword, offset, size).stream()
                .map(this::toResponse).toList(), repository.count(tenantId, keyword), page, size);
    }

    @Transactional(readOnly = true)
    public CustomerSegmentResponse get(long tenantId, long id) {
        return toResponse(requireSegment(tenantId, id));
    }

    @Transactional
    public CustomerSegmentResponse create(long tenantId, CurrentUser user, CreateCustomerSegmentRequest input,
                                          String idempotencyKey) {
        if (scopeSupport.isEmptyScope(scopeSupport.scope(user))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前数据范围不能创建动态分组");
        }
        CreateCustomerSegmentRequest request = new CreateCustomerSegmentRequest(input.name().strip(),
                normalize(input.filters()));
        projectSupport.requireExisting(tenantId, projectIds(request.filters()));
        String payload = canonical(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            return get(tenantId, resourceId(replay.orElseThrow()));
        }
        try {
            repository.insert(new CustomerSegment(null, tenantId, request.name(), json(request.filters()), false,
                    user.userId(), null, null, null));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名动态分组已存在");
        }
        CustomerSegment created = repository.findActiveByName(tenantId, request.name()).orElseThrow();
        idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, idempotencyKey, "CUSTOMER_SEGMENT",
                created.id().toString(), payload);
        auditService.record(tenantId, user.userId(), "CUSTOMER_SEGMENT_CREATE", "CUSTOMER_SEGMENT",
                created.id().toString(), null, toResponse(created), null);
        return toResponse(created);
    }

    @Transactional
    public CustomerSegmentResponse update(long tenantId, CurrentUser user, long id,
                                          UpdateCustomerSegmentRequest input) {
        CustomerSegment before = requireSegment(tenantId, id);
        requireSegmentManageable(user, before);
        UpdateCustomerSegmentRequest request = new UpdateCustomerSegmentRequest(input.name().strip(),
                normalize(input.filters()), input.version());
        projectSupport.requireExisting(tenantId, projectIds(request.filters()));
        try {
            if (repository.update(new CustomerSegment(id, tenantId, request.name(), json(request.filters()), false,
                    before.createdBy(), before.createdAt(), before.updatedAt(), request.version())) == 0) {
                throw conflict();
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名动态分组已存在");
        }
        CustomerSegment after = requireSegment(tenantId, id);
        auditService.record(tenantId, user.userId(), "CUSTOMER_SEGMENT_UPDATE", "CUSTOMER_SEGMENT",
                Long.toString(id), toResponse(before), toResponse(after), null);
        return toResponse(after);
    }

    @Transactional
    public void delete(long tenantId, CurrentUser user, long id, long version) {
        CustomerSegment before = requireSegment(tenantId, id);
        requireSegmentManageable(user, before);
        if (repository.softDelete(tenantId, id, version) == 0) {
            throw conflict();
        }
        auditService.record(tenantId, user.userId(), "CUSTOMER_SEGMENT_DELETE", "CUSTOMER_SEGMENT",
                Long.toString(id), toResponse(before), Map.of("deleted", true, "version", version + 1), null);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItemResponse> preview(long tenantId, CurrentUser user,
                                                           PreviewCustomerSegmentRequest request) {
        CustomerSegmentCriteria filters = normalize(request.filters());
        projectSupport.requireExisting(tenantId, projectIds(filters));
        return members(tenantId, user, filters, request.page(), request.size());
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItemResponse> members(long tenantId, CurrentUser user, long segmentId,
                                                           int page, int size) {
        CustomerSegment segment = requireSegment(tenantId, segmentId);
        return members(tenantId, user, filters(segment), page, size);
    }

    @Transactional
    public CustomerSegmentBatchResponse createOperationBatch(long tenantId, CurrentUser user, long segmentId,
                                                              CreateSegmentOperationBatchRequest request,
                                                              String idempotencyKey) {
        String operationType = request.operationType() == null ? "FOLLOWUP" : request.operationType();
        String payload = canonical(Map.of("segmentId", segmentId, "operationType", operationType,
                "version", request.version()));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, BATCH_SCOPE, idempotencyKey, payload);
        if (replay.isPresent()) {
            return toBatchResponse(repository.findBatchById(tenantId, resourceId(replay.orElseThrow())).orElseThrow());
        }
        CustomerSegment segment = requireSegment(tenantId, segmentId);
        if (!segment.version().equals(request.version())) {
            throw conflict();
        }
        CustomerSegmentCriteria filters = filters(segment);
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前数据范围不能创建客户操作批次");
        }
        long total = countMembers(tenantId, user, filters);
        String batchNo = "SG" + UlidCreator.getUlid().toString();
        repository.insertBatch(new CustomerSegmentBatch(null, tenantId, segmentId, batchNo, operationType,
                segment.filterJson(), Math.toIntExact(total), user.userId(), null));
        CustomerSegmentBatch created = repository.findBatchByNo(tenantId, batchNo).orElseThrow();
        for (Long customerId : allMemberIds(tenantId, user, filters)) {
            repository.insertBatchMember(tenantId, created.id(), customerId);
        }
        idempotencyService.recordSuccess(tenantId, BATCH_SCOPE, idempotencyKey, "CUSTOMER_SEGMENT_BATCH",
                created.id().toString(), payload);
        auditService.record(tenantId, user.userId(), "CUSTOMER_SEGMENT_BATCH_CREATE", "CUSTOMER_SEGMENT",
                Long.toString(segmentId), null, toBatchResponse(created), "已保存操作批次，未自动执行后续操作");
        return toBatchResponse(created);
    }

    private PageResponse<CustomerListItemResponse> members(long tenantId, CurrentUser user,
                                                            CustomerSegmentCriteria filters, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<CustomerListItemResponse> items = repository.searchMembers(tenantId, query.scopeType(), query.teamIds(),
                        query.selfId(), filters, offset, size).stream().map(customerService::toListItem).toList();
        long total = repository.countMembers(tenantId, query.scopeType(), query.teamIds(), query.selfId(), filters);
        return new PageResponse<>(items, total, page, size);
    }

    private long countMembers(long tenantId, CurrentUser user, CustomerSegmentCriteria filters) {
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        return repository.countMembers(tenantId, query.scopeType(), query.teamIds(), query.selfId(), filters);
    }

    private List<Long> allMemberIds(long tenantId, CurrentUser user, CustomerSegmentCriteria filters) {
        List<Long> ids = new ArrayList<>();
        int page = 1;
        long total = countMembers(tenantId, user, filters);
        while ((long) (page - 1) * 500 < total) {
            ids.addAll(members(tenantId, user, filters, page, 500).items().stream()
                    .map(CustomerListItemResponse::id).toList());
            page++;
        }
        return ids;
    }

    private CustomerSegment requireSegment(long tenantId, long id) {
        return repository.findActiveById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "动态分组不存在"));
    }

    private void requireSegmentManageable(CurrentUser user, CustomerSegment segment) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)
                || (!Objects.equals(segment.createdBy(), user.userId()) && scope.type() != DataScope.ScopeType.ALL)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅创建人或全量范围管理员可修改或删除动态分组");
        }
    }

    private CustomerSegmentResponse toResponse(CustomerSegment segment) {
        return new CustomerSegmentResponse(segment.id(), segment.name(), filters(segment), segment.createdAt(),
                segment.updatedAt(), segment.version());
    }

    private CustomerSegmentBatchResponse toBatchResponse(CustomerSegmentBatch batch) {
        return new CustomerSegmentBatchResponse(batch.id(), batch.segmentId(), batch.batchNo(), batch.operationType(),
                batch.memberCount(), batch.createdAt());
    }

    private CustomerSegmentCriteria filters(CustomerSegment segment) {
        try {
            return normalize(objectMapper.readValue(segment.filterJson(), CustomerSegmentCriteria.class));
        } catch (Exception ex) {
            throw new IllegalStateException("动态分组条件无法解析", ex);
        }
    }

    private CustomerSegmentCriteria normalize(CustomerSegmentCriteria input) {
        CustomerSegmentCriteria value = input == null ? CustomerSegmentCriteria.empty() : input;
        return new CustomerSegmentCriteria(codes(value.cityCodes()), codes(value.tierCodes()),
                ids(value.attentionProjectIds()), ids(value.purchasedProjectIds()), ids(value.redeemedProjectIds()),
                value.redeemedMinCount());
    }

    private List<String> codes(List<String> input) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String code : input) {
            if (code == null || code.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分组筛选条件不能为空");
            }
            values.add(code.strip());
        }
        return values.stream().sorted().toList();
    }

    private List<Long> ids(List<Long> input) {
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (Long id : input) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分组项目ID必须为正整数");
            }
            values.add(id);
        }
        return values.stream().sorted().toList();
    }

    private List<Long> projectIds(CustomerSegmentCriteria filters) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.addAll(filters.attentionProjectIds());
        ids.addAll(filters.purchasedProjectIds());
        ids.addAll(filters.redeemedProjectIds());
        return List.copyOf(ids);
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "动态分组已被他人修改，请刷新后重试");
    }

    private String canonical(Object value) {
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("动态分组请求序列化失败", ex);
        }
    }
}
