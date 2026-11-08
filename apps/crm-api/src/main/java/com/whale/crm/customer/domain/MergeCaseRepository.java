package com.whale.crm.customer.domain;

import java.util.List;
import java.util.Optional;

/**
 * crm_merge_case 存取。(tenant_id, primary, secondary) 唯一，扫描幂等依赖该约束。
 */
public interface MergeCaseRepository {

    Optional<MergeCase> findById(long tenantId, long id);

    boolean existsPair(long tenantId, long primaryCustomerId, long secondaryCustomerId);

    /**
     * 任一方向存在未终结（SUGGESTED/REVIEWING）的合并单。
     */
    boolean existsOpenBetween(long tenantId, long customerA, long customerB);

    void insert(MergeCase mergeCase);

    /**
     * 乐观锁状态流转；0 = 版本/状态冲突。
     */
    int updateStatus(long tenantId, long id, long version, String toStatus,
                     long reviewerId, java.time.Instant reviewedAt);

    List<MergeCase> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                           String status, int offset, int size);

    long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId, String status);
}
