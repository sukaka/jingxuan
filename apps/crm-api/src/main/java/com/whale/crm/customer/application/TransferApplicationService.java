package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.ApproveTransferRequest;
import com.whale.crm.customer.api.dto.CreateTransferRequest;
import com.whale.crm.customer.api.dto.RejectTransferRequest;
import com.whale.crm.customer.api.dto.TransferItemResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTransfer;
import com.whale.crm.customer.domain.CustomerTransferRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 客户转交审批（docs/11 第 7 节 PENDING → APPROVED → EFFECTIVE）。
 * 申请不改归属；仅 approve 在同一事务写 owner/team + 追加不可覆盖 owner_history。
 * 组内 MANAGER_LEAD 可办理本组客户（新旧管家均须在本组）；跨团队仅 ADMIN。
 * baseVersion 为申请时客户乐观锁快照，审批时失配 → 409 复核。
 */
@Service
public class TransferApplicationService {

    private final CustomerRepository customerRepository;
    private final CustomerTransferRepository transferRepository;
    private final CustomerOwnerHistoryRepository ownerHistoryRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final StewardDirectory stewardDirectory;

    public TransferApplicationService(CustomerRepository customerRepository,
                                      CustomerTransferRepository transferRepository,
                                      CustomerOwnerHistoryRepository ownerHistoryRepository,
                                      UserRepository userRepository,
                                      CustomerScopeSupport scopeSupport,
                                      AuditService auditService,
                                      StewardDirectory stewardDirectory) {
        this.customerRepository = customerRepository;
        this.transferRepository = transferRepository;
        this.ownerHistoryRepository = ownerHistoryRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.stewardDirectory = stewardDirectory;
    }

