package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.AssignCustomerOwnerRequest;
import com.whale.crm.customer.api.dto.BatchAssignCustomerOwnerRequest;
import com.whale.crm.customer.api.dto.BatchAssignCustomerOwnerResponse;
import com.whale.crm.customer.api.dto.CustomerOwnerAssignmentResponse;
import com.whale.crm.customer.api.dto.CustomerOwnershipLogItemResponse;
import com.whale.crm.customer.api.dto.ScopedTransferItemResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTransferRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 主管/管理员直接分配客户归属与归属日志查询服务。
 * 硬边界：
 * - 权限：写操作由控制器 @PreAuthorize("hasAuthority('customer:transfer')") + 本服务角色/团队范围双重校验。
 * - 范围：ADMIN 可跨团队全量分配；MANAGER_LEAD 仅可将本团队客户分配给本团队在职管家/主管；普通管家无权分配。
 * - 事实不覆盖：仅变更 crm_customer 当前 owner/team，同事务追加不可修改的 crm_customer_owner_history 与审计日志，
 *   不回写历史订单成交快照、核销或跟进历史。
 */
@Service
public class CustomerAssignmentApplicationService {

    public static final String IDEMPOTENCY_SCOPE_ASSIGN = "customer:owner_assign";
    public static final String IDEMPOTENCY_SCOPE_BATCH_ASSIGN = "customer:owner_assign_batch";
    public static final String APPROVAL_REF_DIRECT = "DIRECT_ASSIGN";
    public static final String APPROVAL_REF_BATCH_PREFIX = "BATCH-";
    public static final String APPROVAL_REF_TRANSFER_PREFIX = "TR-";
    public static final String MODE_DIRECT_ASSIGN = "DIRECT_ASSIGN";
    public static final String MODE_BATCH_ASSIGN = "BATCH_ASSIGN";
    public static final String MODE_TRANSFER_APPROVAL = "TRANSFER_APPROVAL";
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private final CustomerRepository customerRepository;
    private final CustomerTransferRepository transferRepository;
    private final CustomerOwnerHistoryRepository ownerHistoryRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final StewardDirectory stewardDirectory;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public CustomerAssignmentApplicationService(CustomerRepository customerRepository,
                                                CustomerTransferRepository transferRepository,
                                                CustomerOwnerHistoryRepository ownerHistoryRepository,
                                                UserRepository userRepository,
                                                CustomerScopeSupport scopeSupport,
                                                StewardDirectory stewardDirectory,
                                                AuditService auditService,
                                                IdempotencyService idempotencyService,
                                                ObjectMapper objectMapper) {
        this.customerRepository = customerRepository;
        this.transferRepository = transferRepository;
        this.ownerHistoryRepository = ownerHistoryRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.stewardDirectory = stewardDirectory;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CustomerOwnerAssignmentResponse assignOwner(long tenantId, CurrentUser user, long customerId,
                                                       AssignCustomerOwnerRequest request, String idempotencyKey) {
        String normalizedKey = normalizeKey(idempotencyKey);
        String reason = request.reason().strip();
        String payload = toCanonicalJson(new SingleAssignCanonical(customerId, request.newOwnerId(), reason,
                request.version()));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_ASSIGN,
                user.userId(), normalizedKey, payload);
        if (replay.isPresent()) {
            return replaySingleAssign(tenantId, user, customerId, replay.get());
        }

        SysUser newOwner = requireEligibleTargetSteward(tenantId, request.newOwnerId());
        requireTargetStewardScope(user, newOwner);

        Customer customer = scopeSupport.requireVisibleForUpdate(tenantId, customerId, user);
        requireAssignScope(user, customer.getTeamId(), newOwner.getTeamId());
        if (!Objects.equals(customer.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户资料已被他人修改，请刷新后重试");
        }
        if (Objects.equals(customer.getOwnerStaffId(), newOwner.getId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "目标管家与当前归属管家相同，无需重新分配");
        }
        if (transferRepository.existsPendingForCustomer(tenantId, customerId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该客户存在待审批的转交申请，请先处理后再分配");
        }

        Long oldOwnerId = customer.getOwnerStaffId();
        Long oldTeamId = customer.getTeamId();
        int updated = customerRepository.updateOwner(tenantId, customerId, customer.getVersion(),
                newOwner.getId(), newOwner.getTeamId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户资料已被他人修改，请刷新后重试");
        }

        Instant now = Instant.now();
        CustomerOwnerHistory history = new CustomerOwnerHistory();
        history.setTenantId(tenantId);
        history.setCustomerId(customerId);
        history.setOldOwnerId(oldOwnerId);
        history.setNewOwnerId(newOwner.getId());
        history.setChangedBy(user.userId());
        history.setEffectiveAt(now);
        history.setReason(reason);
        history.setApprovalRef(APPROVAL_REF_DIRECT);
        ownerHistoryRepository.insert(history);

        recordAssignAudit(tenantId, user.userId(), customerId, oldOwnerId, oldTeamId,
                newOwner.getId(), newOwner.getTeamId(), history.getId(), MODE_DIRECT_ASSIGN, null, reason);

        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_ASSIGN, user.userId(), normalizedKey,
                "CUSTOMER_OWNER_HISTORY", Long.toString(history.getId()), payload);

        String oldOwnerName = resolveUserDisplayName(tenantId, oldOwnerId, new HashMap<>());
        return new CustomerOwnerAssignmentResponse(history.getId(), customer.getId(), customer.getCustomerNo(),
                customerLabel(customer), oldOwnerId, oldOwnerName, newOwner.getId(), newOwner.getDisplayName(),
                oldTeamId, newOwner.getTeamId(), user.userId(), user.displayName(), reason, APPROVAL_REF_DIRECT,
                now, customer.getVersion() + 1);
    }

    @Transactional
    public BatchAssignCustomerOwnerResponse batchAssignOwners(long tenantId, CurrentUser user,
                                                              BatchAssignCustomerOwnerRequest request,
                                                              String idempotencyKey) {
        String normalizedKey = normalizeKey(idempotencyKey);
        String reason = request.reason().strip();
        validateNoDuplicateCustomers(request.items());

        List<BatchAssignCustomerOwnerRequest.BatchAssignItem> sortedItems = request.items().stream()
                .sorted(Comparator.comparingLong(BatchAssignCustomerOwnerRequest.BatchAssignItem::customerId))
                .toList();
        String payload = toCanonicalJson(new BatchAssignCanonical(sortedItems, request.newOwnerId(), reason));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_BATCH_ASSIGN,
                user.userId(), normalizedKey, payload);
        if (replay.isPresent()) {
            return replayBatchAssign(tenantId, user, request, reason, replay.get());
        }

        SysUser newOwner = requireEligibleTargetSteward(tenantId, request.newOwnerId());
        requireTargetStewardScope(user, newOwner);

        DataScope scope = scopeSupport.scope(user);
        Map<Long, Customer> lockedCustomers = new LinkedHashMap<>();
        for (BatchAssignCustomerOwnerRequest.BatchAssignItem item : sortedItems) {
            Customer customer = customerRepository.findByIdForUpdate(tenantId, item.customerId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
            if (customer.isDeleted() || !scopeSupport.isVisible(scope, user, customer)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见");
            }
            requireAssignScope(user, customer.getTeamId(), newOwner.getTeamId());
            if (!Objects.equals(customer.getVersion(), item.version())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "客户「" + customerLabel(customer) + "（" + customer.getCustomerNo() + "）」资料已被他人修改，请刷新后重试");
            }
            lockedCustomers.put(item.customerId(), customer);
        }

        for (BatchAssignCustomerOwnerRequest.BatchAssignItem item : sortedItems) {
            Customer customer = lockedCustomers.get(item.customerId());
            if (!Objects.equals(customer.getOwnerStaffId(), newOwner.getId())
                    && transferRepository.existsPendingForCustomer(tenantId, customer.getId())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "客户「" + customerLabel(customer) + "（" + customer.getCustomerNo() + "）」存在待审批的转交申请，请先处理后再分配");
            }
        }

