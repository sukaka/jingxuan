package com.whale.crm.integration.wecom.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.api.dto.WecomContentView;
import com.whale.crm.integration.wecom.api.dto.WecomLinkRequest;
import com.whale.crm.integration.wecom.api.dto.WecomMessageView;
import com.whale.crm.integration.wecom.api.dto.WecomMonitorView;
import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomCursor;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

@Service
public class WecomManagementService {
    private final WecomRepository repository;
    private final WecomArchiveService archiveService;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final com.whale.crm.integration.wecom.media.application.WecomMediaRegistrationService mediaService;
    private final com.whale.crm.integration.wecom.adapter.WecomAdapterRouter adapterRouter;
    private final TransactionTemplate transactions;

    public WecomManagementService(WecomRepository repository, WecomArchiveService archiveService,
                                  CustomerScopeSupport scopeSupport, AuditService auditService,
                                  com.whale.crm.integration.wecom.media.application.WecomMediaRegistrationService mediaService,
                                  com.whale.crm.integration.wecom.adapter.WecomAdapterRouter adapterRouter,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.archiveService = archiveService;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.mediaService = mediaService;
        this.adapterRouter = adapterRouter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<WecomMessageView> messages(long tenantId, CurrentUser user, Long customerId,
                                                    String conversationId, String corpId, int page, int size) {
        if (customerId != null) {
            scopeSupport.requireVisible(tenantId, customerId, user);
        }
        String normalizedCorpId = corpId == null || corpId.isBlank() ? null : corpId.strip();
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<WecomMessageView> items = repository.listMessages(tenantId, customerId, conversationId, normalizedCorpId,
                        query.scopeType(), query.teamIds(), query.selfId(), offset, size).stream().map(this::view).toList();
        return new PageResponse<>(items, repository.countMessages(tenantId, customerId, conversationId, normalizedCorpId,
                query.scopeType(), query.teamIds(), query.selfId()), page, size);
    }

    @Transactional(readOnly = true)
    public List<WecomContact> contacts(long tenantId, CurrentUser user) {
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        return repository.listContacts(tenantId, query.scopeType(), query.teamIds(), query.selfId());
    }

    public WecomContact link(long tenantId, CurrentUser user, long contactId, WecomLinkRequest request) {
        WecomContact result = transactions.execute(status -> {
            WecomContact before = repository.findContactById(tenantId, contactId)
                    .orElseThrow(this::notFound);
            if (before.customerId() != null) {
                if (before.customerId().equals(request.customerId()) && before.linkReason().equals(request.reason())) {
                    return before;
                }
                throw new BusinessException(ErrorCode.CONFLICT, "该外部身份已确认客户，不能直接改绑");
            }
            DataScope scope = scopeSupport.scope(user);
            CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
            if (!repository.staffVisible(tenantId, before.staffUserId(), query.scopeType(), query.teamIds(), query.selfId())) {
                throw notFound();
            }
            scopeSupport.requireVisible(tenantId, request.customerId(), user);
            if (repository.linkContact(tenantId, contactId, request.version(), request.customerId(),
                    request.reason(), user.userId()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "联系身份版本已变化，请刷新后重试");
            }
            repository.linkMessages(tenantId, before.corpId(), before.externalUserid(), before.staffUserId(),
                    request.customerId());
            WecomContact after = repository.findContactById(tenantId, contactId).orElseThrow();
            auditService.record(tenantId, user.userId(), "WECOM_CONTACT_LINK", "WECOM_CONTACT",
                    Long.toString(contactId), before, after, request.reason());
            return after;
        });
        if (result == null) {
            throw new IllegalStateException("人工匹配没有返回结果");
        }
        return result;
    }

    @Transactional
    public WecomContentView content(long tenantId, CurrentUser user, long messageId) {
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        WecomMessage message = repository.findReadableMessage(tenantId, messageId, query.scopeType(),
                query.teamIds(), query.selfId()).orElseThrow(this::notFound);
        String content = archiveService.readContent(message);
        auditService.record(tenantId, user.userId(), "WECOM_MESSAGE_CONTENT_READ", "WECOM_MESSAGE",
                Long.toString(messageId), null, Map.of("messageRef", messageId, "corpId", message.corpId()), null);
        return new WecomContentView(message.id(), message.messageType(), message.sentAt(), content,
                mediaService.listByMessage(tenantId, messageId).stream().map(asset ->
                        new com.whale.crm.integration.wecom.media.api.dto.WecomMediaAssetView(asset.id(),
                                asset.mediaKind(), asset.fileName(), asset.contentType(), asset.fileSize(), asset.status(),
                                "STORED".equals(asset.status()))).toList(), message.recalledAt());
    }

    @Transactional(readOnly = true)
    public WecomMonitorView monitor(long tenantId, CurrentUser user, String corpId) {
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        String archiveScope = "OFFICIAL_SDK".equals(adapterRouter.adapter(tenantId).name())
                ? "OFFICIAL_ARCHIVE" : MockWecomAdapter.SCOPE;
        WecomCursor cursor = repository.findCursor(tenantId, corpId, archiveScope).orElse(null);
        long messages = scope.type() == DataScope.ScopeType.ALL ? repository.countAllMessages(tenantId, corpId)
                : repository.countMessages(tenantId, null, null, corpId, query.scopeType(), query.teamIds(), query.selfId());
        long unmatched = scope.type() == DataScope.ScopeType.ALL
                ? contacts(tenantId, user).stream().filter(contact -> contact.customerId() == null).count() : 0;
        long activeGaps = repository.findOpenGaps(tenantId, corpId, archiveScope).size();
        long history = repository.countResolvedGaps(tenantId, corpId, archiveScope);
        return new WecomMonitorView(corpId, archiveScope, cursor == null ? 0 : cursor.lastSeq(),
                cursor == null ? null : cursor.lastSuccessAt(), cursor == null ? null : cursor.lastAttemptAt(),
                cursor == null ? "NEVER_SYNCED" : cursor.status(), cursor == null || cursor.dataGap(),
                cursor == null ? "尚未采集，无法判断沟通情况" : cursor.gapReason(), messages, unmatched,
                activeGaps, history);
    }

    private WecomMessageView view(WecomMessage message) {
        return new WecomMessageView(message.id(), message.corpId(), message.msgId(), message.archiveSeq(),
                message.customerId(), message.externalUserid(), message.staffUserId(), message.conversationId(),
                message.messageType(), message.senderType(), message.sentAt(), message.contentObjectKey(),
                message.consentSnapshot(), message.currentConsentStatus(), message.status(), message.recalledAt(),
                message.recallMessageId(), message.conversationType(), message.senderWecomUserid(),
                message.senderStaffUserId(), message.roomRosterVersion());
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "资源不存在或不在数据范围内");
    }
}
