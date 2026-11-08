package com.whale.crm.platform.idempotency;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 创建类操作的幂等守卫（crm_idempotency_record）。
 * 用法：先 {@link #lookup}，命中且请求哈希一致 → 调用方返回首次资源；哈希不一致 → 409。
 * 未命中则业务执行后 {@link #recordSuccess} 落记录（与业务同事务）；并发同键撞唯一键 → 409。
 */
@Service
public class IdempotencyService {

    private final IdempotencyMapper idempotencyMapper;

    public IdempotencyService(IdempotencyMapper idempotencyMapper) {
        this.idempotencyMapper = idempotencyMapper;
    }

    public Optional<IdempotencyRecord> lookup(long tenantId, String scopeCode, String idempotencyKey,
                                              String requestPayload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        IdempotencyRecord record = idempotencyMapper.selectByKey(tenantId, scopeCode, idempotencyKey);
        if (record == null) {
            return Optional.empty();
        }
        if (record.getRequestHash() != null && !record.getRequestHash().equals(sha256(requestPayload))) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已用于不同请求体，禁止重放");
        }
        return Optional.of(record);
    }

    public void recordSuccess(long tenantId, String scopeCode, String idempotencyKey,
                              String resourceType, String resourceId, String requestPayload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setTenantId(tenantId);
        record.setScopeCode(scopeCode);
        record.setIdempotencyKey(idempotencyKey);
        record.setResourceType(resourceType);
        record.setResourceId(resourceId);
        record.setStatus(IdempotencyRecord.STATUS_SUCCEEDED);
        record.setRequestHash(sha256(requestPayload));
        try {
            idempotencyMapper.insertRecord(record);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "相同幂等键的请求正在处理，请查询结果后重试");
        }
    }

    public static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("请求哈希计算失败", ex);
        }
    }
}
