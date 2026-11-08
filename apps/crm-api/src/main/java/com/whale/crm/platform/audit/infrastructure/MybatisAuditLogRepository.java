package com.whale.crm.platform.audit.infrastructure;

import com.whale.crm.platform.audit.domain.AuditLog;
import com.whale.crm.platform.audit.domain.AuditLogRepository;
import com.whale.crm.platform.audit.infrastructure.mapper.AuditLogMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisAuditLogRepository implements AuditLogRepository {

    private final AuditLogMapper auditLogMapper;

    public MybatisAuditLogRepository(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void insert(AuditLog log) {
        auditLogMapper.insertLog(log);
    }

    @Override
    public List<AuditLogRow> search(long tenantId, Long actorUserId, String actionCode, String targetType,
                                    int offset, int size) {
        return auditLogMapper.search(tenantId, actorUserId, actionCode, targetType, offset, size);
    }

    @Override
    public long count(long tenantId, Long actorUserId, String actionCode, String targetType) {
        return auditLogMapper.countSearch(tenantId, actorUserId, actionCode, targetType);
    }
}
