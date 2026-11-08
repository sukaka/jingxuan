package com.whale.crm.platform.user.infrastructure.mapper;

import com.whale.crm.platform.user.domain.DataScopeRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DataScopeMapper {

    List<DataScopeRow> selectActiveScopes(@Param("tenantId") long tenantId, @Param("userId") long userId,
                                          @Param("resource") String resource, @Param("now") Instant now);
}
