package com.whale.crm.customer.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CreateMergeCaseRequest;
import com.whale.crm.customer.api.dto.MergeCandidateItemResponse;
import com.whale.crm.customer.api.dto.MergeScanResultResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.customer.domain.MergeCase;
import com.whale.crm.customer.domain.MergeCaseRepository;
import com.whale.crm.customer.domain.MergeReversal;
import com.whale.crm.customer.domain.MergeReversalRepository;
import com.whale.crm.customer.domain.MergeResourceSnapshot;
import com.whale.crm.customer.domain.MergeResourceSnapshotRepository;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 客户查重与合并审批（docs/11 第 7 节 SUGGESTED → REVIEWING → CONFIRMED/REJECTED）。
 * 候选 = 同 mobile_hash 或同 identity external_id；仅昵称相似不自动合并（本阶段无自动相似度）。
 * 未经 CONFIRMED 不迁移任何关联数据；仅 REVIEWING 可确认。确认时锁主从客户，保存从客户资源 ID 快照，
 * 同事务迁移 identity/tag/followup/task/积分流水并按账本重算主客户缓存；从客户 deleted=1、审计；
 * identity/tag 的唯一键冲突按“同身份/同标签去重删除”处理。错误合并另走更正审批，按快照恢复并保留历史。
 */
@Service
public class MergeApplicationService {

    private final MergeCaseRepository mergeCaseRepository;
    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final CustomerTagRepository tagRepository;
    private final FollowupRepository followupRepository;
    private final TaskRepository taskRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final CustomerScopeSupport scopeSupport;
    private final MobileSupport mobileSupport;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CustomerOrderOperations customerOrderOperations;
    private final MergeResourceSnapshotRepository snapshotRepository;
    private final MergeReversalRepository reversalRepository;
    private final MergeReferenceGuard referenceGuard;

    public MergeApplicationService(MergeCaseRepository mergeCaseRepository,
                                   CustomerRepository customerRepository,
                                   CustomerIdentityRepository identityRepository,
                                   CustomerTagRepository tagRepository,
                                   FollowupRepository followupRepository,
                                   TaskRepository taskRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   CustomerScopeSupport scopeSupport,
                                   MobileSupport mobileSupport,
                                   AuditService auditService,
                                   ObjectMapper objectMapper,
                                   CustomerOrderOperations customerOrderOperations,
                                   MergeResourceSnapshotRepository snapshotRepository,
                                   MergeReversalRepository reversalRepository,
                                   MergeReferenceGuard referenceGuard) {
        this.mergeCaseRepository = mergeCaseRepository;
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.tagRepository = tagRepository;
        this.followupRepository = followupRepository;
        this.taskRepository = taskRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.scopeSupport = scopeSupport;
        this.mobileSupport = mobileSupport;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.customerOrderOperations = customerOrderOperations;
        this.snapshotRepository = snapshotRepository;
        this.reversalRepository = reversalRepository;
        this.referenceGuard = referenceGuard;
    }

    @Transactional(readOnly = true)
    public PageResponse<MergeCandidateItemResponse> list(long tenantId, CurrentUser user, String status,
                                                         int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<MergeCandidateItemResponse> items = mergeCaseRepository
                .search(tenantId, query.scopeType(), query.teamIds(), query.selfId(), status, offset, size)
                .stream().map(mc -> toResponse(tenantId, mc)).toList();
        long total = mergeCaseRepository.countSearch(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                status);
        return new PageResponse<>(items, total, page, size);
    }

