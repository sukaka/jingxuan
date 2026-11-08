package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.MergeCase;
import com.whale.crm.customer.domain.MergeCaseRepository;
import com.whale.crm.customer.infrastructure.mapper.MergeCaseMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisMergeCaseRepository implements MergeCaseRepository {

    private final MergeCaseMapper mergeCaseMapper;

    public MybatisMergeCaseRepository(MergeCaseMapper mergeCaseMapper) {
        this.mergeCaseMapper = mergeCaseMapper;
    }

    @Override
    public Optional<MergeCase> findById(long tenantId, long id) {
        return Optional.ofNullable(mergeCaseMapper.selectById(tenantId, id));
    }

    @Override
    public boolean existsPair(long tenantId, long primaryCustomerId, long secondaryCustomerId) {
        return mergeCaseMapper.countPair(tenantId, primaryCustomerId, secondaryCustomerId) > 0;
    }

    @Override
    public boolean existsOpenBetween(long tenantId, long customerA, long customerB) {
        return mergeCaseMapper.countOpenBetween(tenantId, customerA, customerB) > 0;
    }

    @Override
    public void insert(MergeCase mergeCase) {
        mergeCaseMapper.insertCase(mergeCase);
    }

    @Override
    public int updateStatus(long tenantId, long id, long version, String toStatus,
                            long reviewerId, Instant reviewedAt) {
        return mergeCaseMapper.updateStatus(tenantId, id, version, toStatus, reviewerId, reviewedAt);
    }

    @Override
    public List<MergeCase> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                  String status, int offset, int size) {
        return mergeCaseMapper.search(tenantId, scopeType, teamIds, selfId, status, offset, size);
    }

    @Override
    public long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId, String status) {
        return mergeCaseMapper.countSearch(tenantId, scopeType, teamIds, selfId, status);
    }
}
