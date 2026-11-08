package com.whale.crm.platform.user.infrastructure;

import com.whale.crm.platform.user.domain.DataScopeRepository;
import com.whale.crm.platform.user.domain.DataScopeRow;
import com.whale.crm.platform.user.infrastructure.mapper.DataScopeMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class MybatisDataScopeRepository implements DataScopeRepository {

    private final DataScopeMapper dataScopeMapper;

    public MybatisDataScopeRepository(DataScopeMapper dataScopeMapper) {
        this.dataScopeMapper = dataScopeMapper;
    }

    @Override
    public List<DataScopeRow> findActiveScopes(long tenantId, long userId, String resource) {
        return dataScopeMapper.selectActiveScopes(tenantId, userId, resource, Instant.now());
    }
}
