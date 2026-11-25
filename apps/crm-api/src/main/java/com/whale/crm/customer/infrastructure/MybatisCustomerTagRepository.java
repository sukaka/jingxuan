package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerTagMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class MybatisCustomerTagRepository implements CustomerTagRepository {

    private static final int BATCH_SIZE = 500;

    private final CustomerTagMapper customerTagMapper;

    public MybatisCustomerTagRepository(CustomerTagMapper customerTagMapper) {
        this.customerTagMapper = customerTagMapper;
    }

    @Override
    public List<String> findTagCodes(long tenantId, long customerId) {
        return customerTagMapper.selectTagCodes(tenantId, customerId);
    }

    @Override
    public Map<Long, List<String>> findTagCodesByCustomerIds(long tenantId, List<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        List<Long> uniqueIds = customerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (int start = 0; start < uniqueIds.size(); start += BATCH_SIZE) {
            List<Long> batch = uniqueIds.subList(start, Math.min(start + BATCH_SIZE, uniqueIds.size()));
            for (CustomerTagMapper.CustomerTagRow row : customerTagMapper.selectTagCodesByCustomerIds(tenantId, batch)) {
                grouped.computeIfAbsent(row.customerId(), ignored -> new ArrayList<>()).add(row.tagCode());
            }
        }
        grouped.replaceAll((ignored, tags) -> List.copyOf(tags));
        return Map.copyOf(grouped);
    }

    @Override
    public void insert(long tenantId, long customerId, String tagCode, Long createdBy) {
        customerTagMapper.insertTag(tenantId, customerId, tagCode, createdBy);
    }

    @Override
    public int insertIfAbsent(long tenantId, long customerId, String tagCode, Long createdBy) {
        return customerTagMapper.insertTagIfAbsent(tenantId, customerId, tagCode, createdBy);
    }

    @Override
    public int delete(long tenantId, long customerId, String tagCode) {
        return customerTagMapper.deleteTag(tenantId, customerId, tagCode);
    }

    @Override
    public int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId) {
        return customerTagMapper.reassignCustomer(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int deleteDuplicatesOf(long tenantId, long fromCustomerId, long toCustomerId) {
        return customerTagMapper.deleteDuplicatesOf(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int reassignSpecificCodes(long tenantId, List<String> tagCodes, long fromCustomerId, long toCustomerId) {
        return customerTagMapper.reassignSpecificCodes(tenantId, tagCodes, fromCustomerId, toCustomerId);
    }
}