        String batchRef = APPROVAL_REF_BATCH_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        Map<Long, String> userNameCache = new HashMap<>();
        userNameCache.put(newOwner.getId(), newOwner.getDisplayName());
        Map<Long, BatchAssignCustomerOwnerResponse.BatchAssignResultItem> resultByCustomerId = new HashMap<>();
        int assignedCount = 0;
        int skippedCount = 0;

        for (BatchAssignCustomerOwnerRequest.BatchAssignItem item : sortedItems) {
            Customer customer = lockedCustomers.get(item.customerId());
            Long oldOwnerId = customer.getOwnerStaffId();
            String oldOwnerName = resolveUserDisplayName(tenantId, oldOwnerId, userNameCache);
            if (Objects.equals(oldOwnerId, newOwner.getId())) {
                skippedCount++;
                resultByCustomerId.put(customer.getId(), new BatchAssignCustomerOwnerResponse.BatchAssignResultItem(
                        customer.getId(), customer.getCustomerNo(), customerLabel(customer), STATUS_SKIPPED,
                        oldOwnerId, oldOwnerName, newOwner.getId(), newOwner.getDisplayName(), null,
                        customer.getVersion(), "已归属目标管家，已跳过"));
                continue;
            }
            int updated = customerRepository.updateOwner(tenantId, customer.getId(), customer.getVersion(),
                    newOwner.getId(), newOwner.getTeamId());
            if (updated == 0) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "客户「" + customerLabel(customer) + "（" + customer.getCustomerNo() + "）」资料已被他人修改，请刷新后重试");
            }
            CustomerOwnerHistory history = new CustomerOwnerHistory();
            history.setTenantId(tenantId);
            history.setCustomerId(customer.getId());
            history.setOldOwnerId(oldOwnerId);
            history.setNewOwnerId(newOwner.getId());
            history.setChangedBy(user.userId());
            history.setEffectiveAt(now);
            history.setReason(reason);
            history.setApprovalRef(batchRef);
            ownerHistoryRepository.insert(history);

            recordAssignAudit(tenantId, user.userId(), customer.getId(), oldOwnerId, customer.getTeamId(),
                    newOwner.getId(), newOwner.getTeamId(), history.getId(), MODE_BATCH_ASSIGN, batchRef, reason);

            assignedCount++;
            resultByCustomerId.put(customer.getId(), new BatchAssignCustomerOwnerResponse.BatchAssignResultItem(
                    customer.getId(), customer.getCustomerNo(), customerLabel(customer), STATUS_ASSIGNED,
                    oldOwnerId, oldOwnerName, newOwner.getId(), newOwner.getDisplayName(), history.getId(),
                    customer.getVersion() + 1, "已分配"));
        }

        if (assignedCount == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "所选客户均已归属目标管家，无需重新分配");
        }

        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_BATCH_ASSIGN, user.userId(), normalizedKey,
                "CUSTOMER_OWNER_BATCH", batchRef, payload);

        List<BatchAssignCustomerOwnerResponse.BatchAssignResultItem> orderedResults = new ArrayList<>(request.items().size());
        for (BatchAssignCustomerOwnerRequest.BatchAssignItem item : request.items()) {
            orderedResults.add(resultByCustomerId.get(item.customerId()));
        }
        return new BatchAssignCustomerOwnerResponse(batchRef, newOwner.getId(), newOwner.getDisplayName(),
                newOwner.getTeamId(), reason, request.items().size(), assignedCount, skippedCount, now, orderedResults);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerOwnershipLogItemResponse> searchOwnershipLogs(long tenantId, CurrentUser user,
                                                                              Long customerId, Long ownerId,
                                                                              String keyword, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        String normalizedKeyword = blankToNull(keyword);
        int offset = (page - 1) * size;
        List<CustomerOwnershipLogItemResponse> items = ownerHistoryRepository
                .searchLogs(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                        customerId, ownerId, normalizedKeyword, offset, size)
                .stream()
                .map(row -> new CustomerOwnershipLogItemResponse(row.id(), row.customerId(), row.customerNo(),
                        row.customerName(), row.oldOwnerId(), row.oldOwnerName(), row.newOwnerId(),
                        row.newOwnerName(), row.changedBy(), row.changedByName(), row.reason(), row.approvalRef(),
                        resolveChangeMode(row.approvalRef()), row.effectiveAt(), row.createdAt()))
                .toList();
        long total = ownerHistoryRepository.countSearchLogs(tenantId, query.scopeType(), query.teamIds(),
                query.selfId(), customerId, ownerId, normalizedKeyword);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ScopedTransferItemResponse> searchScopedTransfers(long tenantId, CurrentUser user,
                                                                          String status, Long ownerId, String keyword,
                                                                          int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        String normalizedStatus = blankToNull(status);
        String normalizedKeyword = blankToNull(keyword);
        int offset = (page - 1) * size;
        List<ScopedTransferItemResponse> items = transferRepository
                .searchScopedRows(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                        normalizedStatus, ownerId, normalizedKeyword, offset, size)
                .stream()
                .map(row -> new ScopedTransferItemResponse(row.id(), row.customerId(), row.customerNo(),
                        row.customerName(), row.fromOwnerId(), row.fromOwnerName(), row.toOwnerId(),
                        row.toOwnerName(), row.fromTeamId(), row.toTeamId(), row.status(), row.requestedBy(),
                        row.requestedByName(), row.reason(), row.reviewedBy(), row.reviewedByName(),
                        row.reviewedAt(), row.reviewReason(), row.baseVersion(), row.effectiveAt(),
                        row.createdAt(), row.version()))
                .toList();
        long total = transferRepository.countScopedRows(tenantId, query.scopeType(), query.teamIds(),
                query.selfId(), normalizedStatus, ownerId, normalizedKeyword);
        return new PageResponse<>(items, total, page, size);
    }

    public static String resolveChangeMode(String approvalRef) {
        if (approvalRef == null || approvalRef.isBlank()) {
            return MODE_DIRECT_ASSIGN;
        }
        if (approvalRef.startsWith(APPROVAL_REF_TRANSFER_PREFIX)) {
            return MODE_TRANSFER_APPROVAL;
        }
        if (approvalRef.startsWith(APPROVAL_REF_BATCH_PREFIX)) {
            return MODE_BATCH_ASSIGN;
        }
        return MODE_DIRECT_ASSIGN;
    }

    private CustomerOwnerAssignmentResponse replaySingleAssign(long tenantId, CurrentUser user, long customerId,
                                                               IdempotencyRecord record) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        long historyId = Long.parseLong(record.getResourceId());
        CustomerOwnerHistoryRepository.OwnerHistoryRow row = ownerHistoryRepository.findRowById(tenantId, historyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "幂等归属记录不存在，请刷新后确认客户当前归属"));
        if (!Objects.equals(row.customerId(), customerId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已用于其他客户，禁止重放");
        }
        SysUser oldOwner = userRepository.findById(tenantId, row.oldOwnerId()).orElse(null);
        SysUser newOwner = userRepository.findById(tenantId, row.newOwnerId()).orElse(null);
        return new CustomerOwnerAssignmentResponse(row.id(), customer.getId(), customer.getCustomerNo(),
                customerLabel(customer), row.oldOwnerId(), row.oldOwnerName(), row.newOwnerId(), row.newOwnerName(),
                oldOwner == null ? null : oldOwner.getTeamId(),
                newOwner == null ? customer.getTeamId() : newOwner.getTeamId(),
                row.changedBy(), row.changedByName(), row.reason(), row.approvalRef(), row.effectiveAt(),
                customer.getVersion());
    }

    private BatchAssignCustomerOwnerResponse replayBatchAssign(long tenantId, CurrentUser user,
                                                               BatchAssignCustomerOwnerRequest request,
                                                               String reason, IdempotencyRecord record) {
        String batchRef = record.getResourceId();
        List<CustomerOwnerHistoryRepository.OwnerHistoryRow> rows = ownerHistoryRepository
                .findRowsByApprovalRef(tenantId, batchRef);
        Map<Long, CustomerOwnerHistoryRepository.OwnerHistoryRow> rowByCustomer = new HashMap<>();
        for (CustomerOwnerHistoryRepository.OwnerHistoryRow row : rows) {
            rowByCustomer.put(row.customerId(), row);
        }
        SysUser newOwner = userRepository.findById(tenantId, request.newOwnerId()).orElse(null);
        String newOwnerName = newOwner != null ? newOwner.getDisplayName() : null;
        Long newTeamId = newOwner != null ? newOwner.getTeamId() : null;
        Instant effectiveAt = rows.isEmpty() ? null : rows.getFirst().effectiveAt();
        List<BatchAssignCustomerOwnerResponse.BatchAssignResultItem> items = new ArrayList<>(request.items().size());
        int assignedCount = 0;
        int skippedCount = 0;
        for (BatchAssignCustomerOwnerRequest.BatchAssignItem item : request.items()) {
            Customer customer = scopeSupport.requireVisible(tenantId, item.customerId(), user);
            CustomerOwnerHistoryRepository.OwnerHistoryRow row = rowByCustomer.get(item.customerId());
            if (row != null) {
                assignedCount++;
                items.add(new BatchAssignCustomerOwnerResponse.BatchAssignResultItem(
                        customer.getId(), customer.getCustomerNo(), customerLabel(customer), STATUS_ASSIGNED,
                        row.oldOwnerId(), row.oldOwnerName(), row.newOwnerId(), row.newOwnerName(), row.id(),
                        customer.getVersion(), "已分配"));
            } else {
                skippedCount++;
                items.add(new BatchAssignCustomerOwnerResponse.BatchAssignResultItem(
                        customer.getId(), customer.getCustomerNo(), customerLabel(customer), STATUS_SKIPPED,
                        request.newOwnerId(), newOwnerName, request.newOwnerId(), newOwnerName, null,
                        customer.getVersion(), "已归属目标管家，已跳过"));
            }
        }
        return new BatchAssignCustomerOwnerResponse(batchRef, request.newOwnerId(), newOwnerName, newTeamId,
                reason, request.items().size(), assignedCount, skippedCount, effectiveAt, items);
    }

    private SysUser requireEligibleTargetSteward(long tenantId, long newOwnerId) {
        SysUser newOwner = userRepository.findById(tenantId, newOwnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标管家不存在"));
        if (!stewardDirectory.isEligibleSteward(tenantId, newOwner)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标管家必须为在职管家或管家主管");
        }
        return newOwner;
    }

    private void requireTargetStewardScope(CurrentUser user, SysUser newOwner) {
        if (user.roles().contains("ADMIN")) {
            return;
        }
        if (!user.roles().contains("MANAGER_LEAD")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管家主管或系统管理员可分配客户");
        }
        if (user.teamId() == null || !Objects.equals(user.teamId(), newOwner.getTeamId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅本组主管可分配本团队客户给本团队管家，跨团队分配需系统管理员办理");
        }
    }

    private void requireAssignScope(CurrentUser user, Long fromTeamId, Long toTeamId) {
        if (user.roles().contains("ADMIN")) {
            return;
        }
        if (!user.roles().contains("MANAGER_LEAD")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管家主管或系统管理员可分配客户");
        }
        Long actorTeam = user.teamId();
        boolean inTeam = actorTeam != null && Objects.equals(actorTeam, fromTeamId)
                && Objects.equals(actorTeam, toTeamId);
        if (!inTeam) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅本组主管可分配本团队客户给本团队管家，跨团队分配需系统管理员办理");
        }
    }

    private void validateNoDuplicateCustomers(List<BatchAssignCustomerOwnerRequest.BatchAssignItem> items) {
        Set<Long> seen = new HashSet<>();
        for (BatchAssignCustomerOwnerRequest.BatchAssignItem item : items) {
            if (!seen.add(item.customerId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "待分配客户列表中包含重复客户");
            }
        }
    }

    private void recordAssignAudit(long tenantId, long actorUserId, long customerId, Long oldOwnerId, Long oldTeamId,
                                   long newOwnerId, Long newTeamId, Long historyId, String mode,
                                   String batchRef, String reason) {
        Map<String, Object> beforeSnapshot = new LinkedHashMap<>();
        beforeSnapshot.put("ownerStaffId", oldOwnerId);
        beforeSnapshot.put("teamId", oldTeamId == null ? "" : oldTeamId);

        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("ownerStaffId", newOwnerId);
        afterSnapshot.put("teamId", newTeamId == null ? "" : newTeamId);
        afterSnapshot.put("historyId", historyId);
        afterSnapshot.put("mode", mode);
        if (batchRef != null) {
            afterSnapshot.put("batchRef", batchRef);
        }
        auditService.record(tenantId, actorUserId, AuditService.ACTION_CUSTOMER_OWNER_ASSIGN,
                "CUSTOMER", Long.toString(customerId), beforeSnapshot, afterSnapshot, reason);
    }

    private String resolveUserDisplayName(long tenantId, Long userId, Map<Long, String> cache) {
        if (userId == null) {
            return null;
        }
        if (cache.containsKey(userId)) {
            return cache.get(userId);
        }
        String displayName = userRepository.findById(tenantId, userId).map(SysUser::getDisplayName).orElse(null);
        cache.put(userId, displayName);
        return displayName;
    }

    private String toCanonicalJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            return String.valueOf(request);
        }
    }

    private static String customerLabel(Customer customer) {
        if (customer.getDisplayName() != null && !customer.getDisplayName().isBlank()) {
            return customer.getDisplayName();
        }
        if (customer.getNickname() != null && !customer.getNickname().isBlank()) {
            return customer.getNickname();
        }
        return customer.getCustomerNo();
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record SingleAssignCanonical(long customerId, Long newOwnerId, String reason, Long version) {
    }

    private record BatchAssignCanonical(List<BatchAssignCustomerOwnerRequest.BatchAssignItem> items,
                                        Long newOwnerId, String reason) {
    }
}
