package com.whale.crm.customer.domain;

import java.util.List;
import java.util.Optional;

/**
 * crm_customer 存取。所有查询必须显式 tenantId；列表/详情由调用方先取 DataScope 再传入收窄条件，
 * 禁止无范围参数的"全量查询"泄漏给越权调用方。
 */
public interface CustomerRepository {

    /**
     * 范围过滤搜索。scopeType: ALL/TEAM/SELF；TEAM 传 teamIds 且含本人客户；SELF 仅 owner=selfId。
     * ownerId 只允许在范围之上继续收窄；mobileHashKeyword 命中盲索引做等值查（不明文 LIKE）。
     */
    List<CustomerListRow> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                 String keyword, String mobileHashKeyword, String status, String cityCode,
                                 String sourceCode, String channelCode, String tierCode, Long ownerId,
                                 java.time.Instant followupDueBefore, int offset, int size);

    long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                     String keyword, String mobileHashKeyword, String status, String cityCode,
                     String sourceCode, String channelCode, String tierCode, Long ownerId,
                     java.time.Instant followupDueBefore);

    Optional<Customer> findById(long tenantId, long id);

    /**
     * 行锁读取（合并确认/转交审批/积分记账用），配合事务内 SELECT ... FOR UPDATE。
     */
    Optional<Customer> findByIdForUpdate(long tenantId, long id);

    void insert(Customer customer);

    /**
     * 同事务回填 customer_no = 'C' + id 左补零（创建后立即执行）。
     */
    int backfillCustomerNo(long tenantId, long id, String customerNo);

    /**
     * 乐观锁全字段更新（PATCH 白名单展开后的实体）；0 = 版本冲突或不可见。
     */
    int updateProfile(Customer customer);

    int updateOwner(long tenantId, long id, long version, long newOwnerId, Long newTeamId);

    int updateLastFollowupAt(long tenantId, long id, java.time.Instant lastFollowupAt);

    int markFirstOrderIfNull(long tenantId, long id, java.time.Instant paidAt);

    int refreshPointsCached(long tenantId, long id, int newBalance);

    int softDelete(long tenantId, long id, long version);

    int restore(long tenantId, long id, long version);

    /**
     * 同盲索引未归档客户数（"电话-是否重复"计算）。
     */
    int countByMobileHash(long tenantId, String mobileHash);

    /**
     * 查重扫描：同 mobile_hash 且未归档的客户组（hash → 客户 id 升序列表）。
     */
    List<DuplicateMobileGroup> findDuplicateMobileGroups(long tenantId);

    /**
     * 查重扫描：同 identity external_id 跨客户的分组（external_id → 客户 id 升序列表，剔除已归档）。
     */
    List<DuplicateIdentityGroup> findDuplicateIdentityGroups(long tenantId);

    /**
     * 查重候选分组投影。
     */
    record DuplicateMobileGroup(String mobileHash, List<Long> customerIds) {
        public DuplicateMobileGroup {
            customerIds = customerIds == null ? null : List.copyOf(customerIds);
        }
    }

    record DuplicateIdentityGroup(String externalId, List<Long> customerIds) {
        public DuplicateIdentityGroup {
            customerIds = customerIds == null ? null : List.copyOf(customerIds);
        }
    }
}
