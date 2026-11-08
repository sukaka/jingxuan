package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisCustomerRepository implements CustomerRepository {

    private final CustomerMapper customerMapper;

    public MybatisCustomerRepository(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public List<CustomerListRow> search(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                        String keyword, String mobileHashKeyword, String status, String cityCode,
                                        String sourceCode, String channelCode, String tierCode, Long ownerId,
                                        Instant followupDueBefore, int offset, int size) {
        return customerMapper.search(tenantId, scopeType, teamIds, selfId, keyword, mobileHashKeyword, status,
                cityCode, sourceCode, channelCode, tierCode, ownerId, followupDueBefore, offset, size);
    }

    @Override
    public long countSearch(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                            String keyword, String mobileHashKeyword, String status, String cityCode,
                            String sourceCode, String channelCode, String tierCode, Long ownerId,
                            Instant followupDueBefore) {
        return customerMapper.countSearch(tenantId, scopeType, teamIds, selfId, keyword, mobileHashKeyword, status,
                cityCode, sourceCode, channelCode, tierCode, ownerId, followupDueBefore);
    }

    @Override
    public Optional<Customer> findById(long tenantId, long id) {
        return Optional.ofNullable(customerMapper.selectById(tenantId, id));
    }

    @Override
    public Optional<Customer> findByIdForUpdate(long tenantId, long id) {
        return Optional.ofNullable(customerMapper.selectByIdForUpdate(tenantId, id));
    }

    @Override
    public void insert(Customer customer) {
        customerMapper.insertCustomer(customer);
    }

    @Override
    public int backfillCustomerNo(long tenantId, long id, String customerNo) {
        return customerMapper.backfillCustomerNo(tenantId, id, customerNo);
    }

    @Override
    public int updateProfile(Customer customer) {
        return customerMapper.updateProfile(customer);
    }

    @Override
    public int updateOwner(long tenantId, long id, long version, long newOwnerId, Long newTeamId) {
        return customerMapper.updateOwner(tenantId, id, version, newOwnerId, newTeamId);
    }

    @Override
    public int updateLastFollowupAt(long tenantId, long id, Instant lastFollowupAt) {
        return customerMapper.updateLastFollowupAt(tenantId, id, lastFollowupAt);
    }

    @Override
    public int markFirstOrderIfNull(long tenantId, long id, Instant paidAt) {
        return customerMapper.markFirstOrderIfNull(tenantId, id, paidAt);
    }

    @Override
    public int refreshPointsCached(long tenantId, long id, int newBalance) {
        return customerMapper.refreshPointsCached(tenantId, id, newBalance);
    }

    @Override
    public int softDelete(long tenantId, long id, long version) {
        return customerMapper.softDelete(tenantId, id, version);
    }

    @Override
    public int restore(long tenantId, long id, long version) {
        return customerMapper.restore(tenantId, id, version);
    }

    @Override
    public int countByMobileHash(long tenantId, String mobileHash) {
        return customerMapper.countByMobileHash(tenantId, mobileHash);
    }

    @Override
    public List<DuplicateMobileGroup> findDuplicateMobileGroups(long tenantId) {
        return customerMapper.selectDuplicatedMobileHashes(tenantId).stream()
                .map(hash -> new DuplicateMobileGroup(hash,
                        customerMapper.selectCustomerIdsByMobileHash(tenantId, hash)))
                .toList();
    }

    @Override
    public List<DuplicateIdentityGroup> findDuplicateIdentityGroups(long tenantId) {
        return customerMapper.selectDuplicatedExternalIds(tenantId).stream()
                .map(externalId -> new DuplicateIdentityGroup(externalId,
                        customerMapper.selectCustomerIdsByExternalId(tenantId, externalId)))
                .toList();
    }
}
