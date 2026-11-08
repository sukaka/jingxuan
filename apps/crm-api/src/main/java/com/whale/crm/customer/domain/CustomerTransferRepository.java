package com.whale.crm.customer.domain;

import java.util.List;
import java.util.Optional;

/**
 * crm_customer_transfer 存取（转交审批单）。
 */
public interface CustomerTransferRepository {

    Optional<CustomerTransfer> findById(long tenantId, long id);

    List<CustomerTransfer> findByCustomerId(long tenantId, long customerId);

    boolean existsPendingForCustomer(long tenantId, long customerId);

    void insert(CustomerTransfer transfer);

    /**
     * 乐观锁状态流转（PENDING → EFFECTIVE/REJECTED），0 = 版本/状态冲突。
     */
    int updateStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                     Long reviewedBy, java.time.Instant reviewedAt, String reviewReason,
                     java.time.Instant effectiveAt);

    /**
     * 联表用户名投影（展示用）。
     */
    List<TransferRow> findRowsByCustomerId(long tenantId, long customerId);

    record TransferRow(Long id, Long customerId, Long fromOwnerId, String fromOwnerName,
                       Long toOwnerId, String toOwnerName, Long fromTeamId, Long toTeamId,
                       String status, Long requestedBy, String requestedByName, String reason,
                       Long reviewedBy, String reviewedByName, java.time.Instant reviewedAt,
                       String reviewReason, Long baseVersion, java.time.Instant effectiveAt,
                       java.time.Instant createdAt, Long version) {
    }
}
