package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.MergeReversal;
import com.whale.crm.customer.domain.MergeReversalRepository;
import com.whale.crm.customer.infrastructure.mapper.MergeReversalMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class MybatisMergeReversalRepository implements MergeReversalRepository {
    private final MergeReversalMapper mapper;

    public MybatisMergeReversalRepository(MergeReversalMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(long tenantId, MergeReversal reversal) { mapper.insert(tenantId, reversal); }
    public Optional<MergeReversal> findById(long tenantId, long id) { return Optional.ofNullable(mapper.select(tenantId, id)); }
    public Optional<MergeReversal> findForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectForUpdate(tenantId, id)); }
    public Optional<MergeReversal> findByMergeCaseId(long tenantId, long mergeCaseId) { return Optional.ofNullable(mapper.selectByMergeCaseId(tenantId, mergeCaseId)); }
    public int updateStatus(long tenantId, long id, long version, String status, String reviewReason,
                            long reviewedBy, Instant reviewedAt, Instant executedAt, String warningMessage, String errorMasked) {
        return mapper.updateStatus(tenantId, id, version, status, reviewReason, reviewedBy, reviewedAt, executedAt, warningMessage, errorMasked);
    }
}
