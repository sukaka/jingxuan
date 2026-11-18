package com.whale.crm.quickreply.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyUsageStatResponse;
import com.whale.crm.quickreply.domain.QuickReplyRepository;
import com.whale.crm.quickreply.domain.QuickReplyUsageStat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class QuickReplyEngagementService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FAVORITE_SCOPE = "quickreply.favorite.set";
    private static final String UNFAVORITE_SCOPE = "quickreply.favorite.remove";

    private final QuickReplyRepository repository;
    private final QuickReplyPreviewService previewService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public QuickReplyEngagementService(QuickReplyRepository repository, QuickReplyPreviewService previewService,
                                       IdempotencyService idempotencyService, AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.previewService = previewService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QuickReplyTemplateResponse setFavorite(CurrentUser user, long templateId, boolean favorite, String key) {
        requireView(user);
        String scope = favorite ? FAVORITE_SCOPE : UNFAVORITE_SCOPE;
        String payload = payload(templateId, favorite);
        if (idempotencyService.lookup(user.tenantId(), scope, key, payload).isPresent()) {
            return current(user, templateId);
        }
        QuickReplyTemplateResponse before = current(user, templateId);
        if (before.favorite() == favorite) {
            return before;
        }
        if (favorite) {
            repository.insertFavorite(user.tenantId(), templateId, user.userId());
        } else {
            repository.deleteFavorite(user.tenantId(), templateId, user.userId());
        }
        auditService.record(user.tenantId(), user.userId(), "QUICK_REPLY_FAVORITE_SET", "QUICK_REPLY_TEMPLATE",
                Long.toString(templateId), Map.of("favorite", before.favorite()), Map.of("favorite", favorite), null);
        idempotencyService.recordSuccess(user.tenantId(), scope, key, "QUICK_REPLY_TEMPLATE",
                Long.toString(templateId), payload);
        return current(user, templateId);
    }

    @Transactional(readOnly = true)
    public List<QuickReplyUsageStatResponse> statistics(CurrentUser user, Long employeeId,
                                                         LocalDate from, LocalDate to) {
        requireView(user);
        Long effectiveEmployee = user.userId();
        if (user.permissions().contains("quickreply:manage")) {
            effectiveEmployee = employeeId;
        } else if (employeeId != null && employeeId != user.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能查看其他员工的话术统计");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "统计开始日期不能晚于结束日期");
        }
        java.time.Instant fromInstant = from == null ? null : from.atStartOfDay(ZONE).toInstant();
        java.time.Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Map<Long, com.whale.crm.quickreply.domain.QuickReplyTemplate> templates = repository.findTemplates(user.tenantId())
                .stream().collect(java.util.stream.Collectors.toMap(com.whale.crm.quickreply.domain.QuickReplyTemplate::id,
                        java.util.function.Function.identity()));
        return repository.aggregateUsageStats(user.tenantId(), effectiveEmployee, fromInstant, toInstant).stream()
                .map(stat -> toResponse(stat, templates.get(stat.templateId())))
                .sorted(java.util.Comparator.comparingLong(QuickReplyUsageStatResponse::totalCount).reversed()
                        .thenComparing(QuickReplyUsageStatResponse::templateId)).toList();
    }

    private QuickReplyUsageStatResponse toResponse(QuickReplyUsageStat stat,
                                                   com.whale.crm.quickreply.domain.QuickReplyTemplate template) {
        long sdkResults = stat.successCount() + stat.failedCount() + stat.unknownCount();
        BigDecimal successRate = sdkResults == 0 ? null : BigDecimal.valueOf(stat.successCount() * 100)
                .divide(BigDecimal.valueOf(sdkResults), 2, RoundingMode.HALF_UP);
        return new QuickReplyUsageStatResponse(stat.templateId(), template == null ? "未知模板" : template.name(),
                template == null ? "UNKNOWN" : template.code(), stat.totalCount(), stat.copiedCount(),
                stat.successCount(), stat.failedCount(), stat.unknownCount(), stat.editedCount(),
                stat.employeeCount(), successRate, stat.lastUsedAt());
    }

    private QuickReplyTemplateResponse current(CurrentUser user, long templateId) {
        return previewService.list(user, null).stream().filter(template -> template.id() == templateId).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "话术不存在或不可见"));
    }

    private void requireView(CurrentUser user) {
        if (!user.permissions().contains("quickreply:view")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有快捷话术使用权限");
        }
    }

    private String payload(Object... values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等请求序列化失败", ex);
        }
    }
}
