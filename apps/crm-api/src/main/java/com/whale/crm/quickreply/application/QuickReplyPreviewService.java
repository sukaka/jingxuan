package com.whale.crm.quickreply.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.api.dto.QuickReplyCategoryResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyPreviewRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyPreviewResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyVariableResponse;
import com.whale.crm.quickreply.domain.QuickReplyRepository;
import com.whale.crm.quickreply.domain.QuickReplyTemplate;
import com.whale.crm.quickreply.domain.QuickReplyVariable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QuickReplyPreviewService {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private final QuickReplyRepository repository;
    private final QuickReplyFactsProvider factsProvider;
    private final Map<String, QuickReplyVariableResolver> resolvers;

    public QuickReplyPreviewService(QuickReplyRepository repository, QuickReplyFactsProvider factsProvider,
                                    List<QuickReplyVariableResolver> resolverList) {
        this.repository = repository;
        this.factsProvider = factsProvider;
        this.resolvers = resolverList.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                QuickReplyVariableResolver::code, Function.identity()));
    }

    @Transactional(readOnly = true)
    public List<QuickReplyTemplateResponse> list(CurrentUser user, String scene) {
        return list(user, scene, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<QuickReplyTemplateResponse> list(CurrentUser user, String scene, String keyword,
                                                 Boolean favoriteOnly, String sort) {
        Set<Long> favorites = Set.copyOf(repository.findFavoriteTemplateIds(user.tenantId(), user.userId()));
        Map<Long, com.whale.crm.quickreply.domain.QuickReplyUsageStat> usage = repository.aggregateUsageStats(
                user.tenantId(), user.userId(), null, null).stream().collect(java.util.stream.Collectors.toMap(
                        com.whale.crm.quickreply.domain.QuickReplyUsageStat::templateId, Function.identity()));
        String normalizedKeyword = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        boolean onlyFavorites = Boolean.TRUE.equals(favoriteOnly);
        Comparator<QuickReplyTemplateResponse> order = "RECENT".equals(sort)
                ? Comparator.comparing(QuickReplyTemplateResponse::lastUsedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(QuickReplyTemplateResponse::usageCount, Comparator.reverseOrder())
                .thenComparing(QuickReplyTemplateResponse::favorite, Comparator.reverseOrder())
                .thenComparingInt(QuickReplyTemplateResponse::sortNo)
                .thenComparingLong(QuickReplyTemplateResponse::id)
                : Comparator.comparing(QuickReplyTemplateResponse::favorite, Comparator.reverseOrder())
                .thenComparing(QuickReplyTemplateResponse::lastUsedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(QuickReplyTemplateResponse::aiEnabled, Comparator.reverseOrder())
                .thenComparing(QuickReplyTemplateResponse::featured, Comparator.reverseOrder())
                .thenComparingInt(QuickReplyTemplateResponse::sortNo)
                .thenComparingLong(QuickReplyTemplateResponse::id);
        return repository.findTemplates(user.tenantId()).stream()
                .filter(template -> "ACTIVE".equals(template.status()))
                .filter(template -> scene == null || scene.isBlank() || scene.equals(template.scene()))
                .filter(template -> visible(user, template.scopeType(), template.scopeId()))
                .filter(template -> matchesKeyword(template, normalizedKeyword))
                .map(template -> {
                    com.whale.crm.quickreply.domain.QuickReplyUsageStat stat = usage.get(template.id());
                    return QuickReplyTemplateResponse.from(template, favorites.contains(template.id()),
                            stat == null ? null : stat.lastUsedAt(), stat == null ? 0L : stat.totalCount());
                })
                .filter(item -> !onlyFavorites || item.favorite())
                .sorted(order).toList();
    }

    private boolean matchesKeyword(QuickReplyTemplate template, String normalizedKeyword) {
        if (normalizedKeyword.isEmpty()) {
            return true;
        }
        return containsIgnoreCase(template.name(), normalizedKeyword)
                || containsIgnoreCase(template.content(), normalizedKeyword)
                || containsIgnoreCase(template.code(), normalizedKeyword);
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Transactional(readOnly = true)
    public List<QuickReplyCategoryResponse> categories(CurrentUser user) {
        return repository.findCategories(user.tenantId()).stream()
                .filter(category -> "ACTIVE".equals(category.status()))
                .filter(category -> visible(user, category.scopeType(), category.scopeId()))
                .map(QuickReplyCategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<QuickReplyVariableResponse> variables(CurrentUser user) {
        return repository.findVariables().stream().filter(variable -> "ACTIVE".equals(variable.status()))
                .map(QuickReplyVariableResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public QuickReplyTemplate requireAiTemplate(CurrentUser user, long templateId) {
        QuickReplyTemplate template = repository.findTemplateById(user.tenantId(), templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 候选话术不存在或不可见"));
        if (!"ACTIVE".equals(template.status()) || !template.aiEnabled()
                || !visible(user, template.scopeType(), template.scopeId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "AI 候选话术不存在或未允许 AI 使用");
        }
        return template;
    }

    @Transactional(readOnly = true)
    public QuickReplyPreviewResponse preview(CurrentUser user, long templateId, QuickReplyPreviewRequest request) {
        QuickReplyTemplate template = repository.findTemplateById(user.tenantId(), templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "话术不存在或不可见"));
        if (!"ACTIVE".equals(template.status()) || !visible(user, template.scopeType(), template.scopeId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "话术不存在或不可见");
        }
        String scene = request.scene() == null || request.scene().isBlank() ? template.scene() : request.scene();
        if (!scene.equals(template.scene())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "话术与业务场景不匹配");
        }
        Map<String, Object> facts = factsProvider.load(user.tenantId(), user, request.customerId());
        QuickReplyContext context = new QuickReplyContext(user.tenantId(), user, request.customerId(), scene, facts);
        Map<String, QuickReplyVariable> variables = repository.findVariables().stream()
                .filter(variable -> "ACTIVE".equals(variable.status()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(QuickReplyVariable::resolverKey,
                        Function.identity()));
        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template.content());
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String code = matcher.group(1);
            QuickReplyVariable definition = variables.get(code);
            QuickReplyVariableResolver resolver = definition == null ? null : resolvers.get(definition.resolverKey());
            Object value = resolver == null ? null : resolver.resolve(context);
            resolved.put(code, value == null ? "" : value);
            if ((definition == null || definition.required()) && (value == null || value.toString().isBlank())) {
                missing.add(code);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(output);
        if (output.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "渲染后的话术超过 2000 字，请缩短模板");
        }
        return new QuickReplyPreviewResponse(template.id(), output.toString(), resolved, missing,
                missing.isEmpty(), template.allowEdit());
    }

    private boolean visible(CurrentUser user, String scopeType, Long scopeId) {
        return switch (scopeType) {
            case "GLOBAL" -> true;
            case "PERSONAL" -> scopeId != null && scopeId == user.userId();
            case "DEPARTMENT" -> scopeId != null && scopeId.equals(user.teamId());
            case "INSTITUTION" -> scopeId != null && user.institutionIds().contains(scopeId);
            default -> false;
        };
    }
}
