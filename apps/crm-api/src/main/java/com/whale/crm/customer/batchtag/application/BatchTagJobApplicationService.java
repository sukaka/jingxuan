package com.whale.crm.customer.batchtag.application;

import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.customer.api.dto.CustomerListItemResponse;
import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.api.dto.CustomerSegmentResponse;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerSegmentApplicationService;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobActionRequest;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobDetailResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobItemResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagMemberResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagPreviewResponse;
import com.whale.crm.customer.batchtag.api.dto.CreateBatchTagJobRequest;
import com.whale.crm.customer.batchtag.api.dto.PreviewBatchTagJobRequest;
import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItem;
import com.whale.crm.customer.batchtag.domain.BatchTagJobRepository;
import com.whale.crm.customer.batchtag.domain.BatchTagPreview;
import com.whale.crm.customer.batchtag.domain.BatchTagPreviewMember;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerSegmentBatch;
import com.whale.crm.customer.domain.CustomerSegmentRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class BatchTagJobApplicationService {
    public static final String TAG_DICTIONARY = "customerTags";
    private static final String PREVIEW_SCOPE = "customer:batch_tag:preview";
    private static final String CREATE_SCOPE = "customer:batch_tag:create";
    private static final String EXECUTE_SCOPE = "customer:batch_tag:execute";
    private static final String RETRY_SCOPE = "customer:batch_tag:retry";
    private static final String ROLLBACK_SCOPE = "customer:batch_tag:rollback";
    private static final String JOB_RESOURCE = "BATCH_TAG_JOB";
    private static final int SNAPSHOT_PAGE_SIZE = 500;

    private final BatchTagJobRepository repository;
    private final CustomerSegmentApplicationService segmentService;
    private final CustomerSegmentRepository segmentRepository;
    private final CustomerScopeSupport scopeSupport;
    private final DictionaryApplicationService dictionaryService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final BatchTagJobExecutionService executionService;
    private final BatchTagJson json;

    public BatchTagJobApplicationService(BatchTagJobRepository repository,
            CustomerSegmentApplicationService segmentService, CustomerSegmentRepository segmentRepository,
            CustomerScopeSupport scopeSupport, DictionaryApplicationService dictionaryService,
            IdempotencyService idempotencyService, AuditService auditService,
            BatchTagJobExecutionService executionService, BatchTagJson json) {
        this.repository = repository;
        this.segmentService = segmentService;
        this.segmentRepository = segmentRepository;
        this.scopeSupport = scopeSupport;
        this.dictionaryService = dictionaryService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.executionService = executionService;
        this.json = json;
    }

    @Transactional
    public BatchTagPreviewResponse preview(long tenantId, CurrentUser user, PreviewBatchTagJobRequest input,
                                           String idempotencyKey) {
        requireActor(tenantId, user, "customer:write");
        DataScope scope = scopeSupport.scope(user);
        requireNonEmptyScope(scope);
        String key = requireKey(idempotencyKey);
        String payload = json.json(input);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, PREVIEW_SCOPE, key, payload);
        if (replay.isPresent()) {
            BatchTagPreview existing = requirePreview(tenantId, resourceId(replay.orElseThrow()));
            requirePreviewOwner(existing, user);
            return previewResponse(existing, input.page(), input.size());
        }
        List<String> tags = normalizeTags(input.tagCodes());
        List<Map<String, String>> tagSnapshot = tagSnapshot(tenantId, input.operation(), tags);
        ResolvedSource source = resolveSource(tenantId, user, input);
        Map<String, Object> scopeSnapshot = scopeSnapshot(scope, user);
        List<BatchTagMemberResponse> members = resolveAllMembers(tenantId, user, source);
        String previewId = "BP" + UlidCreator.getUlid().toString();
        BatchTagPreview preview = new BatchTagPreview(previewId, tenantId, user.userId(), input.operation(),
                json.json(tags), json.json(tagSnapshot), source.type(), source.id(), json.json(source.snapshot()),
                json.json(scopeSnapshot), key, members.size(), null);
        repository.insertPreview(preview);
        for (BatchTagMemberResponse member : members) {
            repository.insertPreviewMember(new BatchTagPreviewMember(null, tenantId, previewId, member.customerId(),
                    member.customerNo(), member.customerName(), member.ownerStaffId(), member.teamId(), null));
        }
        idempotencyService.recordSuccess(tenantId, PREVIEW_SCOPE, key, "BATCH_TAG_PREVIEW", previewId, payload);
        auditService.record(tenantId, user.userId(), "BATCH_TAG_PREVIEW_CREATE", "BATCH_TAG_PREVIEW", previewId,
                null, Map.of("operation", preview.operation(), "tagCodes", tags, "sourceType", source.type(),
                        "memberCount", members.size()), null);
        return previewResponse(preview, input.page(), input.size());
    }

    @Transactional
    public BatchTagJobDetailResponse create(long tenantId, CurrentUser user, CreateBatchTagJobRequest input,
                                            String idempotencyKey) {
        requireActor(tenantId, user, "customer:write");
        String previewId = input.previewId().strip();
        String key = requireKey(idempotencyKey);
        String payload = json.json(Map.of("previewId", previewId));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay.isPresent()) {
            IdempotencyRecord record = replay.orElseThrow();
            requireResource(record, JOB_RESOURCE, null);
            return detail(tenantId, user, numericResourceId(record), 1, 100);
        }
        BatchTagPreview preview = requirePreview(tenantId, previewId);
        requirePreviewOwner(preview, user);
        requireNonEmptyScope(scopeSupport.scope(user));
        if (repository.findJobByPreviewId(tenantId, previewId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该预览已创建任务，不能重复创建");
        }
        List<String> tags = json.stringList(preview.tagCodesJson());
        List<Map<String, String>> currentTagSnapshot = tagSnapshot(tenantId, preview.operation(), tags);
        List<BatchTagPreviewMember> members = repository.findPreviewMembers(tenantId, previewId, null, null);
        if (members.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "预览成员为空，不能创建批量重打标任务");
        }
        String jobNo = "BT" + UlidCreator.getUlid().toString();
        try {
            repository.insertJob(new BatchTagJob(null, tenantId, jobNo, previewId, user.userId(), preview.operation(),
                    preview.tagCodesJson(), json.json(currentTagSnapshot), preview.sourceType(), preview.sourceId(),
                    preview.sourceSnapshotJson(), preview.scopeSnapshotJson(), key, null, BatchTagJob.PENDING,
                    members.size(), 0, 0, 0, 0, 0, null, null, null, null, null, null, 0L));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该预览已创建任务，请查询任务结果");
        }
        BatchTagJob created = repository.findJobByNo(tenantId, jobNo).orElseThrow();
        for (BatchTagPreviewMember member : members) {
            insertJobItem(created, member.customerId(), member.customerNo(), member.customerName(),
                    member.ownerStaffId(), member.teamId(), preview.tagCodesJson());
        }
        idempotencyService.recordSuccess(tenantId, CREATE_SCOPE, key, JOB_RESOURCE,
                created.id().toString(), payload);
        auditService.record(tenantId, user.userId(), "BATCH_TAG_JOB_CREATE", JOB_RESOURCE,
                created.id().toString(), null, jobSnapshot(created), "使用服务端预览固定成员快照");
        return detail(tenantId, user, created.id(), 1, 100);
    }

    @Transactional(readOnly = true)
    public PageResponse<BatchTagJobResponse> search(long tenantId, CurrentUser user, String status,
                                                    int page, int size) {
        requireActor(tenantId, user, "customer:read");
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        int offset = (page - 1) * size;
        List<BatchTagJobResponse> items = repository.searchJobs(tenantId, status, scope.type().name(),
                user.userId(), offset, size).stream().map(this::toResponse).toList();
        long total = repository.countJobs(tenantId, status, scope.type().name(), user.userId());
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public BatchTagJobDetailResponse detail(long tenantId, CurrentUser user, long jobId, int page, int size) {
        requireActor(tenantId, user, "customer:read");
        BatchTagJob job = requireVisibleJob(tenantId, user, jobId);
        List<BatchTagJobItemResponse> items = repository.findItemsPage(tenantId, jobId, (page - 1) * size, size)
                .stream().map(this::toResponse).toList();
        return new BatchTagJobDetailResponse(toResponse(job),
                new PageResponse<>(items, repository.countItems(tenantId, jobId), page, size));
    }

    public BatchTagJobDetailResponse execute(long tenantId, CurrentUser user, long jobId,
            BatchTagJobActionRequest request, String idempotencyKey) {
        return execute(tenantId, user, jobId, request, idempotencyKey, false);
    }

    public BatchTagJobDetailResponse retry(long tenantId, CurrentUser user, long jobId,
            BatchTagJobActionRequest request, String idempotencyKey) {
        return execute(tenantId, user, jobId, request, idempotencyKey, true);
    }

    private BatchTagJobDetailResponse execute(long tenantId, CurrentUser user, long jobId,
            BatchTagJobActionRequest request, String idempotencyKey, boolean retry) {
        requireActor(tenantId, user, "customer:write");
        BatchTagJob job = requireOwnedJob(tenantId, user, jobId);
        String scope = retry ? RETRY_SCOPE : EXECUTE_SCOPE;
        String key = requireKey(idempotencyKey);
        String payload = actionPayload(jobId, request.version(), retry ? "RETRY" : "EXECUTE");
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, scope, key, payload);
        if (replay.isPresent()) {
            IdempotencyRecord record = replay.orElseThrow();
            requireResource(record, JOB_RESOURCE, jobId);
            return detail(tenantId, user, jobId, 1, 100);
        }
        String requiredStatus = retry ? null : BatchTagJob.PENDING;
        if (!retry && !requiredStatus.equals(job.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅待执行任务可执行");
        }
        if (retry && !Set.of(BatchTagJob.PARTIAL_FAILED, BatchTagJob.FAILED).contains(job.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅部分失败或失败任务可重试");
        }
        if (retry && repository.findWorkItems(tenantId, jobId).stream()
                .noneMatch(item -> BatchTagJobItem.FAILED.equals(item.status()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务没有可重试的失败项，跳过项不会自动重试");
        }
        requireVersion(job, request.version());
        executionService.run(job, new BatchTagJobExecutionService.ExecutionIdempotency(scope, key, payload,
                retry ? "RETRY" : "EXECUTE"));
        return detail(tenantId, user, jobId, 1, 100);
    }

    @Transactional
    public BatchTagJobDetailResponse rollback(long tenantId, CurrentUser user, long originalJobId,
            BatchTagJobActionRequest request, String idempotencyKey) {
        requireActor(tenantId, user, "customer:write");
        BatchTagJob original = requireVisibleJob(tenantId, user, originalJobId);
        String key = requireKey(idempotencyKey);
        String payload = actionPayload(originalJobId, request.version(), "ROLLBACK");
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, ROLLBACK_SCOPE, key, payload);
        if (replay.isPresent()) {
            IdempotencyRecord record = replay.orElseThrow();
            requireResource(record, JOB_RESOURCE, null);
            return detail(tenantId, user, numericResourceId(record), 1, 100);
        }
        requireVersion(original, request.version());
        if (Set.of(BatchTagJob.PENDING, BatchTagJob.RUNNING).contains(original.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务尚未完成，不能创建独立回滚");
        }
        if (repository.findJobByRollbackOf(tenantId, originalJobId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该任务已有独立回滚任务");
        }
        List<BatchTagJobItem> candidates = repository.findRollbackCandidates(tenantId, originalJobId);
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "原任务没有实际变更项，无需回滚");
        }
        DataScope scope = scopeSupport.scope(user);
        requireNonEmptyScope(scope);
        String operation = BatchTagJob.ADD.equals(original.operation()) ? BatchTagJob.REMOVE : BatchTagJob.ADD;
        LinkedHashSet<String> tagValues = new LinkedHashSet<>();
        candidates.forEach(item -> tagValues.addAll(json.stringList(item.changedTagCodesJson())));
        List<String> tags = tagValues.stream().sorted().toList();
        List<Map<String, String>> currentTagSnapshot = tagSnapshot(tenantId, operation, tags);
        Map<String, Object> sourceSnapshot = new LinkedHashMap<>();
        sourceSnapshot.put("originalJobId", original.id());
        sourceSnapshot.put("originalJobNo", original.jobNo());
        sourceSnapshot.put("originalOperation", original.operation());
        String jobNo = "BT" + UlidCreator.getUlid().toString();
        try {
            repository.insertJob(new BatchTagJob(null, tenantId, jobNo, null, user.userId(), operation,
                    json.json(tags), json.json(currentTagSnapshot), "ROLLBACK", original.id(),
                    json.json(sourceSnapshot), json.json(scopeSnapshot(scope, user)), key, original.id(),
                    BatchTagJob.PENDING, candidates.size(), 0, 0, 0, 0, 0, null, null, null,
                    null, null, null, 0L));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该任务已有独立回滚任务");
        }
        BatchTagJob rollback = repository.findJobByNo(tenantId, jobNo).orElseThrow();
        for (BatchTagJobItem item : candidates) {
            insertJobItem(rollback, item.customerId(), item.customerNo(), item.customerName(),
                    item.ownerStaffId(), item.teamId(), item.changedTagCodesJson());
        }
        idempotencyService.recordSuccess(tenantId, ROLLBACK_SCOPE, key, JOB_RESOURCE,
                rollback.id().toString(), payload);
        auditService.record(tenantId, user.userId(), "BATCH_TAG_ROLLBACK_CREATE", JOB_RESOURCE,
                rollback.id().toString(), jobSnapshot(original), jobSnapshot(rollback),
                "新建独立反向任务，不覆盖原任务或逐项历史");
        return detail(tenantId, user, rollback.id(), 1, 100);
    }

    private ResolvedSource resolveSource(long tenantId, CurrentUser user, PreviewBatchTagJobRequest request) {
        String type = request.source().type();
        if ("FILTER".equals(type)) {
            CustomerSegmentCriteria filters = normalize(request.source().filters());
            return new ResolvedSource(type, null, filters, Map.of("filters", filters));
        }
        if (request.source().id() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分组或操作批次来源必须提供ID");
        }
        if ("SEGMENT".equals(type)) {
            CustomerSegmentResponse segment = segmentService.get(tenantId, request.source().id());
            CustomerSegmentCriteria filters = normalize(segment.filters());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("segmentId", segment.id());
            snapshot.put("name", segment.name());
            snapshot.put("version", segment.version());
            snapshot.put("filters", filters);
            return new ResolvedSource(type, segment.id(), filters, snapshot);
        }
        CustomerSegmentBatch batch = segmentRepository.findBatchById(tenantId, request.source().id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "V33操作批次不存在"));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("batchId", batch.id());
        snapshot.put("batchNo", batch.batchNo());
        snapshot.put("segmentId", batch.segmentId());
        snapshot.put("operationType", batch.operationType());
        snapshot.put("memberCount", batch.memberCount());
        snapshot.put("filters", json.read(batch.filterJson(), CustomerSegmentCriteria.class));
        return new ResolvedSource(type, batch.id(), null, snapshot);
    }

    private List<BatchTagMemberResponse> resolveAllMembers(long tenantId, CurrentUser user, ResolvedSource source) {
        LinkedHashMap<Long, BatchTagMemberResponse> members = new LinkedHashMap<>();
        if ("OPERATION_BATCH".equals(source.type())) {
            DataScope scope = scopeSupport.scope(user);
            CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
            long total = repository.countOperationBatchMembers(tenantId, source.id(), query.scopeType(),
                    query.teamIds(), query.selfId());
            for (int offset = 0; offset < total; offset += SNAPSHOT_PAGE_SIZE) {
                repository.findOperationBatchMembers(tenantId, source.id(), query.scopeType(), query.teamIds(),
                        query.selfId(), offset, SNAPSHOT_PAGE_SIZE)
                        .forEach(row -> members.put(row.getId(), member(row)));
            }
            return List.copyOf(members.values());
        }
        int page = 1;
        long total;
        do {
            PageResponse<CustomerListItemResponse> result = "SEGMENT".equals(source.type())
                    ? segmentService.members(tenantId, user, source.id(), page, SNAPSHOT_PAGE_SIZE)
                    : segmentService.preview(tenantId, user,
                            new com.whale.crm.customer.api.dto.PreviewCustomerSegmentRequest(
                                    source.filters(), page, SNAPSHOT_PAGE_SIZE));
            total = result.total();
            result.items().forEach(item -> members.put(item.id(), member(item)));
            page++;
        } while ((long) (page - 1) * SNAPSHOT_PAGE_SIZE < total);
        return List.copyOf(members.values());
    }

    private BatchTagPreviewResponse previewResponse(BatchTagPreview preview, int page, int size) {
        List<BatchTagMemberResponse> items = repository.findPreviewMembers(preview.tenantId(), preview.id(),
                (page - 1) * size, size).stream().map(this::member).toList();
        return new BatchTagPreviewResponse(preview.id(), preview.operation(), json.stringList(preview.tagCodesJson()),
                preview.sourceType(), preview.sourceId(), items, preview.memberCount(), page, size);
    }

    private void insertJobItem(BatchTagJob job, long customerId, String customerNo, String customerName,
                               long ownerStaffId, Long teamId, String tagCodesJson) {
        repository.insertItem(new BatchTagJobItem(null, job.tenantId(), job.id(), customerId, customerNo,
                customerName, ownerStaffId, teamId, tagCodesJson, BatchTagJobItem.PENDING, null, null,
                null, null, null, 0, null, 0L));
    }

    private BatchTagPreview requirePreview(long tenantId, String previewId) {
        return repository.findPreviewById(tenantId, previewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "批量重打标预览不存在或不可见"));
    }

    private void requirePreviewOwner(BatchTagPreview preview, CurrentUser user) {
        if (preview.requestedBy() != user.userId()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "批量重打标预览不存在或不可见");
        }
    }

    private BatchTagJob requireVisibleJob(long tenantId, CurrentUser user, long jobId) {
        BatchTagJob job = repository.findJobById(tenantId, jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "批量重打标任务不存在或不可见"));
        DataScope scope = scopeSupport.scope(user);
        if (job.requestedBy() != user.userId() && scope.type() != DataScope.ScopeType.ALL) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "批量重打标任务不存在或不可见");
        }
        return job;
    }

    private BatchTagJob requireOwnedJob(long tenantId, CurrentUser user, long jobId) {
        BatchTagJob job = requireVisibleJob(tenantId, user, jobId);
        if (job.requestedBy() != user.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅任务请求人可执行或重试");
        }
        return job;
    }

    private void requireActor(long tenantId, CurrentUser user, String permission) {
        if (user.tenantId() != tenantId || user.isInstitutionUser() || !user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号不能进行批量重打标操作");
        }
    }

    private void requireNonEmptyScope(DataScope scope) {
        if (scopeSupport.isEmptyScope(scope)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前数据范围不能进行批量重打标");
        }
    }

    private List<String> normalizeTags(List<String> input) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String value : input) {
            if (value == null || value.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签代码不能为空");
            }
            tags.add(value.strip());
        }
        if (tags.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少选择一个标签");
        }
        return tags.stream().sorted().toList();
    }

    private List<Map<String, String>> tagSnapshot(long tenantId, String operation, List<String> tags) {
        Map<String, String> activeLabels = new LinkedHashMap<>();
        if (BatchTagJob.ADD.equals(operation)) {
            for (String tag : tags) {
                DictionaryOptionResponse option = dictionaryService.requireActiveOption(tenantId, TAG_DICTIONARY, tag);
                activeLabels.put(tag, option.optionLabel());
            }
        } else {
            dictionaryService.listActiveOptions(tenantId, TAG_DICTIONARY)
                    .forEach(option -> activeLabels.put(option.optionCode(), option.optionLabel()));
        }
        return tags.stream().map(tag -> Map.of("code", tag, "label", activeLabels.getOrDefault(tag, tag))).toList();
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
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "筛选代码不能为空");
            }
            values.add(code.strip());
        }
        return values.stream().sorted().toList();
    }

    private List<Long> ids(List<Long> input) {
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (Long id : input) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "筛选项目ID必须为正整数");
            }
            values.add(id);
        }
        return values.stream().sorted().toList();
    }

    private Map<String, Object> scopeSnapshot(DataScope scope, CurrentUser user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", scope.type().name());
        value.put("teamIds", scope.teamIds());
        value.put("selfId", user.userId());
        return value;
    }

    private BatchTagJobResponse toResponse(BatchTagJob job) {
        return new BatchTagJobResponse(job.id(), job.jobNo(), job.requestedBy(), job.operation(),
                json.stringList(job.tagCodesJson()), job.sourceType(), job.sourceId(),
                json.map(job.sourceSnapshotJson()), json.map(job.scopeSnapshotJson()), job.idempotencyKey(),
                job.rollbackOfJobId(), job.status(), job.totalCount(), job.succeededCount(), job.noChangeCount(),
                job.skippedCount(), job.failedCount(), job.attemptCount(), job.lastErrorMasked(), job.createdAt(),
                job.startedAt(), job.finishedAt(), job.version());
    }

    private BatchTagJobItemResponse toResponse(BatchTagJobItem item) {
        return new BatchTagJobItemResponse(item.id(), item.customerId(), item.customerNo(), item.customerName(),
                item.ownerStaffId(), item.teamId(), json.stringList(item.tagCodesJson()), item.status(),
                item.failureCode(), item.reason(), nullableStrings(item.beforeTagCodesJson()),
                nullableStrings(item.afterTagCodesJson()), nullableStrings(item.changedTagCodesJson()),
                item.attemptCount(), item.processedAt(), item.version());
    }

    private List<String> nullableStrings(String value) {
        return value == null ? null : json.stringList(value);
    }

    private BatchTagMemberResponse member(BatchTagPreviewMember member) {
        return new BatchTagMemberResponse(member.customerId(), member.customerNo(), member.customerName(),
                member.ownerStaffId(), member.teamId());
    }

    private BatchTagMemberResponse member(CustomerListItemResponse item) {
        String name = item.displayName() != null ? item.displayName() : item.nickname();
        return new BatchTagMemberResponse(item.id(), item.customerNo(), name, item.ownerStaffId(), item.teamId());
    }

    private BatchTagMemberResponse member(CustomerListRow row) {
        String name = row.getDisplayName() != null ? row.getDisplayName() : row.getNickname();
        return new BatchTagMemberResponse(row.getId(), row.getCustomerNo(), name, row.getOwnerStaffId(), row.getTeamId());
    }

    private Map<String, Object> jobSnapshot(BatchTagJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", job.id());
        value.put("jobNo", job.jobNo());
        value.put("requestedBy", job.requestedBy());
        value.put("operation", job.operation());
        value.put("tagCodes", json.stringList(job.tagCodesJson()));
        value.put("sourceType", job.sourceType());
        value.put("sourceId", job.sourceId());
        value.put("status", job.status());
        value.put("totalCount", job.totalCount());
        value.put("rollbackOfJobId", job.rollbackOfJobId());
        value.put("version", job.version());
        return value;
    }

    private String actionPayload(long jobId, long version, String action) {
        return json.json(Map.of("jobId", jobId, "version", version, "action", action));
    }

    private void requireVersion(BatchTagJob job, long expectedVersion) {
        if (job.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被他人修改，请刷新后重试");
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private void requireResource(IdempotencyRecord record, String type, Long expectedId) {
        if (!type.equals(record.getResourceType())
                || expectedId != null && !Long.toString(expectedId).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于该批量重打标任务");
        }
    }

    private String resourceId(IdempotencyRecord record) {
        if (!"BATCH_TAG_PREVIEW".equals(record.getResourceType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于批量重打标预览");
        }
        return record.getResourceId();
    }

    private long numericResourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private record ResolvedSource(String type, Long id, CustomerSegmentCriteria filters,
                                  Map<String, Object> snapshot) {
    }
}
