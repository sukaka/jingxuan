package com.whale.crm.integration.wecom.h5card.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.h5card.api.dto.CreateWecomH5CardRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.DisableWecomH5CardRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.PublicWecomH5CardResponse;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardResponse;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardUsageRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardUsageResponse;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5Card;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5CardRepository;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5CardUsage;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Project;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WecomH5CardService {
    private static final String CREATE_SCOPE = "wecom.h5.card.create";
    private static final String DISABLE_SCOPE = "wecom.h5.card.disable";
    private static final String USAGE_SCOPE = "wecom.h5.card.usage";
    private static final Set<String> FORBIDDEN_CLAIMS = Set.of("保证治愈", "保证有效", "百分之百", "百分百",
            "根治", "永久有效", "最低价");
    private static final Pattern SHARE_KEY = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final WecomH5CardRepository repository;
    private final WecomRepository wecomRepository;
    private final CatalogApplicationService catalogService;
    private final CustomerScopeSupport scopeSupport;
    private final WecomSidebarProperties properties;
    private final QuickReplyTextMasker masker;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public WecomH5CardService(WecomH5CardRepository repository, WecomRepository wecomRepository,
                              CatalogApplicationService catalogService, CustomerScopeSupport scopeSupport,
                              WecomSidebarProperties properties, QuickReplyTextMasker masker,
                              IdempotencyService idempotencyService, AuditService auditService) {
        this.repository = repository;
        this.wecomRepository = wecomRepository;
        this.catalogService = catalogService;
        this.scopeSupport = scopeSupport;
        this.properties = properties;
        this.masker = masker;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
    }

    @Transactional
    public WecomH5CardResponse create(CurrentUser user, CreateWecomH5CardRequest request, String key) {
        String payload = payload(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), CREATE_SCOPE, key, payload);
        if (replay.isPresent()) {
            return view(owned(user, resourceId(replay.orElseThrow())));
        }
        scopeSupport.requireVisible(user.tenantId(), request.customerId(), user);
        if (!wecomRepository.contactMapped(user.tenantId(), request.externalUserid(), request.customerId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微客户与 CRM 客户映射不一致");
        }
        Project project = catalogService.activeProjectForSharing(user.tenantId(), request.projectId());
        String title = publicText(request.title(), "卡片标题", 160);
        String summary = publicText(request.summary(), "卡片摘要", 500);
        String content = publicText(request.content(), "公开介绍", 4000);
        String shareKey = newShareKey();
        WecomH5Card input = new WecomH5Card(null, user.tenantId(), project.id(), masker.mask(project.projectName(), 160),
                request.customerId(), user.userId(), request.externalUserid().strip(), title, summary, content,
                shareKey, "ACTIVE", null, null, null, null, null, 0);
        repository.insert(input);
        WecomH5Card created = repository.findByShareKey(shareKey).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "WECOM_H5_CARD_CREATE", "WECOM_H5_CARD",
                created.id().toString(), null, snapshot(created), "员工生成项目公开介绍分享快照");
        idempotencyService.recordSuccess(user.tenantId(), CREATE_SCOPE, key, "WECOM_H5_CARD",
                created.id().toString(), payload);
        return view(created);
    }

    @Transactional(readOnly = true)
    public List<WecomH5CardResponse> history(CurrentUser user, long customerId, int size) {
        scopeSupport.requireVisible(user.tenantId(), customerId, user);
        return repository.findForCustomer(user.tenantId(), user.userId(), customerId, size).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PublicWecomH5CardResponse publicView(String shareKey) {
        if (!SHARE_KEY.matcher(shareKey).matches()) {
            throw notFound();
        }
        WecomH5Card card = repository.findByShareKey(shareKey).filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(this::notFound);
        return new PublicWecomH5CardResponse(card.projectNameSnapshot(), card.title(), card.summary(), card.content(),
                publicOrigin() + "/h5-card-cover.svg");
    }

    @Transactional
    public WecomH5CardResponse disable(CurrentUser user, long id, DisableWecomH5CardRequest request, String key) {
        String payload = payload(id, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), DISABLE_SCOPE, key, payload);
        if (replay.isPresent()) {
            if (!Long.toString(id).equals(replay.orElseThrow().getResourceId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等结果与卡片不一致");
            }
            return view(owned(user, id));
        }
        WecomH5Card before = repository.findByIdForUpdate(user.tenantId(), user.userId(), id).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), before.customerId(), user);
        if (before.version() != request.version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片已被处理，请刷新后重试");
        }
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片已停用，不能重复处理");
        }
        String reason = masker.required(request.reason(), "停用理由", 1000);
        if (repository.disable(user.tenantId(), user.userId(), id, before.version(), reason, user.userId(),
                Instant.now()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片已被处理，请刷新后重试");
        }
        WecomH5Card after = owned(user, id);
        auditService.record(user.tenantId(), user.userId(), "WECOM_H5_CARD_DISABLE", "WECOM_H5_CARD",
                Long.toString(id), Map.of("status", before.status(), "version", before.version()),
                Map.of("status", after.status(), "version", after.version()), reason);
        idempotencyService.recordSuccess(user.tenantId(), DISABLE_SCOPE, key, "WECOM_H5_CARD",
                Long.toString(id), payload);
        return view(after);
    }

    @Transactional
    public WecomH5CardUsageResponse recordUsage(CurrentUser user, long cardId, WecomH5CardUsageRequest request,
                                                String key) {
        String payload = payload(cardId, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), USAGE_SCOPE, key, payload);
        if (replay.isPresent()) {
            WecomH5CardUsage stored = usage(user.tenantId(), key);
            if (stored.employeeId() != user.userId()) {
                throw notFound();
            }
            scopeSupport.requireVisible(user.tenantId(), stored.customerId(), user);
            return usageView(stored);
        }
        WecomH5Card card = owned(user, cardId);
        scopeSupport.requireVisible(user.tenantId(), request.customerId(), user);
        if (card.customerId() != request.customerId()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "卡片与当前客户不一致");
        }
        if (!wecomRepository.contactMapped(user.tenantId(), card.externalUserid(), request.customerId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微客户与 CRM 客户映射不一致");
        }
        String clientResult = request.clientResult() == null || request.clientResult().isBlank() ? null
                : masker.mask(request.clientResult(), 512);
        WecomH5CardUsage input = new WecomH5CardUsage(null, user.tenantId(), cardId, user.userId(), request.customerId(),
                request.sendStatus(), clientResult, Instant.now(), key, null);
        repository.insertUsage(input);
        WecomH5CardUsage stored = usage(user.tenantId(), key);
        auditService.record(user.tenantId(), user.userId(), "WECOM_H5_CARD_USAGE", "WECOM_H5_CARD_USAGE",
                stored.id().toString(), null, Map.of("cardId", cardId, "customerId", request.customerId(),
                        "sendStatus", request.sendStatus()), "员工复制链接或企微当前会话 SDK 回调");
        idempotencyService.recordSuccess(user.tenantId(), USAGE_SCOPE, key, "WECOM_H5_CARD_USAGE",
                stored.id().toString(), payload);
        return usageView(stored);
    }

    private WecomH5Card owned(CurrentUser user, long id) {
        WecomH5Card card = repository.findById(user.tenantId(), user.userId(), id).orElseThrow(this::notFound);
        scopeSupport.requireVisible(user.tenantId(), card.customerId(), user);
        return card;
    }

    private String publicText(String value, String label, int maxLength) {
        String result = masker.required(value, label, maxLength);
        if (FORBIDDEN_CLAIMS.stream().anyMatch(result.toLowerCase(Locale.ROOT)::contains)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "公开介绍不能包含疗效保证或绝对化承诺");
        }
        return result;
    }

    private String publicOrigin() {
        try {
            URI uri = URI.create(properties.sidebarUrl());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local = List.of("localhost", "127.0.0.1", "::1").contains(host);
            if (host.isEmpty() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(scheme) || "http".equals(scheme) && local)) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "H5 公开地址必须使用受信 HTTPS 域名");
            }
            String origin = scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            boolean trusted = properties.trustedOrigins().stream().map(this::normalizeOrigin).anyMatch(origin::equals);
            if (!trusted) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "H5 公开地址不在企微受信域名范围");
            }
            return origin;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "H5 公开地址未正确配置");
        }
    }

    private String normalizeOrigin(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    private WecomH5CardResponse view(WecomH5Card card) {
        String origin = publicOrigin();
        return new WecomH5CardResponse(card.id(), card.customerId(), card.projectId(), card.projectNameSnapshot(),
                card.title(), card.summary(), card.content(), origin + "/h5/cards/" + card.shareKey(),
                origin + "/h5-card-cover.svg", card.status(), card.createdAt(), card.version());
    }

    private WecomH5CardUsageResponse usageView(WecomH5CardUsage usage) {
        return new WecomH5CardUsageResponse(usage.id(), usage.cardId(), usage.customerId(), usage.sendStatus(),
                usage.clientResult(), usage.sendTime(), usage.createdAt());
    }

    private WecomH5CardUsage usage(long tenantId, String key) {
        return repository.findUsageByKey(tenantId, key).orElseThrow(this::notFound);
    }

    private Map<String, Object> snapshot(WecomH5Card card) {
        return Map.of("projectId", card.projectId(), "customerId", card.customerId(), "status", card.status(),
                "version", card.version());
    }

    private String newShareKey() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "卡片幂等结果无效");
        }
    }

    private String payload(Object... values) {
        return java.util.Arrays.deepToString(values);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "卡片不存在或不可见");
    }
}
