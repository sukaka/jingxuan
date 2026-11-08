package com.whale.crm.exporting.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.exporting.api.dto.CreateExportRequest;
import com.whale.crm.exporting.api.dto.ExportAccessLogResponse;
import com.whale.crm.exporting.api.dto.ExportDownloadResponse;
import com.whale.crm.exporting.api.dto.ExportFilterRequest;
import com.whale.crm.exporting.api.dto.ExportJobResponse;
import com.whale.crm.exporting.domain.ExportAccessLog;
import com.whale.crm.exporting.domain.ExportColumn;
import com.whale.crm.exporting.domain.ExportFilter;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import com.whale.crm.exporting.domain.ExportRestriction;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;
import com.whale.crm.exporting.infrastructure.ExportFileService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ExportApplicationService {
    private final ExportJobRepository repository;
    private final ExportColumnPolicy columnPolicy;
    private final ExportScopeService scopeService;
    private final ExportFileService fileService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ExportApplicationService(ExportJobRepository repository, ExportColumnPolicy columnPolicy,
                                    ExportScopeService scopeService, ExportFileService fileService,
                                    ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.columnPolicy = columnPolicy;
        this.scopeService = scopeService;
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ExportJob create(CurrentUser user, CreateExportRequest request, String idempotencyKey) {
        Prepared prepared;
        try {
            prepared = prepare(user, request, idempotencyKey);
        } catch (BusinessException ex) {
            deny(user, null, "REQUEST", safeReason(request), denialCode(ex, "EXPORT_REQUEST_INVALID"));
            throw ex;
        }

        ExportJob existing = repository.findByIdempotency(user.tenantId(), user.userId(), prepared.key()).orElse(null);
        if (existing != null) {
            return idempotentOrConflict(user, existing, prepared);
        }
        try {
            return transactions.execute(status -> {
                ExportJob concurrent = repository.findByIdempotency(user.tenantId(), user.userId(), prepared.key())
                        .orElse(null);
                if (concurrent != null) {
                    if (!prepared.hash().equals(concurrent.getRequestHash())) {
                        throw new ExportAccessException(ErrorCode.CONFLICT, "幂等键已用于不同导出请求", "IDEMPOTENCY_CONFLICT");
                    }
                    access(user, concurrent.getId(), "REQUEST", "ALLOWED", concurrent.getReason(), null);
                    return concurrent;
                }
                ExportJob job = newJob(user, prepared);
                repository.insert(job);
                access(user, job.getId(), "REQUEST", "ALLOWED", job.getReason(), null);
                return job;
            });
        } catch (DuplicateKeyException ex) {
            ExportJob concurrent = repository.findByIdempotency(user.tenantId(), user.userId(), prepared.key())
                    .orElseThrow(() -> ex);
            return idempotentOrConflict(user, concurrent, prepared);
        } catch (BusinessException ex) {
            deny(user, null, "REQUEST", prepared.reason(), denialCode(ex, "EXPORT_CREATE_FAILED"));
            throw ex;
        }
    }

    public ExportJob get(CurrentUser user, long id) {
        requirePermission(user, "export:read", ErrorCode.FORBIDDEN, "没有查看导出任务的权限", "EXPORT_READ_FORBIDDEN");
        return repository.findVisibleById(user.tenantId(), id, scopeService.visibilityScope(user), user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "导出任务不存在或不可见"));
    }

    public PageResponse<ExportJob> search(CurrentUser user, ExportType type, ExportStatus status, int page, int size) {
        requirePermission(user, "export:read", ErrorCode.FORBIDDEN, "没有查看导出任务的权限", "EXPORT_READ_FORBIDDEN");
        int offset = offset(page, size);
        String visibility = scopeService.visibilityScope(user);
        return new PageResponse<>(repository.search(user.tenantId(), type, status, visibility, user.userId(), offset, size),
                repository.countSearch(user.tenantId(), type, status, visibility, user.userId()), page, size);
    }

    public ExportDownloadResponse download(CurrentUser user, long id) {
        ExportJob job = null;
        try {
            requirePermission(user, "export:read", ErrorCode.FORBIDDEN, "没有下载导出文件的权限", "EXPORT_READ_FORBIDDEN");
            job = repository.findById(user.tenantId(), id)
                    .orElseThrow(() -> new ExportAccessException(ErrorCode.NOT_FOUND, "导出任务不存在或不可见", "EXPORT_JOB_NOT_FOUND"));
            if (job.getRequestedBy() != user.userId()) {
                throw new ExportAccessException(ErrorCode.NOT_FOUND, "导出任务不存在或不可见", "EXPORT_JOB_FORBIDDEN");
            }
            requirePermission(user, job.getExportType().readPermission(), ErrorCode.FORBIDDEN,
                    "没有该数据集的读取权限", "DATASET_READ_FORBIDDEN");
            if (job.getStatus() != ExportStatus.SUCCEEDED || job.getFileId() == null) {
                throw new ExportAccessException(ErrorCode.CONFLICT, "导出文件尚未生成", "EXPORT_NOT_READY");
            }
            Instant now = Instant.now();
            if (job.getExpiresAt() == null || !job.getExpiresAt().isAfter(now)) {
                throw new ExportAccessException(ErrorCode.NOT_FOUND, "导出文件已过期", "EXPORT_EXPIRED");
            }
            StoredExportScope stored = read(job.getScopeJson(), StoredExportScope.class);
            if (stored.executed() == null) {
                throw new ExportAccessException(ErrorCode.CONFLICT, "导出文件缺少执行范围", "EXPORT_SCOPE_INVALID");
            }
            ExportScope current = scopeService.executionScope(user, job.getExportType(), stored);
            if (!current.equals(stored.executed())) {
                throw new ExportAccessException(ErrorCode.FORBIDDEN, "当前数据范围已变化，旧导出文件不可下载", "EXPORT_SCOPE_CHANGED");
            }
            ExportFileService.Download download = fileService.download(job);
            access(user, job.getId(), "DOWNLOAD", "ALLOWED", job.getReason(), null);
            return new ExportDownloadResponse(job.getFileId(), download.url(), download.expiresAt());
        } catch (BusinessException ex) {
            deny(user, job == null ? null : job.getId(), "DOWNLOAD",
                    job == null ? "下载导出文件" : job.getReason(), denialCode(ex, "EXPORT_FILE_UNAVAILABLE"));
            throw ex;
        }
    }

    public PageResponse<ExportAccessLogResponse> accessLogs(CurrentUser user, Long jobId, Long actorId,
                                                             String action, String result, int page, int size) {
        requirePermission(user, "export:read", ErrorCode.FORBIDDEN, "没有查看导出审计的权限", "EXPORT_READ_FORBIDDEN");
        int offset = offset(page, size);
        String visibility = scopeService.visibilityScope(user);
        return new PageResponse<>(repository.searchAccessLogs(user.tenantId(), jobId, actorId, action, result,
                        visibility, user.userId(), offset, size).stream().map(ExportAccessLogResponse::from).toList(),
                repository.countAccessLogs(user.tenantId(), jobId, actorId, action, result, visibility, user.userId()),
                page, size);
    }

    public ExportJobResponse response(ExportJob job) {
        return new ExportJobResponse(job.getId(), job.getJobNo(), job.getExportType(), job.getStatus(),
                read(job.getColumnsJson(), new TypeReference<>() { }),
                read(job.getFiltersJson(), ExportFilter.class), read(job.getScopeJson(), StoredExportScope.class),
                job.getReason(), job.getRowCount(), job.getFileId(), job.getExpiresAt(), job.getLastError(),
                job.getStartedAt(), job.getFinishedAt(), job.getCreatedAt(), job.getUpdatedAt(), job.getVersion());
    }

    private Prepared prepare(CurrentUser user, CreateExportRequest request, String key) {
        if (request == null || request.exportType() == null) {
            throw new ExportAccessException(ErrorCode.VALIDATION_FAILED, "导出类型不能为空", "EXPORT_REQUEST_INVALID");
        }
        requirePermission(user, "export:create", ErrorCode.FORBIDDEN, "没有创建导出任务的权限", "EXPORT_CREATE_FORBIDDEN");
        requirePermission(user, request.exportType().readPermission(), ErrorCode.FORBIDDEN,
                "没有该数据集的读取权限", "DATASET_READ_FORBIDDEN");
        String normalizedKey = normalizeKey(key);
        String reason = normalizeReason(request.reason());
        List<ExportColumn> columns = columnPolicy.validate(request.exportType(), request.columns());
        ExportFilter filter = validateFilter(request.exportType(), request.filters());
        StoredExportScope scope = scopeService.requestScope(user, request.exportType(), request.scope());
        List<String> codes = columnPolicy.codes(columns);
        String hash = hash(new CanonicalRequest(request.exportType(), codes, reason, filter, scope.restriction()));
        return new Prepared(request.exportType(), codes, filter, scope, reason, normalizedKey, hash);
    }

    private ExportJob idempotentOrConflict(CurrentUser user, ExportJob existing, Prepared prepared) {
        if (!prepared.hash().equals(existing.getRequestHash())) {
            ExportAccessException ex = new ExportAccessException(ErrorCode.CONFLICT,
                    "幂等键已用于不同导出请求", "IDEMPOTENCY_CONFLICT");
            deny(user, existing.getId(), "REQUEST", prepared.reason(), ex.denialCode());
            throw ex;
        }
        access(user, existing.getId(), "REQUEST", "ALLOWED", existing.getReason(), null);
        return existing;
    }

    private ExportJob newJob(CurrentUser user, Prepared prepared) {
        Instant now = Instant.now();
        ExportJob job = new ExportJob();
        job.setTenantId(user.tenantId());
        job.setJobNo("EXP" + UUID.randomUUID().toString().replace("-", ""));
        job.setRequestedBy(user.userId());
        job.setExportType(prepared.type());
        job.setStatus(ExportStatus.PENDING);
        job.setFiltersJson(write(prepared.filter()));
        job.setColumnsJson(write(prepared.columns()));
        job.setScopeJson(write(prepared.scope()));
        job.setReason(prepared.reason());
        job.setRowCount(0L);
        job.setIdempotencyKey(prepared.key());
        job.setRequestHash(prepared.hash());
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setVersion(0L);
        return job;
    }

    private ExportFilter validateFilter(ExportType type, ExportFilterRequest request) {
        if (request == null) {
            return ExportFilter.empty();
        }
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导出开始时间不能晚于结束时间");
        }
        if (request.status() != null && type == ExportType.FOLLOWUP) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "跟进导出不支持状态筛选");
        }
        if ((request.cityCode() != null || request.sourceCode() != null) && type != ExportType.CUSTOMER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "城市和客户来源仅用于客户导出");
        }
        if (request.sourceSystem() != null && type != ExportType.ORDER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "订单来源仅用于订单导出");
        }
        if (request.channelCode() != null && type != ExportType.CUSTOMER && type != ExportType.FOLLOWUP) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "渠道筛选仅用于客户或跟进导出");
        }
        return new ExportFilter(request.customerId(), text(request.status()), text(request.cityCode()),
                text(request.sourceCode()), text(request.sourceSystem()), text(request.channelCode()), request.from(), request.to());
    }

    private void requirePermission(CurrentUser user, String permission, ErrorCode errorCode,
                                   String message, String denialCode) {
        if (!user.permissions().contains(permission)) {
            throw new ExportAccessException(errorCode, message, denialCode);
        }
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 128) {
            throw new ExportAccessException(ErrorCode.VALIDATION_FAILED, "幂等键必须为1至128字符", "EXPORT_REQUEST_INVALID");
        }
        return value.strip();
    }

    private String normalizeReason(String value) {
        if (value == null || value.strip().length() < 2 || value.strip().length() > 500) {
            throw new ExportAccessException(ErrorCode.VALIDATION_FAILED, "导出理由必须为2至500字符", "EXPORT_REASON_INVALID");
        }
        return value.strip();
    }

    private int offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分页参数无效");
        }
        return Math.toIntExact((long) (page - 1) * size);
    }

    private String denialCode(BusinessException ex, String fallback) {
        return ex instanceof ExportAccessException access ? access.denialCode() : fallback;
    }

    private void deny(CurrentUser user, Long jobId, String action, String reason, String code) {
        access(user, jobId, action, "DENIED", safeText(reason, "导出请求未提供有效理由"), code);
    }

    private void access(CurrentUser user, Long jobId, String action, String result, String reason, String code) {
        ExportAccessLog log = new ExportAccessLog();
        log.setTenantId(user.tenantId());
        log.setJobId(jobId);
        log.setActorUserId(user.userId());
        log.setAction(action);
        log.setResult(result);
        log.setReason(safeText(reason, "导出请求未提供有效理由"));
        log.setDenialCode(code);
        log.setCreatedAt(Instant.now());
        repository.insertAccessLog(log);
    }

    private String safeReason(CreateExportRequest request) {
        return request == null ? "导出请求未提供有效理由" : safeText(request.reason(), "导出请求未提供有效理由");
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String stripped = value.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String hash(CanonicalRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required digest algorithm unavailable", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("导出请求序列化失败", ex);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("导出任务元数据无效", ex);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("导出任务元数据无效", ex);
        }
    }

    private record CanonicalRequest(ExportType type, List<String> columns, String reason,
                                    ExportFilter filter, ExportRestriction restriction) {
    }

    private record Prepared(ExportType type, List<String> columns, ExportFilter filter,
                            StoredExportScope scope, String reason, String key, String hash) {
    }
}
