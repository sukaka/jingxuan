package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.PointsLedgerEntry;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.customer.infrastructure.mapper.PointsLedgerMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisPointsLedgerRepository implements PointsLedgerRepository {

    private final PointsLedgerMapper pointsLedgerMapper;

    public MybatisPointsLedgerRepository(PointsLedgerMapper pointsLedgerMapper) {
        this.pointsLedgerMapper = pointsLedgerMapper;
    }

    @Override
    public List<PointsLedgerEntry> findByCustomerId(long tenantId, long customerId) {
        return pointsLedgerMapper.selectByCustomerId(tenantId, customerId);
    }

    @Override
    public long sumDelta(long tenantId, long customerId) {
        Long sum = pointsLedgerMapper.sumDelta(tenantId, customerId);
        return sum == null ? 0L : sum;
    }

    @Override
    public void insert(PointsLedgerEntry entry) {
        pointsLedgerMapper.insertEntry(entry);
    }

    @Override
    public int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId) {
        return pointsLedgerMapper.reassignCustomer(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int reassignSpecific(long tenantId, List<Long> entryIds, long fromCustomerId, long toCustomerId) {
        return pointsLedgerMapper.reassignSpecific(tenantId, entryIds, fromCustomerId, toCustomerId);
    }
}
