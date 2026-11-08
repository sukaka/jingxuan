package com.whale.crm.platform.audit.infrastructure.mapper;

import com.whale.crm.platform.audit.domain.AuditLog;
import com.whale.crm.platform.audit.domain.AuditLogRepository.AuditLogRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuditLogMapper {

    int insertLog(AuditLog log);

    List<AuditLogRow> search(@Param("tenantId") long tenantId, @Param("actorUserId") Long actorUserId,
                             @Param("actionCode") String actionCode, @Param("targetType") String targetType,
                             @Param("offset") int offset, @Param("size") int size);

    long countSearch(@Param("tenantId") long tenantId, @Param("actorUserId") Long actorUserId,
                     @Param("actionCode") String actionCode, @Param("targetType") String targetType);
}
