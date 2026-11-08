package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.MergeResourceSnapshot;
import com.whale.crm.customer.domain.MergeResourceSnapshotRepository;
import com.whale.crm.customer.infrastructure.mapper.MergeResourceSnapshotMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisMergeResourceSnapshotRepository implements MergeResourceSnapshotRepository {
    private final MergeResourceSnapshotMapper mapper;

    public MybatisMergeResourceSnapshotRepository(MergeResourceSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(long tenantId, MergeResourceSnapshot snapshot) { mapper.insert(tenantId, snapshot); }
    public List<MergeResourceSnapshot> findByMergeCaseId(long tenantId, long mergeCaseId) { return mapper.selectByMergeCaseId(tenantId, mergeCaseId); }
}
