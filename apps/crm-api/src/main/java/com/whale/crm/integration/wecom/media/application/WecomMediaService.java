package com.whale.crm.integration.wecom.media.application;

import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.WecomArchiveNativeClient;
import com.whale.crm.integration.wecom.adapter.WecomArchiveOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomMediaChunk;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.media.adapter.WecomMediaProperties;
import com.whale.crm.integration.wecom.media.domain.WecomMediaAsset;
import com.whale.crm.integration.wecom.media.domain.WecomMediaRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Service
public class WecomMediaService {
    private final WecomMediaRepository repository;
    private final WecomArchiveNativeClient nativeClient;
    private final WecomArchiveOfficialProperties archiveProperties;
    private final WecomMediaProperties properties;
    private final ObjectProvider<PrivateObjectStorage> objectStorage;
    private final WecomArchiveService archiveService;
    private final AuditService auditService;
    private final TransactionTemplate transactions;

    public WecomMediaService(WecomMediaRepository repository, WecomArchiveNativeClient nativeClient,
                             WecomArchiveOfficialProperties archiveProperties, WecomMediaProperties properties,
                             ObjectProvider<PrivateObjectStorage> objectStorage, WecomArchiveService archiveService,
                             AuditService auditService, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.nativeClient = nativeClient;
        this.archiveProperties = archiveProperties;
        this.properties = properties;
        this.objectStorage = objectStorage;
        this.archiveService = archiveService;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void download(long tenantId, long id, String owner) {
        WecomMediaAsset asset = repository.findById(tenantId, id).orElseThrow(this::notFound);
        if (!"DOWNLOADING".equals(asset.status()) || !owner.equals(asset.leaseOwner())) {
            throw new BusinessException(ErrorCode.CONFLICT, "媒体下载租约已变化");
        }
        if (asset.tenantId() != archiveProperties.allowedTenantId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "媒体所属租户未获官方采集授权");
        }
        byte[] content = downloadChunks(asset);
        validateContent(asset, content);
        String objectKey = "wecom-media/" + tenantId + "/" + asset.messageId() + "/" + asset.id();
        storage().putObject(objectKey, content, asset.contentType());
        String sha256 = digest("SHA-256", content);
        transactions.executeWithoutResult(status -> {
            if (repository.markStored(tenantId, id, asset.version(), owner, objectKey, sha256, content.length,
                    Instant.now()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "媒体状态已变化，请刷新后重试");
            }
            auditService.record(tenantId, null, "WECOM_MEDIA_STORED", "WECOM_MEDIA_ASSET", Long.toString(id),
                    Map.of("status", asset.status()), Map.of("status", "STORED", "actualSize", content.length), null);
        });
    }

    public void fail(long tenantId, long id, String owner, Exception error) {
        transactions.executeWithoutResult(status -> {
            WecomMediaAsset asset = repository.findByIdForUpdate(tenantId, id).orElse(null);
            if (asset == null || !"DOWNLOADING".equals(asset.status()) || !owner.equals(asset.leaseOwner())) {
                return;
            }
            boolean terminal = asset.attemptCount() >= properties.maxAttempts();
            String target = terminal ? "FAILED" : "RETRY";
            Instant next = terminal ? null : Instant.now().plusMillis(
                    Math.min(60000, 1000L * (1L << Math.min(6, asset.attemptCount()))));
            String masked = "媒体下载失败，请稍后重试";
            if (repository.markFailure(tenantId, id, asset.version(), owner, target, masked, next, Instant.now()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "媒体失败状态已变化");
            }
            auditService.record(tenantId, null, "WECOM_MEDIA_" + target, "WECOM_MEDIA_ASSET", Long.toString(id),
                    Map.of("status", asset.status()), Map.of("status", target, "attemptCount", asset.attemptCount()),
                    masked);
        });
    }

    public WecomMediaContent read(CurrentUser user, long id) {
        WecomMediaAsset asset = repository.findById(user.tenantId(), id).orElseThrow(this::notFound);
        archiveService.requireVisibleMessage(user, asset.messageId());
        if (!"STORED".equals(asset.status()) || asset.objectKey() == null || asset.actualSize() == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "媒体尚未完成下载，请稍后重试");
        }
        String fileName = asset.fileName() == null || asset.fileName().isBlank() ? "media-" + id : asset.fileName();
        auditService.record(user.tenantId(), user.userId(), "WECOM_MEDIA_READ", "WECOM_MEDIA_ASSET",
                Long.toString(id), null, Map.of("messageRef", asset.messageId(), "status", asset.status()), null);
        return new WecomMediaContent(storage().getObject(asset.objectKey()), asset.contentType(), fileName,
                asset.actualSize());
    }

    private byte[] downloadChunks(WecomMediaAsset asset) {
        if (asset.fileSize() != null && asset.fileSize() > properties.maxFileBytes()) {
            throw new IllegalStateException("媒体超过允许大小");
        }
        try (WecomArchiveNativeClient.NativeSession session = nativeClient.open(archiveProperties.corpId(),
                archiveProperties.secret(), archiveProperties.sdkLibraryPath(), archiveProperties.proxy(),
                archiveProperties.proxyPassword(), archiveProperties.timeoutSeconds())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String index = "";
            for (int count = 0; count < properties.maxChunks(); count++) {
                WecomMediaChunk chunk = session.downloadMedia(asset.sdkFileId(), index);
                byte[] data = chunk.data();
                if (output.size() + data.length > properties.maxFileBytes()) {
                    throw new IllegalStateException("媒体超过允许大小");
                }
                output.writeBytes(data);
                if (chunk.finished()) {
                    return output.toByteArray();
                }
                if (chunk.nextIndex() == null || chunk.nextIndex().isBlank() || chunk.nextIndex().equals(index)) {
                    throw new IllegalStateException("媒体分片游标未推进");
                }
                index = chunk.nextIndex();
            }
            throw new IllegalStateException("媒体分片数量超过限制");
        }
    }

    private void validateContent(WecomMediaAsset asset, byte[] content) {
        if (asset.fileSize() != null && asset.fileSize() != content.length) {
            throw new IllegalStateException("媒体实际大小与企微声明不一致");
        }
        if (asset.md5sum() != null && !asset.md5sum().isBlank()
                && !digest("MD5", content).equalsIgnoreCase(asset.md5sum().strip())) {
            throw new IllegalStateException("媒体 MD5 完整性校验失败");
        }
    }

    private String digest(String algorithm, byte[] content) {
        try {
            // MD5 仅用于企微声明值校验，不用于密码、签名或安全认证。
            // nosemgrep: java.lang.security.audit.crypto.weak-hash.md5
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("媒体完整性摘要失败", ex);
        }
    }

    private PrivateObjectStorage storage() {
        PrivateObjectStorage storage = objectStorage.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "私有对象存储未配置，不能处理媒体");
        }
        return storage;
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "媒体不存在或不在数据范围内");
    }
}
