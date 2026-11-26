package com.whale.crm.integration.wecom.application;

import com.whale.crm.customer.application.CustomerWecomFollowReader;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 为客户唯一性多管家排查提供只读企微活跃好友跟进关系。
 */
@Component
public class WecomCustomerFollowReader implements CustomerWecomFollowReader {

    private final WecomRepository wecomRepository;

    public WecomCustomerFollowReader(WecomRepository wecomRepository) {
        this.wecomRepository = wecomRepository;
    }

    @Override
    public List<WecomFollowFact> findActiveByCustomersOrKeys(long tenantId, Collection<Long> customerIds,
                                                             Collection<String> externalUserids,
                                                             Collection<String> unionids) {
        List<Long> cids = customerIds == null ? List.of() : customerIds.stream().distinct().toList();
        List<String> exts = externalUserids == null ? List.of()
                : externalUserids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        List<String> unions = unionids == null ? List.of()
                : unionids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (cids.isEmpty() && exts.isEmpty() && unions.isEmpty()) {
            return List.of();
        }
        return wecomRepository.findActiveContactsForUniqueness(tenantId, cids, exts, unions).stream()
                .map(this::toFact)
                .toList();
    }

    @Override
    public List<WecomFollowFact> findCandidateMultiStewardFollows(long tenantId) {
        return wecomRepository.findCandidateMultiStewardContacts(tenantId).stream()
                .map(this::toFact)
                .toList();
    }

    private WecomFollowFact toFact(WecomContact contact) {
        return new WecomFollowFact(
                contact.id(),
                contact.corpId(),
                contact.externalUserid(),
                contact.unionid(),
                contact.staffUserId(),
                contact.customerId(),
                contact.name(),
                contact.followCreatedAt(),
                contact.lastSyncedAt());
    }
}
