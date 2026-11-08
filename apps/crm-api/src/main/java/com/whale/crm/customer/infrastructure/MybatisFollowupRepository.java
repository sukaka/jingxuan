package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.customer.infrastructure.mapper.FollowupMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisFollowupRepository implements FollowupRepository {

    private final FollowupMapper followupMapper;

    public MybatisFollowupRepository(FollowupMapper followupMapper) {
        this.followupMapper = followupMapper;
    }

    @Override
    public List<FollowupListRow> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                        Long customerId, int offset, int size) {
        return followupMapper.search(tenantId, scopeType, teamIds, selfId, customerId, offset, size);
    }

    @Override
    public long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId) {
        return followupMapper.countSearch(tenantId, scopeType, teamIds, selfId, customerId);
    }

    @Override
    public Optional<Followup> findById(long tenantId, long id) {
        return Optional.ofNullable(followupMapper.selectById(tenantId, id));
    }

    @Override
    public void insert(Followup followup) {
        followupMapper.insertFollowup(followup);
    }

    @Override
    public int update(Followup followup) {
        return followupMapper.updateFollowup(followup);
    }

    @Override
    public int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId) {
        return followupMapper.reassignCustomer(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int reassignSpecific(long tenantId, List<Long> followupIds, long fromCustomerId, long toCustomerId) {
        return followupMapper.reassignSpecific(tenantId, followupIds, fromCustomerId, toCustomerId);
    }

    @Override
    public List<Long> findIdsByCustomerId(long tenantId, long customerId) {
        return followupMapper.findIdsByCustomerId(tenantId, customerId);
    }
}