    /**
     * 查重扫描：同 mobile_hash / 同 identity external_id 生成 SUGGESTED 候选。
     * 幂等：(tenant, primary, secondary) 唯一键兜底，已存在（含已拒绝）不重复生成。
     */
    @Transactional
    public MergeScanResultResponse scan(long tenantId, CurrentUser user) {
        Map<String, List<Long>> pairs = new LinkedHashMap<>();
        Map<String, String> evidenceByPair = new LinkedHashMap<>();

        for (CustomerRepository.DuplicateMobileGroup group : customerRepository.findDuplicateMobileGroups(tenantId)) {
            List<Long> ids = group.customerIds();
            Customer sample = customerRepository.findById(tenantId, ids.get(0)).orElse(null);
            String masked = sample == null ? null : mobileSupport.maskFromCipher(sample.getMobileCipher());
            registerPairs(pairs, evidenceByPair, ids, "MOBILE_HASH", masked);
        }
        for (CustomerRepository.DuplicateIdentityGroup group : customerRepository.findDuplicateIdentityGroups(tenantId)) {
            registerPairs(pairs, evidenceByPair, group.customerIds(), "IDENTITY",
                    CustomerApplicationService.maskExternalId(group.externalId()));
        }

        List<MergeCase> created = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : pairs.entrySet()) {
            long primaryId = entry.getValue().get(0);
            long secondaryId = entry.getValue().get(1);
            if (mergeCaseRepository.existsPair(tenantId, primaryId, secondaryId)
                    || mergeCaseRepository.existsOpenBetween(tenantId, primaryId, secondaryId)) {
                continue;
            }
            MergeCase mergeCase = new MergeCase();
            mergeCase.setTenantId(tenantId);
            mergeCase.setPrimaryCustomerId(primaryId);
            mergeCase.setSecondaryCustomerId(secondaryId);
            mergeCase.setStatus(MergeCase.STATUS_SUGGESTED);
            mergeCase.setMatchEvidence(evidenceByPair.get(entry.getKey()));
            mergeCaseRepository.insert(mergeCase);
            created.add(mergeCase);
        }
        auditService.record(tenantId, user.userId(), AuditService.ACTION_MERGE_CANDIDATE_SCAN, "MERGE_CASE", "-",
                null, Map.of("created", created.size()), null);
        List<MergeCandidateItemResponse> items = created.stream().map(mc -> toResponse(tenantId, mc)).toList();
        return new MergeScanResultResponse(items.size(), items);
    }

    /**
     * 人工登记合并候选：两客户均须在可见范围且未归档。
     */
    @Transactional
    public MergeCandidateItemResponse createCase(long tenantId, CurrentUser user, CreateMergeCaseRequest request) {
        if (Objects.equals(request.primaryCustomerId(), request.secondaryCustomerId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "主从客户不能相同");
        }
        Customer primary = scopeSupport.requireVisible(tenantId, request.primaryCustomerId(), user);
        Customer secondary = scopeSupport.requireVisible(tenantId, request.secondaryCustomerId(), user);
        if (mergeCaseRepository.existsPair(tenantId, primary.getId(), secondary.getId())
                || mergeCaseRepository.existsOpenBetween(tenantId, primary.getId(), secondary.getId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该客户对的合并候选已存在");
        }
        MergeCase mergeCase = new MergeCase();
        mergeCase.setTenantId(tenantId);
        mergeCase.setPrimaryCustomerId(primary.getId());
        mergeCase.setSecondaryCustomerId(secondary.getId());
        mergeCase.setStatus(MergeCase.STATUS_SUGGESTED);
        mergeCase.setMatchEvidence(writeEvidence(Map.of("matchedOn", List.of("MANUAL"),
                "reason", request.reason() == null ? "" : request.reason())));
        mergeCaseRepository.insert(mergeCase);
        MergeCase persisted = mergeCaseRepository.findById(tenantId, mergeCase.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_MERGE_CASE_CREATE, "MERGE_CASE",
                Long.toString(mergeCase.getId()), null, caseSnapshot(persisted), request.reason());
        return toResponse(tenantId, persisted);
    }

    @Transactional
    public MergeCandidateItemResponse review(long tenantId, CurrentUser user, long caseId, long version,
                                             String reason) {
        MergeCase mergeCase = requireOpenCase(tenantId, user, caseId, version);
        if (!MergeCase.STATUS_SUGGESTED.equals(mergeCase.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅 SUGGESTED 状态可转为 REVIEWING");
        }
        transition(tenantId, mergeCase, MergeCase.STATUS_REVIEWING, user.userId());
        MergeCase after = mergeCaseRepository.findById(tenantId, caseId).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_MERGE_CASE_REVIEW, "MERGE_CASE",
                Long.toString(caseId), caseSnapshot(mergeCase), caseSnapshot(after), reason);
        return toResponse(tenantId, after);
    }

    /**
     * 确认合并：仅 REVIEWING 可执行。锁主从客户（按 id 升序防死锁），同事务迁移
     * identity/tag/followup/task/积分流水到主客户并重算积分缓存，从客户 deleted=1，合并单 → CONFIRMED。
     */
    @Transactional
    public MergeCandidateItemResponse confirm(long tenantId, CurrentUser user, long caseId, long version,
                                              String reason) {
        MergeCase mergeCase = requireOpenCase(tenantId, user, caseId, version);
        if (!MergeCase.STATUS_REVIEWING.equals(mergeCase.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅 REVIEWING 状态可确认合并");
        }
        long primaryId = mergeCase.getPrimaryCustomerId();
        long secondaryId = mergeCase.getSecondaryCustomerId();
        long firstLock = Math.min(primaryId, secondaryId);
        long secondLock = Math.max(primaryId, secondaryId);
        Customer first = customerRepository.findByIdForUpdate(tenantId, firstLock)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        Customer second = customerRepository.findByIdForUpdate(tenantId, secondLock)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        Customer primary = first.getId() == primaryId ? first : second;
        Customer secondary = first.getId() == secondaryId ? first : second;
        if (primary.isDeleted() || secondary.isDeleted()) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户已归档，无法执行合并");
        }
        DataScope scope = scopeSupport.scope(user);
        if (!scopeSupport.isVisible(scope, user, primary) || !scopeSupport.isVisible(scope, user, secondary)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在或不可见");
        }

        snapshotSecondaryResources(tenantId, caseId, primaryId, secondaryId);

        // identity：与主客户完全同 (type,app,externalId) 的去重删除，其余改挂主客户（规避唯一键冲突）
        Set<String> primaryIdentityKeys = new HashSet<>();
        for (CustomerIdentity identity : identityRepository.findByCustomerId(tenantId, primaryId)) {
            primaryIdentityKeys.add(identityKey(identity));
        }
        int identitiesMigrated = 0;
        int identitiesDropped = 0;
        for (CustomerIdentity identity : identityRepository.findByCustomerId(tenantId, secondaryId)) {
            if (primaryIdentityKeys.contains(identityKey(identity))) {
                identityRepository.deleteById(tenantId, identity.getId());
                identitiesDropped++;
            } else {
                identityRepository.reassignCustomer(tenantId, identity.getId(), primaryId);
                identitiesMigrated++;
            }
        }
        // tag：主客户已有同 code 的删除，其余改挂
        int tagsDropped = tagRepository.deleteDuplicatesOf(tenantId, secondaryId, primaryId);
        int tagsMigrated = tagRepository.reassignCustomer(tenantId, secondaryId, primaryId);
        int followupsMigrated = followupRepository.reassignCustomer(tenantId, secondaryId, primaryId);
        int tasksMigrated = taskRepository.reassignCustomer(tenantId, secondaryId, primaryId);
        int pointsMigrated = pointsLedgerRepository.reassignCustomer(tenantId, secondaryId, primaryId);
        customerOrderOperations.reassignCustomer(tenantId, secondaryId, primaryId);
        long pointsBalance = pointsLedgerRepository.sumDelta(tenantId, primaryId);
        customerRepository.refreshPointsCached(tenantId, primaryId, Math.toIntExact(pointsBalance));

        int deleted = customerRepository.softDelete(tenantId, secondaryId, secondary.getVersion());
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        transition(tenantId, mergeCase, MergeCase.STATUS_CONFIRMED, user.userId());

        Map<String, Object> after = new LinkedHashMap<>(caseSnapshot(mergeCaseRepository.findById(tenantId, caseId).orElseThrow()));
        after.put("identitiesMigrated", identitiesMigrated);
        after.put("identitiesDropped", identitiesDropped);
        after.put("tagsMigrated", tagsMigrated);
        after.put("tagsDropped", tagsDropped);
        after.put("followupsMigrated", followupsMigrated);
        after.put("tasksMigrated", tasksMigrated);
        after.put("pointsMigrated", pointsMigrated);
        after.put("pointsBalance", pointsBalance);
        after.put("secondaryDeleted", true);
        auditService.record(tenantId, user.userId(), AuditService.ACTION_MERGE_CASE_CONFIRM, "MERGE_CASE",
                Long.toString(caseId), caseSnapshot(mergeCase), after, reason);
        return toResponse(tenantId, mergeCaseRepository.findById(tenantId, caseId).orElseThrow());
    }

    @Transactional
    public MergeCandidateItemResponse reject(long tenantId, CurrentUser user, long caseId, long version,
                                             String reason) {
        MergeCase mergeCase = requireOpenCase(tenantId, user, caseId, version);
        transition(tenantId, mergeCase, MergeCase.STATUS_REJECTED, user.userId());
        MergeCase after = mergeCaseRepository.findById(tenantId, caseId).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_MERGE_CASE_REJECT, "MERGE_CASE",
                Long.toString(caseId), caseSnapshot(mergeCase), caseSnapshot(after), reason);
        return toResponse(tenantId, after);
    }

    private void snapshotSecondaryResources(long tenantId, long caseId, long primaryId, long secondaryId) {
        Set<String> primaryIdentityKeys = new HashSet<>();
        for (CustomerIdentity identity : identityRepository.findByCustomerId(tenantId, primaryId)) {
            primaryIdentityKeys.add(identityKey(identity));
        }
        for (CustomerIdentity identity : identityRepository.findByCustomerId(tenantId, secondaryId)) {
            if (!primaryIdentityKeys.contains(identityKey(identity))) {
                snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "IDENTITY", identity.getId().toString()));
            }
        }
        Set<String> primaryTagCodes = new HashSet<>(tagRepository.findTagCodes(tenantId, primaryId));
        for (String tagCode : tagRepository.findTagCodes(tenantId, secondaryId)) {
            if (!primaryTagCodes.contains(tagCode)) {
                snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "TAG", tagCode));
            }
        }
        for (Long followupId : followupRepository.findIdsByCustomerId(tenantId, secondaryId)) {
            snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "FOLLOWUP", followupId.toString()));
        }
        for (Long taskId : taskRepository.findIdsByCustomerId(tenantId, secondaryId)) {
            snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "TASK", taskId.toString()));
        }
        for (var entry : pointsLedgerRepository.findByCustomerId(tenantId, secondaryId)) {
            snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "POINTS", entry.getId().toString()));
        }
        MergeOrderResourceSnapshot resources = customerOrderOperations.snapshotResources(tenantId, secondaryId);
        referenceGuard.requireUnreferenced(tenantId, resources.orderIds(), resources.entitlementIds());
        for (Long orderId : resources.orderIds()) {
            snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "ORDER", orderId.toString()));
        }
        for (Long entitlementId : resources.entitlementIds()) {
            snapshotRepository.insert(tenantId, new MergeResourceSnapshot(null, caseId, "ENTITLEMENT", entitlementId.toString()));
        }
    }

    // ========== 内部 ==========

    private MergeCase requireOpenCase(long tenantId, CurrentUser user, long caseId, long version) {
        MergeCase mergeCase = mergeCaseRepository.findById(tenantId, caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在"));
        if (!Objects.equals(mergeCase.getVersion(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        if (!mergeCase.isOpen()) {
            throw new BusinessException(ErrorCode.CONFLICT, "合并单已终结（当前状态 " + mergeCase.getStatus() + "）");
        }
        // 双客户可见性（confirm 内另有行锁复核）
        DataScope scope = scopeSupport.scope(user);
        for (long customerId : List.of(mergeCase.getPrimaryCustomerId(), mergeCase.getSecondaryCustomerId())) {
            Customer customer = customerRepository.findById(tenantId, customerId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在或不可见"));
            if (!scopeSupport.isVisible(scope, user, customer)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在或不可见");
            }
        }
        return mergeCase;
    }

    private void transition(long tenantId, MergeCase mergeCase, String toStatus, long reviewerId) {
        int updated = mergeCaseRepository.updateStatus(tenantId, mergeCase.getId(), mergeCase.getVersion(),
                toStatus, reviewerId, Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
    }

    /**
     * 组内成对登记：主客户 = id 最小者；每组 (primary, secondary) 只登记一次。
     */
    private void registerPairs(Map<String, List<Long>> pairs, Map<String, String> evidenceByPair,
                               List<Long> customerIds, String matchedOn, String maskedValue) {
        long primary = customerIds.get(0);
        for (int i = 1; i < customerIds.size(); i++) {
            long secondary = customerIds.get(i);
            String key = primary + ":" + secondary;
            pairs.putIfAbsent(key, List.of(primary, secondary));
            // 同一对可能被 mobile 与 identity 同时命中，证据合并
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("matchedOn", List.of(matchedOn));
            if (maskedValue != null) {
                evidence.put(matchedOn.equals("MOBILE_HASH") ? "mobileMasked" : "externalIdMasked", maskedValue);
            }
            evidenceByPair.merge(key, writeEvidence(evidence), (oldJson, newJson) -> mergeEvidence(oldJson, matchedOn, maskedValue));
        }
    }

    private String mergeEvidence(String existingJson, String matchedOn, String maskedValue) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(existingJson);
            List<String> matched = new ArrayList<>();
            node.withArray("matchedOn").forEach(n -> matched.add(n.asText()));
            if (!matched.contains(matchedOn)) {
                matched.add(matchedOn);
            }
            node.putPOJO("matchedOn", matched);
            if (maskedValue != null) {
                node.put(matchedOn.equals("MOBILE_HASH") ? "mobileMasked" : "externalIdMasked", maskedValue);
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException | RuntimeException ex) {
            return existingJson;
        }
    }

    private String writeEvidence(Map<String, Object> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String identityKey(CustomerIdentity identity) {
        return identity.getIdentityType() + "|" + identity.getSourceApp() + "|" + identity.getExternalId();
    }

    private Map<String, Object> caseSnapshot(MergeCase mergeCase) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", mergeCase.getId());
        snapshot.put("primaryCustomerId", mergeCase.getPrimaryCustomerId());
        snapshot.put("secondaryCustomerId", mergeCase.getSecondaryCustomerId());
        snapshot.put("status", mergeCase.getStatus());
        snapshot.put("reviewerId", mergeCase.getReviewerId());
        return snapshot;
    }

    private MergeCandidateItemResponse toResponse(long tenantId, MergeCase mergeCase) {
        JsonNode evidence = null;
        try {
            evidence = mergeCase.getMatchEvidence() == null ? null : objectMapper.readTree(mergeCase.getMatchEvidence());
        } catch (Exception ignored) {
            // 证据 JSON 损坏时不阻断列表，返回 null
        }
        MergeReversal reversal = reversalRepository.findByMergeCaseId(tenantId, mergeCase.getId()).orElse(null);
        return new MergeCandidateItemResponse(mergeCase.getId(), mergeCase.getPrimaryCustomerId(),
                mergeCase.getSecondaryCustomerId(),
                summaryOf(tenantId, mergeCase.getPrimaryCustomerId()),
                summaryOf(tenantId, mergeCase.getSecondaryCustomerId()),
                evidence, mergeCase.getStatus(), mergeCase.getCreatedAt(), mergeCase.getVersion(),
                reversal == null ? null : reversal.id(), reversal == null ? null : reversal.status(),
                reversal == null ? null : reversal.warningMessage(), reversal == null ? null : reversal.version());
    }

    private String summaryOf(long tenantId, long customerId) {
        return customerRepository.findById(tenantId, customerId)
                .map(c -> {
                    StringBuilder sb = new StringBuilder(c.getCustomerNo());
                    String name = c.getDisplayName() != null ? c.getDisplayName() : c.getNickname();
                    if (name != null) {
                        sb.append(' ').append(name);
                    }
                    String masked = mobileSupport.maskFromCipher(c.getMobileCipher());
                    if (masked != null) {
                        sb.append(' ').append(masked);
                    }
                    return sb.toString();
                })
                .orElse("#" + customerId);
    }
}
