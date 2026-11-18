package com.whale.crm.integration.wecom.media.application;

import com.whale.crm.integration.wecom.media.domain.WecomMediaAsset;
import com.whale.crm.integration.wecom.media.domain.WecomMediaReference;
import com.whale.crm.integration.wecom.media.domain.WecomMediaRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WecomMediaRegistrationService {
    private final WecomMediaRepository repository;
    private final QuickReplyTextMasker masker;
    private final AuditService auditService;

    public WecomMediaRegistrationService(WecomMediaRepository repository, QuickReplyTextMasker masker,
                                         AuditService auditService) {
        this.repository = repository;
        this.masker = masker;
        this.auditService = auditService;
    }

    @Transactional
    public void register(long tenantId, long messageId, List<WecomMediaReference> references) {
        List<WecomMediaAsset> existing = repository.findByMessage(tenantId, messageId);
        for (WecomMediaReference reference : references) {
            if (existing.stream().anyMatch(item -> item.assetIndex() == reference.assetIndex())) {
                continue;
            }
            WecomMediaAsset input = new WecomMediaAsset(null, tenantId, messageId, reference.assetIndex(),
                    reference.sdkFileId(), reference.mediaKind(), reference.md5sum(), reference.fileSize(),
                    masker.mask(reference.fileName(), 255), reference.contentType(), "PENDING", null,
                    null, null, null, 0, null, null, null, null, null, 0);
            repository.insert(input);
        }
        if (!references.isEmpty()) {
            auditService.record(tenantId, null, "WECOM_MEDIA_REGISTER", "WECOM_MESSAGE", Long.toString(messageId),
                    null, java.util.Map.of("mediaCount", references.size(), "status", "PENDING"), null);
        }
    }

    @Transactional(readOnly = true)
    public List<WecomMediaAsset> listByMessage(long tenantId, long messageId) {
        return repository.findByMessage(tenantId, messageId);
    }
}
