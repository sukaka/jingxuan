package com.whale.crm.integration.wecom.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.WecomAdapter;
import com.whale.crm.integration.wecom.adapter.WecomAdapterRouter;
import com.whale.crm.integration.wecom.adapter.WecomArchiveMessage;
import com.whale.crm.integration.wecom.adapter.WecomArchivePage;
import com.whale.crm.integration.wecom.adapter.WecomContactInput;
import com.whale.crm.integration.wecom.api.dto.WecomSyncRequest;
import com.whale.crm.integration.wecom.api.dto.WecomSyncResponse;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomCursor;
import com.whale.crm.integration.wecom.domain.WecomDataGap;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WecomArchiveService {
    private static final String ARCHIVE_SCOPE = "MOCK_ARCHIVE";
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private final WecomRepository repository;
    private final WecomAdapterRouter adapterRouter;
    private final AesGcmCipher cipher;
    private final ObjectProvider<PrivateObjectStorage> objectStorage;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public WecomArchiveService(WecomRepository repository, WecomAdapterRouter adapterRouter, AesGcmCipher cipher,
                               ObjectProvider<PrivateObjectStorage> objectStorage, OutboxService outboxService,
                               AuditService auditService, ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.adapterRouter = adapterRouter;
        this.cipher = cipher;
        this.objectStorage = objectStorage;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WecomSyncResponse sync(CurrentUser user, WecomSyncRequest request) {
        List<String> newObjects = new ArrayList<>();
        WecomSyncResponse response;
        try {
            response = transactions.execute(status -> doSync(user.tenantId(), request, newObjects));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            cleanup(newObjects);
            recordFailure(user, request.corpId());
            throw new BusinessException(ErrorCode.CONFLICT, "企微归档失败，成功游标未推进，请检查缺口后重试");
        }
        if (response == null) {
            throw new IllegalStateException("归档事务没有返回结果");
        }
        auditService.record(user.tenantId(), user.userId(), "WECOM_ARCHIVE_SYNC", "WECOM_CURSOR",
                request.corpId(), null, response, null);
        return response;
    }

    private WecomSyncResponse doSync(long tenantId, WecomSyncRequest request, List<String> newObjects) {
        WecomCursor cursor = lockCursor(tenantId, request.corpId());
        long afterSeq = request.cursor() == null ? cursor.lastSeq() : request.cursor();
        if (afterSeq > cursor.lastSeq()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "回放游标不能超过已确认的成功位置");
        }
        WecomAdapter adapter = adapterRouter.adapter(tenantId);
        syncContacts(tenantId, request.corpId(), adapter.pullContacts(request.corpId()));
        WecomArchivePage page = adapter.pullArchive(request.corpId(), afterSeq,
                request.limit() == null ? 50 : request.limit());
        int stored = 0;
        int duplicates = 0;
        int skipped = 0;
        long previousSeq = afterSeq;
        for (WecomArchiveMessage source : page.messages()) {
            if (source.seq() <= previousSeq) {
                throw new IllegalStateException("归档序号必须严格递增");
            }
            previousSeq = source.seq();
            if (repository.findMessage(tenantId, request.corpId(), source.msgId()).isPresent()) {
                duplicates++;
                continue;
            }
            WecomContact contact = repository.findContact(tenantId, request.corpId(),
                    source.externalUserid(), source.staffUserId()).orElse(null);
            if (contact == null || !"GRANTED".equals(contact.consentStatus())) {
                recordGap(tenantId, request.corpId(), "CONSENT_NOT_GRANTED", source.seq(),
                        "同意未确认或已撤回，未归档消息内容");
                skipped++;
                continue;
            }
            if (!repository.archiveMemberEnabled(tenantId, request.corpId(), source.staffUserId())) {
                recordGap(tenantId, request.corpId(), "MEMBER_NOT_COVERED", source.seq(),
                        "成员不在归档覆盖范围，未归档消息内容");
                skipped++;
                continue;
            }
            archiveMessage(tenantId, request.corpId(), contact, source, newObjects);
            stored++;
        }
        long committedSeq = Math.max(cursor.lastSeq(), page.nextSeq());
        repository.resolveCollectorGaps(tenantId, request.corpId(), ARCHIVE_SCOPE, Instant.now());
        List<WecomDataGap> gaps = repository.findOpenGaps(tenantId, request.corpId(), ARCHIVE_SCOPE);
        boolean dataGap = !gaps.isEmpty() || committedSeq == 0;
        String gapReason = !gaps.isEmpty() ? gaps.get(gaps.size() - 1).detailMasked()
                : committedSeq == 0 ? "尚无已确认覆盖区间，无法判断沟通情况" : null;
        updateCursor(tenantId, cursor, committedSeq, Instant.now(), Instant.now(), "SUCCESS", 0, null,
                dataGap, gapReason);
        return new WecomSyncResponse(adapter.name(), page.messages().size(), stored, duplicates, skipped,
                committedSeq, page.hasMore(), dataGap);
    }

    private void archiveMessage(long tenantId, String corpId, WecomContact contact,
                                WecomArchiveMessage source, List<String> newObjects) {
        try {
            String objectKey = "wecom/" + tenantId + "/" + corpId + "/" + UUID.randomUUID() + ".json.enc";
            String raw = objectMapper.writeValueAsString(Map.of("messageType", source.messageType(), "text", source.text()));
            byte[] encrypted = cipher.encrypt(raw).getBytes(StandardCharsets.UTF_8);
            storage().putObject(objectKey, encrypted, "application/octet-stream");
            newObjects.add(objectKey);
            String status = contact.customerId() == null ? "UNMATCHED" : "STORED";
            WecomMessage message = new WecomMessage(null, corpId, source.msgId(), source.seq(), contact.customerId(),
                    source.externalUserid(), source.staffUserId(), source.conversationId(), source.messageType(),
                    source.senderType(), source.sentAt(), objectKey, sha256(encrypted), contact.consentStatus(),
                    contact.consentEvidenceRef(), status, null, null);
            repository.insertMessage(tenantId, message);
            WecomMessage persisted = repository.findMessage(tenantId, corpId, source.msgId()).orElseThrow();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageRef", persisted.id());
            payload.put("corpId", corpId);
            payload.put("msgId", source.msgId());
            payload.put("archiveSeq", source.seq());
            payload.put("conversationId", source.conversationId());
            payload.put("customerId", contact.customerId());
            payload.put("contentObjectKey", objectKey);
            payload.put("sentAt", source.sentAt().toString());
            outboxService.append(tenantId, "wecom.message.archived.v1:" + corpId + ":" + source.msgId(),
                    "wecom.message.archived.v1", "wecom", persisted.id().toString(), payload);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("消息加密归档失败", ex);
        }
    }

    private void syncContacts(long tenantId, String corpId, List<WecomContactInput> contacts) {
        Instant now = Instant.now();
        for (WecomContactInput input : contacts) {
            if (!List.of("PENDING", "GRANTED", "REVOKED").contains(input.consentStatus())) {
                throw new IllegalStateException("未知同意状态");
            }
            WecomContact existing = repository.findContact(tenantId, corpId,
                    input.externalUserid(), input.staffUserId()).orElse(null);
            if (existing == null) {
                repository.insertContact(tenantId, new WecomContact(null, corpId,
                        input.externalUserid(), input.staffUserId(), null, null, null, null, null,
                        null, null, null, null, null, null, "ACTIVE", null, input.consentStatus(),
                        input.consentEvidenceRef(), null, null, null, now, 0L));
            } else if (repository.updateContactConsent(tenantId, existing.id(), existing.version(),
                    input.consentStatus(), input.consentEvidenceRef(), now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "联系身份已变化，请重试同步");
            }
        }
    }

    private WecomCursor lockCursor(long tenantId, String corpId) {
        return repository.findCursorForUpdate(tenantId, corpId, ARCHIVE_SCOPE).orElseGet(() -> {
            repository.insertCursor(tenantId, new WecomCursor(null, corpId, ARCHIVE_SCOPE, 0L, null, null,
                    "NEVER_SYNCED", 0, null, true, "尚未采集，无法判断沟通情况", 0L));
            return repository.findCursorForUpdate(tenantId, corpId, ARCHIVE_SCOPE).orElseThrow();
        });
    }

    private void updateCursor(long tenantId, WecomCursor cursor, long seq, Instant successAt, Instant attemptedAt,
                              String status, int failures, String error, boolean dataGap, String gapReason) {
        if (repository.updateCursor(tenantId, cursor.id(), cursor.version(), seq, successAt, attemptedAt,
                status, failures, error, dataGap, gapReason) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "归档游标已变化，请重试");
        }
    }

    private void recordGap(long tenantId, String corpId, String type, long seq, String detail) {
        repository.recordGap(tenantId, corpId, ARCHIVE_SCOPE, type, seq, seq, detail, Instant.now());
        outboxService.append(tenantId, "wecom.archive.gap.v1:" + corpId + ":" + type + ":" + seq,
                "wecom.archive.gap.v1", "wecom", corpId,
                Map.of("corpId", corpId, "gapType", type, "fromSeq", seq));
    }

    private void recordFailure(CurrentUser user, String corpId) {
        try {
            transactions.execute(status -> {
                WecomCursor cursor = lockCursor(user.tenantId(), corpId);
                Instant now = Instant.now();
                String detail = "采集失败，成功游标未推进，存在覆盖缺口";
                repository.recordGap(user.tenantId(), corpId, ARCHIVE_SCOPE, "COLLECTOR_ERROR",
                        cursor.lastSeq(), null, detail, now);
                updateCursor(user.tenantId(), cursor, cursor.lastSeq(), cursor.lastSuccessAt(), now,
                        "ERROR", cursor.consecutiveFailures() + 1, detail, true, detail);
                outboxService.append(user.tenantId(), "wecom.archive.gap.v1:" + corpId + ":COLLECTOR_ERROR:" + cursor.lastSeq(),
                        "wecom.archive.gap.v1", "wecom", corpId,
                        Map.of("corpId", corpId, "gapType", "COLLECTOR_ERROR", "fromSeq", cursor.lastSeq()));
                auditService.record(user.tenantId(), user.userId(), "WECOM_ARCHIVE_FAILED", "WECOM_CURSOR",
                        corpId, null, Map.of("lastSeq", cursor.lastSeq(), "dataGap", true), null);
                return null;
            });
        } catch (Exception ignored) {
            // 原始同步异常仍返回统一错误；缺口监控会在下次可用时重试，不能伪造已记录。
        }
    }

    public String readContent(WecomMessage message) {
        try (InputStream input = storage().getObject(message.contentObjectKey())) {
            byte[] encrypted = input.readNBytes(MAX_CONTENT_BYTES + 1);
            if (encrypted.length > MAX_CONTENT_BYTES) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "归档内容超过允许读取大小");
            }
            String actualHash = sha256(encrypted);
            if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.UTF_8),
                    message.contentSha256().getBytes(StandardCharsets.UTF_8))) {
                throw new BusinessException(ErrorCode.CONFLICT, "归档对象校验失败");
            }
            String raw = cipher.decrypt(new String(encrypted, StandardCharsets.UTF_8));
            return objectMapper.readTree(raw).path("text").asText();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "归档内容暂时不可读取，请稍后重试");
        }
    }

    private PrivateObjectStorage storage() {
        PrivateObjectStorage storage = objectStorage.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "私有对象存储未配置，不能归档或读取聊天内容");
        }
        return storage;
    }

    private void cleanup(List<String> objectKeys) {
        PrivateObjectStorage storage = objectStorage.getIfAvailable();
        if (storage == null) {
            return;
        }
        for (String key : objectKeys) {
            try {
                storage.deleteObject(key);
            } catch (Exception ignored) {
                // 删除失败的孤立密文不包含可引用元数据，生产清理由对象生命周期策略处理。
            }
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("归档摘要失败", ex);
        }
    }
}
