package com.whale.crm.platform.idempotency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdempotencyMapper {

    IdempotencyRecord selectByKey(@Param("tenantId") long tenantId, @Param("scopeCode") String scopeCode,
                                  @Param("idempotencyKey") String idempotencyKey);

    int insertRecord(IdempotencyRecord record);
}
