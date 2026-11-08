package com.whale.crm.exporting.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.exporting.domain.ExportAccessLog;
import com.whale.crm.exporting.domain.ExportColumn;
import com.whale.crm.exporting.domain.ExportDataQuery;
import com.whale.crm.exporting.domain.ExportDataRepository;
import com.whale.crm.exporting.domain.ExportFilter;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;
import com.whale.crm.exporting.infrastructure.ExportFileService;
import com.whale.crm.exporting.infrastructure.ExportProperties;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExportExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ExportExecutionService.class);

    private final ExportJobRepository repository;
    private final ExportDataRepository dataRepository;
    private final ExportScopeService scopeService;
    private final ExportColumnPolicy columnPolicy;
    private final CsvEncoder csvEncoder;
    private final ExportFileService fileService;
    private final CurrentUserService currentUserService;
    private final MobileSupport mobileSupport;
    private final ObjectMapper objectMapper;
    private final ExportProperties properties;
    private final TransactionTemplate transactions;
    private final long leaseSeconds;

    public ExportExecutionService(ExportJobRepository repository, ExportDataRepository dataRepository,
                                  ExportScopeService scopeService, ExportColumnPolicy columnPolicy,
                                  CsvEncoder csvEncoder, ExportFileService fileService,
                                  CurrentUserService currentUserService, MobileSupport mobileSupport,
                                  ObjectMapper objectMapper, ExportProperties properties,
                                  PlatformTransactionManager transactionManager,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${app.exports.worker.lease-seconds:60}") long leaseSeconds) {
        this.repository = repository;
        this.dataRepository = dataRepository;
        this.scopeService = scopeService;
        this.columnPolicy = columnPolicy;
        this.csvEncoder = csvEncoder;
        this.fileService = fileService;
        this.currentUserService = currentUserService;
        this.mobileSupport = mobileSupport;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.leaseSeconds = leaseSeconds;
    }

    public void execute(long tenantId, long jobId, String owner) {
        ExportJob job = repository.findById(tenantId, jobId).orElse(null);
        if (job == null || job.getStatus() != ExportStatus.RUNNING || !owner.equals(job.getLeaseOwner())) {
            return;
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("crm-export-", ".csv");
            executeClaimed(job, owner, temporary);
        } catch (Exception ex) {
            String code = errorCode(ex);
            log.warn("Export job failed tenantId={} jobId={} code={}", tenantId, jobId, code);
            markFailed(job, owner, code);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void executeClaimed(ExportJob job, String owner, Path temporary) throws Exception {
        CurrentUser user = currentUserService.loadById(job.getRequestedBy())
                .filter(candidate -> candidate.tenantId() == job.getTenantId())
                .orElseThrow(() -> new ExportAccessException(ErrorCode.FORBIDDEN,
                        "导出申请人不存在或已停用", "REQUESTER_UNAVAILABLE"));
        requirePermission(user, "export:create", "EXPORT_CREATE_FORBIDDEN");
        requirePermission(user, job.getExportType().readPermission(), "DATASET_READ_FORBIDDEN");
        StoredExportScope stored = read(job.getScopeJson(), StoredExportScope.class);
        ExportFilter filter = read(job.getFiltersJson(), ExportFilter.class);
        List<String> codes = read(job.getColumnsJson(), new TypeReference<>() { });
        List<ExportColumn> columns = columnPolicy.validate(job.getExportType(), codes);
        ExportScope executed = scopeService.executionScope(user, job.getExportType(), stored);
        if (properties.getMaxRows() <= 0 || properties.getPageSize() <= 0 || properties.getFileExpirySeconds() <= 0) {
            throw new ExportExecutionException("EXPORT_CONFIGURATION_INVALID");
        }

        ExportDataQuery countQuery = query(job, executed, stored, filter, 0, 1);
        long expectedRows = dataRepository.count(countQuery);
        if (expectedRows > properties.getMaxRows()) {
            throw new ExportExecutionException("ROW_LIMIT_EXCEEDED");
        }
        long rowCount = writeCsv(job, owner, temporary, columns, executed, stored, filter, expectedRows);
        renew(job, owner);
        Instant expiresAt = Instant.now().plusSeconds(properties.getFileExpirySeconds());
        FileMetadata file = fileService.registerCleanFile(job, temporary, expiresAt);
        StoredExportScope completed = stored.withExecuted(executed);
        int marked = repository.markSucceeded(job.getTenantId(), job.getId(), job.getVersion(), owner,
                file.id(), rowCount, file.expiresAt(), write(completed), Instant.now());
        if (marked != 1) {
            throw new ExportExecutionException("EXPORT_LEASE_LOST");
        }
    }

    private long writeCsv(ExportJob job, String owner, Path temporary, List<ExportColumn> columns,
                          ExportScope executed, StoredExportScope stored, ExportFilter filter,
                          long expectedRows) throws IOException {
        long rowCount = 0;
        int offset = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            csvEncoder.writeHeader(writer, columns);
            while (offset < expectedRows) {
                renew(job, owner);
                int pageSize = (int) Math.min(properties.getPageSize(), properties.getMaxRows() - rowCount);
                if (pageSize <= 0) {
                    throw new ExportExecutionException("ROW_LIMIT_EXCEEDED");
                }
                List<Map<String, Object>> rows = dataRepository.page(query(job, executed, stored, filter, offset, pageSize));
                if (rows.isEmpty()) {
                    break;
                }
                for (Map<String, Object> row : rows) {
                    csvEncoder.writeRow(writer, columns, safeRow(job.getExportType(), row));
                    rowCount++;
                    if (rowCount > properties.getMaxRows()) {
                        throw new ExportExecutionException("ROW_LIMIT_EXCEEDED");
                    }
                }
                offset += rows.size();
                if (rows.size() < pageSize) {
                    break;
                }
            }
        }
        return rowCount;
    }

    private Map<String, Object> safeRow(ExportType type, Map<String, Object> row) {
        Map<String, Object> safe = new HashMap<>(row);
        safe.remove("rowId");
        if (type == ExportType.CUSTOMER) {
            Object cipher = safe.remove("mobileCipher");
            safe.put("mobile", cipher == null ? null : mobileSupport.maskFromCipher(cipher.toString()));
        }
        return safe;
    }

    private ExportDataQuery query(ExportJob job, ExportScope executed, StoredExportScope stored,
                                  ExportFilter filter, int offset, int size) {
        return new ExportDataQuery(job.getTenantId(), job.getExportType(), executed,
                stored.restriction(), filter, offset, size);
    }

    private void renew(ExportJob job, String owner) {
        Instant until = Instant.now().plusSeconds(leaseSeconds);
        if (repository.renewLease(job.getTenantId(), job.getId(), owner, until) != 1) {
            throw new ExportExecutionException("EXPORT_LEASE_LOST");
        }
    }

    private void requirePermission(CurrentUser user, String permission, String code) {
        if (!user.permissions().contains(permission)) {
            throw new ExportAccessException(ErrorCode.FORBIDDEN, "导出权限已被回收", code);
        }
    }

    private void markFailed(ExportJob job, String owner, String code) {
        try {
            transactions.execute(status -> {
                int marked = repository.markFailed(job.getTenantId(), job.getId(), job.getVersion(), owner,
                        code, Instant.now());
                if (marked == 1) {
                    ExportAccessLog audit = new ExportAccessLog();
                    audit.setTenantId(job.getTenantId());
                    audit.setJobId(job.getId());
                    audit.setActorUserId(job.getRequestedBy());
                    audit.setAction("REQUEST");
                    audit.setResult("DENIED");
                    audit.setReason(job.getReason());
                    audit.setDenialCode(code);
                    audit.setCreatedAt(Instant.now());
                    repository.insertAccessLog(audit);
                }
                return null;
            });
        } catch (RuntimeException ex) {
            log.warn("Export failure state could not be persisted tenantId={} jobId={}", job.getTenantId(), job.getId());
        }
    }

    private String errorCode(Exception ex) {
        if (ex instanceof ExportAccessException access) {
            return access.denialCode();
        }
        if (ex instanceof ExportExecutionException execution) {
            return execution.code();
        }
        if (ex instanceof FileContentInspector.FileContentException) {
            return "FILE_CONTENT_INVALID";
        }
        if (ex instanceof BusinessException business && business.errorCode() == ErrorCode.FORBIDDEN) {
            return "EXPORT_SCOPE_FORBIDDEN";
        }
        if (ex instanceof BusinessException) {
            return "EXPORT_FILE_REJECTED";
        }
        return "EXPORT_EXECUTION_FAILED";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("导出范围序列化失败", ex);
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

    private void deleteTemporary(Path temporary) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ex) {
                log.warn("Export temporary file cleanup failed");
            }
        }
    }

    private static final class ExportExecutionException extends RuntimeException {
        private final String code;

        private ExportExecutionException(String code) {
            super(code);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