    @Transactional
    public TransferItemResponse create(long tenantId, CurrentUser user, long customerId,
                                       CreateTransferRequest request) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        SysUser newOwner = userRepository.findById(tenantId, request.newOwnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "新管家不存在"));
        if (!stewardDirectory.isEligibleSteward(tenantId, newOwner)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "新管家必须为在职管家或管家主管");
        }
        if (Objects.equals(newOwner.getId(), customer.getOwnerStaffId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "新管家与当前归属相同，无需转交");
        }
        requireTransferScope(user, customer.getTeamId(), newOwner.getTeamId());
        if (transferRepository.existsPendingForCustomer(tenantId, customerId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该客户已有待审批的转交单");
        }

        CustomerTransfer transfer = new CustomerTransfer();
        transfer.setTenantId(tenantId);
        transfer.setCustomerId(customerId);
        transfer.setFromOwnerId(customer.getOwnerStaffId());
        transfer.setToOwnerId(newOwner.getId());
        transfer.setFromTeamId(customer.getTeamId());
        transfer.setToTeamId(newOwner.getTeamId());
        transfer.setStatus(CustomerTransfer.STATUS_PENDING);
        transfer.setRequestedBy(user.userId());
        transfer.setReason(request.reason());
        transfer.setBaseVersion(customer.getVersion());
        transferRepository.insert(transfer);

        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_TRANSFER_REQUEST,
                "CUSTOMER", Long.toString(customerId), null,
                transferSnapshot(transferRepository.findById(tenantId, transfer.getId()).orElseThrow()),
                request.reason());
        return toResponse(transferRepository.findRowsByCustomerId(tenantId, customerId).stream()
                .filter(row -> row.id().equals(transfer.getId())).findFirst().orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<TransferItemResponse> listByCustomer(long tenantId, CurrentUser user, long customerId) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        return transferRepository.findRowsByCustomerId(tenantId, customerId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public TransferItemResponse approve(long tenantId, CurrentUser user, long transferId,
                                        ApproveTransferRequest request) {
        CustomerTransfer transfer = transferRepository.findById(tenantId, transferId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "转交单不存在"));
        if (!Objects.equals(transfer.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        if (!transfer.isPending()) {
            throw new BusinessException(ErrorCode.CONFLICT, "转交单已处理（当前状态 " + transfer.getStatus() + "）");
        }
        requireTransferScope(user, transfer.getFromTeamId(), transfer.getToTeamId());

        // 行锁客户，复核 baseVersion（docs/11：审批期间资料被变更则暂停执行）
        Customer customer = customerRepository.findByIdForUpdate(tenantId, transfer.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        if (customer.isDeleted()) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户已归档，无法执行转交");
        }
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "转交单不存在");
        }
        if (!Objects.equals(customer.getVersion(), transfer.getBaseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户资料在审批期间已被变更，请复核后重新发起转交");
        }
        SysUser newOwner = userRepository.findById(tenantId, transfer.getToOwnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "新管家不存在，无法执行转交"));
        if (!stewardDirectory.isEligibleSteward(tenantId, newOwner)) {
            throw new BusinessException(ErrorCode.CONFLICT, "新管家已停用或不再具备管家角色，无法执行转交");
        }

        Instant now = Instant.now();
        int updated = transferRepository.updateStatus(tenantId, transferId, request.version(),
                CustomerTransfer.STATUS_PENDING, CustomerTransfer.STATUS_EFFECTIVE,
                user.userId(), now, null, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        int ownerUpdated = customerRepository.updateOwner(tenantId, customer.getId(), customer.getVersion(),
                newOwner.getId(), newOwner.getTeamId());
        if (ownerUpdated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        CustomerOwnerHistory history = new CustomerOwnerHistory();
        history.setTenantId(tenantId);
        history.setCustomerId(customer.getId());
        history.setOldOwnerId(transfer.getFromOwnerId());
        history.setNewOwnerId(transfer.getToOwnerId());
        history.setChangedBy(user.userId());
        history.setEffectiveAt(now);
        history.setReason(transfer.getReason());
        history.setApprovalRef("TR-" + transfer.getId());
        ownerHistoryRepository.insert(history);

        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_TRANSFER_APPROVE,
                "CUSTOMER", Long.toString(customer.getId()),
                Map.of("ownerStaffId", transfer.getFromOwnerId(), "teamId",
                        transfer.getFromTeamId() == null ? "" : transfer.getFromTeamId()),
                Map.of("ownerStaffId", transfer.getToOwnerId(), "teamId",
                        transfer.getToTeamId() == null ? "" : transfer.getToTeamId(),
                        "transferId", transfer.getId()),
                transfer.getReason());
        return toResponse(transferRepository.findRowsByCustomerId(tenantId, customer.getId()).stream()
                .filter(row -> row.id().equals(transferId)).findFirst().orElseThrow());
    }

    @Transactional
    public TransferItemResponse reject(long tenantId, CurrentUser user, long transferId,
                                       RejectTransferRequest request) {
        CustomerTransfer transfer = transferRepository.findById(tenantId, transferId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "转交单不存在"));
        if (!Objects.equals(transfer.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        if (!transfer.isPending()) {
            throw new BusinessException(ErrorCode.CONFLICT, "转交单已处理（当前状态 " + transfer.getStatus() + "）");
        }
        Customer customer = customerRepository.findById(tenantId, transfer.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "转交单不存在"));
        if (!scopeSupport.isVisible(scopeSupport.scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "转交单不存在");
        }
        requireTransferScope(user, transfer.getFromTeamId(), transfer.getToTeamId());

        int updated = transferRepository.updateStatus(tenantId, transferId, request.version(),
                CustomerTransfer.STATUS_PENDING, CustomerTransfer.STATUS_REJECTED,
                user.userId(), Instant.now(), request.reason(), null);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_TRANSFER_REJECT,
                "CUSTOMER", Long.toString(transfer.getCustomerId()),
                transferSnapshot(transfer), null, request.reason());
        return toResponse(transferRepository.findRowsByCustomerId(tenantId, transfer.getCustomerId()).stream()
                .filter(row -> row.id().equals(transferId)).findFirst().orElseThrow());
    }

    /**
     * 组内 MANAGER_LEAD：新旧管家均须属于本人团队；跨团队（任一端越组）仅 ADMIN。
     */
    private void requireTransferScope(CurrentUser user, Long fromTeamId, Long toTeamId) {
        if (user.roles().contains("ADMIN")) {
            return;
        }
        Long actorTeam = user.teamId();
        boolean inTeam = actorTeam != null && Objects.equals(actorTeam, fromTeamId)
                && Objects.equals(actorTeam, toTeamId);
        if (!inTeam) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "跨团队转交仅 ADMIN 可办理");
        }
    }

    private Map<String, Object> transferSnapshot(CustomerTransfer transfer) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", transfer.getId());
        snapshot.put("customerId", transfer.getCustomerId());
        snapshot.put("fromOwnerId", transfer.getFromOwnerId());
        snapshot.put("toOwnerId", transfer.getToOwnerId());
        snapshot.put("fromTeamId", transfer.getFromTeamId());
        snapshot.put("toTeamId", transfer.getToTeamId());
        snapshot.put("status", transfer.getStatus());
        snapshot.put("requestedBy", transfer.getRequestedBy());
        return snapshot;
    }

    private TransferItemResponse toResponse(CustomerTransferRepository.TransferRow row) {
        return new TransferItemResponse(row.id(), row.customerId(), row.fromOwnerId(), row.fromOwnerName(),
                row.toOwnerId(), row.toOwnerName(), row.fromTeamId(), row.toTeamId(), row.status(),
                row.requestedBy(), row.requestedByName(), row.reason(), row.reviewedBy(), row.reviewedByName(),
                row.reviewedAt(), row.reviewReason(), row.baseVersion(), row.effectiveAt(), row.createdAt(),
                row.version());
    }
}
