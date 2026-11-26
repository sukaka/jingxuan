package com.whale.crm.customer.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 客户域读取企微活跃跟进关系的跨模块只读端口（避免跨模块直接查 WeCom Mapper）。
 */
public interface CustomerWecomFollowReader {

    CustomerWecomFollowReader NOOP = new CustomerWecomFollowReader() {
        @Override
        public List<WecomFollowFact> findActiveByCustomersOrKeys(long tenantId, Collection<Long> customerIds,
                                                                 Collection<String> externalUserids,
                                                                 Collection<String> unionids) {
            return List.of();
        }

        @Override
        public List<WecomFollowFact> findCandidateMultiStewardFollows(long tenantId) {
            return List.of();
        }
    };

    List<WecomFollowFact> findActiveByCustomersOrKeys(long tenantId, Collection<Long> customerIds,
                                                      Collection<String> externalUserids,
                                                      Collection<String> unionids);

    List<WecomFollowFact> findCandidateMultiStewardFollows(long tenantId);

    record WecomFollowFact(
            Long id,
            String corpId,
            String externalUserid,
            String unionid,
            Long staffUserId,
            Long customerId,
            String contactName,
            Instant followCreatedAt,
            Instant lastSyncedAt) {
    }
}
