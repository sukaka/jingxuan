package com.whale.crm.customer.domain;

import java.util.List;
import java.util.Optional;

/**
 * crm_followup 存取。范围条件与客户列表同语义（join 客户后按 team/owner 过滤）。
 */
public interface FollowupRepository {

    List<FollowupListRow> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                 Long customerId, int offset, int size);

    long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId);

    Optional<Followup> findById(long tenantId, long id);

    void insert(Followup followup);

    /**
     * 乐观锁更新；0 = 版本冲突或不存在。
     */
    int update(Followup followup);

    int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId);

    int reassignSpecific(long tenantId, List<Long> followupIds, long fromCustomerId, long toCustomerId);

    List<Long> findIdsByCustomerId(long tenantId, long customerId);

    record FollowupListRow(Long id, Long customerId, String customerName, Long ownerStaffId,
                           String ownerStaffName, String channelCode, java.time.Instant contactAt, String summary,
                           String intentProject, Long estimatedAmountFen, java.time.Instant nextFollowupAt,
                           String blockerNote, Long createdBy, java.time.Instant createdAt, Long version) {
    }
}
