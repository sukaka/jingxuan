package com.whale.crm.platform.user.infrastructure.mapper;

import com.whale.crm.platform.user.domain.InstitutionUserScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InstitutionUserMapper {

    List<Long> selectActiveInstitutionIds(@Param("tenantId") long tenantId, @Param("userId") long userId);

    List<InstitutionUserScope> selectActiveScopes(@Param("tenantId") long tenantId, @Param("userId") long userId);
}
