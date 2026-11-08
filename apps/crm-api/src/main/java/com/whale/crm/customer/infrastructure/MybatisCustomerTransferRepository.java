package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerTransfer;
import com.whale.crm.customer.domain.CustomerTransferRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerTransferMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisCustomerTransferRepository implements CustomerTransferRepository {

    private final CustomerTransferMapper mapper;

    public MybatisCustomerTransferRepository(CustomerTransferMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<CustomerTransfer> findById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectById(tenantId, id));
    }

    @Override
    public List<CustomerTransfer> findByCustomerId(long tenantId, long customerId) {
        return mapper.selectByCustomerId(tenantId, customerId);
    }

    @Override
    public boolean existsPendingForCustomer(long tenantId, long customerId) {
        return mapper.countPendingForCustomer(tenantId, customerId) > 0;
    }

    @Override
    public void insert(CustomerTransfer transfer) {
        mapper.insertTransfer(transfer);
    }

    @Override
    public int updateStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                            Long reviewedBy, Instant reviewedAt, String reviewReason, Instant effectiveAt) {
        return mapper.updateStatus(tenantId, id, version, fromStatus, toStatus, reviewedBy, reviewedAt,
                reviewReason, effectiveAt);
    }

    @Override
    public List<TransferRow> findRowsByCustomerId(long tenantId, long customerId) {
        return mapper.selectRowsByCustomerId(tenantId, customerId);
    }
}
