package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CreateFollowupRequest;
import com.whale.crm.customer.api.dto.FollowupItemResponse;
import com.whale.crm.customer.api.dto.UpdateFollowupRequest;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每日跟进（crm_followup）。每次跟进追加新流水，不覆盖历史；创建后同事务刷新客户 last_followup_at。
 * 设置或修改下次跟进时间时，同事务生成或调整 OPEN 回访任务，不改变其他任务状态。
 * 修改/软删除权限：创建者本人，或客户同组主管（MANAGER_LEAD），或 ADMIN。
 */
@Service
public class FollowupApplicationService {

    private final FollowupRepository followupRepository;
    private final CustomerRepository customerRepository;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final TaskApplicationService taskService;

    public FollowupApplicationService(FollowupRepository followupRepository,
                                      CustomerRepository customerRepository,
                                      CustomerScopeSupport scopeSupport,
                                      AuditService auditService,
                                      TaskApplicationService taskService) {
        this.followupRepository = followupRepository;
        this.customerRepository = customerRepository;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.taskService = taskService;
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowupItemResponse> search(long tenantId, CurrentUser user, Long customerId,
                                                     int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        if (customerId != null) {
            // 指定客户时先校验可见性，避免借跟进列表探测他人客户
            scopeSupport.requireVisible(tenantId, customerId, user);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<FollowupItemResponse> items = followupRepository
                .search(tenantId, query.scopeType(), query.teamIds(), query.selfId(), customerId, offset, size)
                .stream().map(this::toResponse).toList();
        long total = followupRepository.countSearch(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                customerId);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional
    public FollowupItemResponse create(long tenantId, CurrentUser user, CreateFollowupRequest request) {
        Customer customer = scopeSupport.requireVisible(tenantId, request.customerId(), user);
        Instant contactAt = request.contactAt() != null ? request.contactAt() : Instant.now();

        Followup followup = new Followup();
        followup.setTenantId(tenantId);
        followup.setCustomerId(customer.getId());
        followup.setOwnerStaffId(customer.getOwnerStaffId());
        followup.setChannelCode(request.channelCode());
        followup.setContactAt(contactAt);
        followup.setSummary(blankToNull(request.summary()));
        followup.setIntentProject(blankToNull(request.intentProject()));
        followup.setEstimatedAmountFen(request.estimatedAmountFen() != null ? request.estimatedAmountFen() : 0L);
        followup.setNextFollowupAt(request.nextFollowupAt());
        followup.setBlockerNote(blankToNull(request.blockerNote()));
        followup.setCreatedBy(user.userId());
        followupRepository.insert(followup);
        customerRepository.updateLastFollowupAt(tenantId, customer.getId(), contactAt);

        Followup persisted = followupRepository.findById(tenantId, followup.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_FOLLOWUP_CREATE, "FOLLOWUP",
                Long.toString(followup.getId()), null, followupSnapshot(persisted), null);
        taskService.syncFromFollowup(tenantId, user, persisted, customer);
        return toResponse(persisted, null);
    }

    @Transactional
    public FollowupItemResponse update(long tenantId, CurrentUser user, long followupId,
                                       UpdateFollowupRequest request) {
        Followup existing = followupRepository.findById(tenantId, followupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (existing.isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        Customer customer = customerRepository.findById(tenantId, existing.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        requireEditable(user, existing, customer);
        if (!Objects.equals(existing.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }

        Instant previousNextFollowupAt = existing.getNextFollowupAt();
        Map<String, Object> beforeSnapshot = followupSnapshot(existing);
        existing.setChannelCode(request.channelCode() != null ? request.channelCode() : existing.getChannelCode());
        existing.setContactAt(request.contactAt() != null ? request.contactAt() : existing.getContactAt());
        existing.setSummary(request.summary() != null ? blankToNull(request.summary()) : existing.getSummary());
        existing.setIntentProject(request.intentProject() != null ? blankToNull(request.intentProject()) : existing.getIntentProject());
        existing.setEstimatedAmountFen(request.estimatedAmountFen() != null ? request.estimatedAmountFen() : existing.getEstimatedAmountFen());
        existing.setNextFollowupAt(request.nextFollowupAt() != null ? request.nextFollowupAt() : existing.getNextFollowupAt());
        existing.setBlockerNote(request.blockerNote() != null ? blankToNull(request.blockerNote()) : existing.getBlockerNote());
        int rows = followupRepository.update(existing);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Followup after = followupRepository.findById(tenantId, followupId).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_FOLLOWUP_UPDATE, "FOLLOWUP",
                Long.toString(followupId), beforeSnapshot, followupSnapshot(after), null);
        if (!Objects.equals(previousNextFollowupAt, after.getNextFollowupAt())) {
            taskService.syncFromFollowup(tenantId, user, after, customer);
        }
        return toResponse(after, null);
    }

    @Transactional
    public void delete(long tenantId, CurrentUser user, long followupId, long version) {
        Followup existing = followupRepository.findById(tenantId, followupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (existing.isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        Customer customer = customerRepository.findById(tenantId, existing.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见"));
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "跟进记录不存在或不可见");
        }
        requireEditable(user, existing, customer);
        if (existing.getVersion() != version) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Map<String, Object> beforeSnapshot = followupSnapshot(existing);
        existing.setDeleted(true);
        if (followupRepository.update(existing) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        auditService.record(tenantId, user.userId(), "FOLLOWUP_DELETE", "FOLLOWUP",
                Long.toString(followupId), beforeSnapshot, followupSnapshot(existing), "误录记录软删除");
    }

    /**
     * 创建者本人 / 客户同组 MANAGER_LEAD / ADMIN 可改。
     */
    private void requireEditable(CurrentUser user, Followup followup, Customer customer) {
        if (Objects.equals(followup.getCreatedBy(), user.userId()) || user.roles().contains("ADMIN")) {
            return;
        }
        boolean teamLead = user.roles().contains("MANAGER_LEAD") && user.teamId() != null
                && Objects.equals(user.teamId(), customer.getTeamId());
        if (!teamLead) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅创建者或同组主管可修改跟进记录");
        }
    }

    private Map<String, Object> followupSnapshot(Followup followup) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", followup.getId());
        snapshot.put("customerId", followup.getCustomerId());
        snapshot.put("ownerStaffId", followup.getOwnerStaffId());
        snapshot.put("channelCode", followup.getChannelCode());
        snapshot.put("contactAt", followup.getContactAt());
        snapshot.put("intentProject", followup.getIntentProject());
        snapshot.put("estimatedAmountFen", followup.getEstimatedAmountFen());
        snapshot.put("nextFollowupAt", followup.getNextFollowupAt());
        snapshot.put("createdBy", followup.getCreatedBy());
        snapshot.put("deleted", followup.isDeleted());
        return snapshot;
    }

    private FollowupItemResponse toResponse(FollowupRepository.FollowupListRow row) {
        return new FollowupItemResponse(row.id(), row.customerId(), row.customerName(), row.ownerStaffId(),
                row.ownerStaffName(), row.channelCode(), row.contactAt(), row.summary(), row.intentProject(), row.estimatedAmountFen(),
                row.nextFollowupAt(), row.blockerNote(), row.createdBy(), row.createdAt(), row.version());
    }

    private FollowupItemResponse toResponse(Followup followup, String customerName) {
        return new FollowupItemResponse(followup.getId(), followup.getCustomerId(), customerName,
                followup.getOwnerStaffId(), null, followup.getChannelCode(), followup.getContactAt(),
                followup.getSummary(), followup.getIntentProject(), followup.getEstimatedAmountFen(),
                followup.getNextFollowupAt(), followup.getBlockerNote(), followup.getCreatedBy(),
                followup.getCreatedAt(), followup.getVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
