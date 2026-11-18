package com.whale.crm.quickreply.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.institution.application.InstitutionProfileService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.api.dto.QuickReplyCategoryRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyCategoryResponse;
import com.whale.crm.quickreply.api.dto.QuickReplyReasonRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateRequest;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateResponse;
import com.whale.crm.quickreply.domain.QuickReplyCategory;
import com.whale.crm.quickreply.domain.QuickReplyRepository;
import com.whale.crm.quickreply.domain.QuickReplyTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class QuickReplyAdminService {
    private static final String CATEGORY_CREATE = "quickreply.category.create";
    private static final String CATEGORY_UPDATE = "quickreply.category.update";
    private static final String CATEGORY_DISABLE = "quickreply.category.disable";
    private static final String TEMPLATE_CREATE = "quickreply.template.create";
    private static final String TEMPLATE_UPDATE = "quickreply.template.update";
    private static final String TEMPLATE_DISABLE = "quickreply.template.disable";

    private final QuickReplyRepository repository;
    private final QuickReplyTextMasker masker;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final InstitutionProfileService institutionService;

    public QuickReplyAdminService(QuickReplyRepository repository, QuickReplyTextMasker masker,
                                  IdempotencyService idempotencyService, AuditService auditService,
                                  ObjectMapper objectMapper, InstitutionProfileService institutionService) {
        this.repository = repository;
        this.masker = masker;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.institutionService = institutionService;
    }

    @Transactional
    public QuickReplyCategoryResponse createCategory(CurrentUser user, QuickReplyCategoryRequest request,
                                                      String idempotencyKey) {
        requireManage(user);
        String payload = payload(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), CATEGORY_CREATE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return QuickReplyCategoryResponse.from(category(user.tenantId(), resourceId(replay.orElseThrow())));
        }
        requireCreateVersion(request.version());
        String code = code(request.code());
        requireCategoryCodeAvailable(user.tenantId(), code, null);
        Scope scope = scope(user, request.scopeType(), request.scopeId());
        Long parentId = validParent(user.tenantId(), null, request.parentId());
        QuickReplyCategory input = new QuickReplyCategory(null, user.tenantId(), parentId,
                masker.required(request.name(), "分类名称", 64), code, scope.type(), scope.id(), request.sortNo(),
                "ACTIVE", user.userId(), null, null, 0L);
        repository.insertCategory(input);
        QuickReplyCategory stored = categoryByCode(user.tenantId(), code);
        audit(user, "QUICK_REPLY_CATEGORY_CREATE", "QUICK_REPLY_CATEGORY", stored.id(), null, categorySnapshot(stored),
                request.reason());
        idempotencyService.recordSuccess(user.tenantId(), CATEGORY_CREATE, idempotencyKey, "QUICK_REPLY_CATEGORY",
                stored.id().toString(), payload);
        return QuickReplyCategoryResponse.from(stored);
    }

    @Transactional
    public QuickReplyCategoryResponse updateCategory(CurrentUser user, long id, QuickReplyCategoryRequest request,
                                                      String idempotencyKey) {
        requireManage(user);
        String payload = payload(id, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), CATEGORY_UPDATE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return QuickReplyCategoryResponse.from(category(user.tenantId(), id));
        }
        QuickReplyCategory before = categoryForUpdate(user.tenantId(), id);
        requireVersion(before.version(), request.version());
        if (!code(request.code()).equals(before.code())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分类编码创建后不可修改");
        }
        Scope scope = scope(user, request.scopeType(), request.scopeId());
        Long parentId = validParent(user.tenantId(), id, request.parentId());
        boolean scopeChanged = !Objects.equals(before.scopeType(), scope.type())
                || !Objects.equals(before.scopeId(), scope.id());
        boolean hasTemplates = repository.findTemplates(user.tenantId()).stream()
                .anyMatch(template -> template.categoryId() == id);
        if (scopeChanged && hasTemplates) {
            throw new BusinessException(ErrorCode.CONFLICT, "分类已有话术，不允许改变作用域");
        }
        QuickReplyCategory input = new QuickReplyCategory(id, user.tenantId(), parentId,
                masker.required(request.name(), "分类名称", 64), before.code(), scope.type(), scope.id(),
                request.sortNo(), before.status(), before.createdBy(), before.createdAt(), null, before.version());
        if (repository.updateCategory(input) != 1) {
            throw conflict();
        }
        QuickReplyCategory after = category(user.tenantId(), id);
        audit(user, "QUICK_REPLY_CATEGORY_UPDATE", "QUICK_REPLY_CATEGORY", id, categorySnapshot(before),
                categorySnapshot(after), request.reason());
        idempotencyService.recordSuccess(user.tenantId(), CATEGORY_UPDATE, idempotencyKey, "QUICK_REPLY_CATEGORY",
                Long.toString(id), payload);
        return QuickReplyCategoryResponse.from(after);
    }

    @Transactional
    public QuickReplyCategoryResponse disableCategory(CurrentUser user, long id, QuickReplyReasonRequest request,
                                                       String idempotencyKey) {
        requireManage(user);
        String payload = payload(id, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), CATEGORY_DISABLE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return QuickReplyCategoryResponse.from(category(user.tenantId(), id));
        }
        QuickReplyCategory before = categoryForUpdate(user.tenantId(), id);
        requireVersion(before.version(), request.version());
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "分类已停用");
        }
        boolean hasActiveTemplates = repository.findTemplates(user.tenantId()).stream()
                .anyMatch(template -> template.categoryId() == id && "ACTIVE".equals(template.status()));
        if (hasActiveTemplates) {
            throw new BusinessException(ErrorCode.CONFLICT, "分类下仍有启用话术，请先停用相关话术");
        }
        if (repository.disableCategory(user.tenantId(), id, request.version()) != 1) {
            throw conflict();
        }
        QuickReplyCategory after = category(user.tenantId(), id);
        audit(user, "QUICK_REPLY_CATEGORY_DISABLE", "QUICK_REPLY_CATEGORY", id, categorySnapshot(before),
                categorySnapshot(after), request.reason());
        idempotencyService.recordSuccess(user.tenantId(), CATEGORY_DISABLE, idempotencyKey, "QUICK_REPLY_CATEGORY",
                Long.toString(id), payload);
        return QuickReplyCategoryResponse.from(after);
    }

    @Transactional
    public QuickReplyTemplateResponse createTemplate(CurrentUser user, QuickReplyTemplateRequest request,
                                                      String idempotencyKey) {
        requireManage(user);
        String payload = payload(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), TEMPLATE_CREATE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return QuickReplyTemplateResponse.from(template(user.tenantId(), resourceId(replay.orElseThrow())));
        }
        requireCreateVersion(request.version());
        String code = code(request.code());
        if (repository.findTemplateByCode(user.tenantId(), code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术编码已存在");
        }
        QuickReplyCategory category = category(user.tenantId(), request.categoryId());
        Scope scope = scope(user, request.scopeType(), request.scopeId());
        requireCategoryScope(category, scope);
        QuickReplyTemplate input = new QuickReplyTemplate(null, user.tenantId(), category.id(),
                masker.required(request.name(), "话术名称", 96), code, "TEXT",
                masker.required(request.content(), "话术内容", 2000), scene(request.scene()), scope.type(), scope.id(),
                request.allowEdit(), request.requirePreview(), request.aiEnabled(), request.featured(), "ACTIVE", request.sortNo(),
                user.userId(), null, null, 0L);
        repository.insertTemplate(input);
        QuickReplyTemplate stored = templateByCode(user.tenantId(), code);
        audit(user, "QUICK_REPLY_TEMPLATE_CREATE", "QUICK_REPLY_TEMPLATE", stored.id(), null,
                templateSnapshot(stored), request.reason());
        idempotencyService.recordSuccess(user.tenantId(), TEMPLATE_CREATE, idempotencyKey, "QUICK_REPLY_TEMPLATE",
                stored.id().toString(), payload);
        return QuickReplyTemplateResponse.from(stored);
    }

    @Transactional
    public QuickReplyTemplateResponse updateTemplate(CurrentUser user, long id, QuickReplyTemplateRequest request,
                                                      String idempotencyKey) {
        requireManage(user);
        String payload = payload(id, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), TEMPLATE_UPDATE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return QuickReplyTemplateResponse.from(template(user.tenantId(), id));
        }
        QuickReplyTemplate before = templateForUpdate(user.tenantId(), id);
        requireVersion(before.version(), request.version());
        if (!code(request.code()).equals(before.code())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "话术编码创建后不可修改");
        }
        QuickReplyCategory category = category(user.tenantId(), request.categoryId());
        Scope scope = scope(user, request.scopeType(), request.scopeId());
        requireCategoryScope(category, scope);
        QuickReplyTemplate input = new QuickReplyTemplate(id, user.tenantId(), category.id(),
                masker.required(request.name(), "话术名称", 96), before.code(), "TEXT",
                masker.required(request.content(), "话术内容", 2000), scene(request.scene()), scope.type(), scope.id(),
                request.allowEdit(), request.requirePreview(), request.aiEnabled(), request.featured(), before.status(), request.sortNo(),
                before.createdBy(), before.createdAt(), null, before.version());
        if (repository.updateTemplate(input) != 1) {
            throw conflict();
        }
        QuickReplyTemplate after = template(user.tenantId(), id);
        audit(user, "QUICK_REPLY_TEMPLATE_UPDATE", "QUICK_REPLY_TEMPLATE", id, templateSnapshot(before),
                templateSnapshot(after), request.reason());
        idempotencyService.recordSuccess(user.tenantId(), TEMPLATE_UPDATE, idempotencyKey, "QUICK_REPLY_TEMPLATE",
                Long.toString(id), payload);
        return QuickReplyTemplateResponse.from(after);
    }

    @Transactional
    public QuickReplyTemplateResponse disableTemplate(CurrentUser user, long id, QuickReplyReasonRequest request,
                                                       String idempotencyKey) {
        requireManage(user);
        String payload = payload(id, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(user.tenantId(), TEMPLATE_DISABLE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            requireResource(replay.orElseThrow(), id);
            return QuickReplyTemplateResponse.from(template(user.tenantId(), id));
        }
        QuickReplyTemplate before = templateForUpdate(user.tenantId(), id);
        requireVersion(before.version(), request.version());
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术已停用");
        }
        if (repository.disableTemplate(user.tenantId(), id, request.version()) != 1) {
            throw conflict();
        }
        QuickReplyTemplate after = template(user.tenantId(), id);
        audit(user, "QUICK_REPLY_TEMPLATE_DISABLE", "QUICK_REPLY_TEMPLATE", id, templateSnapshot(before),
                templateSnapshot(after), request.reason());
        idempotencyService.recordSuccess(user.tenantId(), TEMPLATE_DISABLE, idempotencyKey, "QUICK_REPLY_TEMPLATE",
                Long.toString(id), payload);
        return QuickReplyTemplateResponse.from(after);
    }

    private void requireManage(CurrentUser user) {
        if (!user.permissions().contains("quickreply:manage")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有快捷话术管理权限");
        }
    }

    private Scope scope(CurrentUser user, String type, Long requestedId) {
        return switch (type) {
            case "GLOBAL" -> {
                if (requestedId != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "全局话术不需要 scopeId");
                }
                yield new Scope(type, null);
            }
            case "PERSONAL" -> {
                if (requestedId != null && requestedId != user.userId()) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "不能为其他员工创建个人话术");
                }
                yield new Scope(type, user.userId());
            }
            case "DEPARTMENT" -> {
                if (user.teamId() == null || requestedId != null && !requestedId.equals(user.teamId())) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "部门话术范围必须是当前团队");
                }
                yield new Scope(type, user.teamId());
            }
            case "INSTITUTION" -> {
                if (requestedId == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "机构话术必须指定 scopeId");
                }
                institutionService.detail(user.tenantId(), user, requestedId);
                yield new Scope(type, requestedId);
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知话术作用域");
        };
    }

    private Long validParent(long tenantId, Long id, Long parentId) {
        if (parentId == null) {
            return null;
        }
        if (id != null && id.equals(parentId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分类不能以自身为父级");
        }
        QuickReplyCategory parent = category(tenantId, parentId);
        if (!"ACTIVE".equals(parent.status()) || parent.parentId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "父分类必须启用且分类最多两层");
        }
        return parentId;
    }

    private void requireCategoryScope(QuickReplyCategory category, Scope scope) {
        if (!"ACTIVE".equals(category.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "话术分类已停用");
        }
        if (!Objects.equals(category.scopeType(), scope.type())
                || !Objects.equals(category.scopeId(), scope.id())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "话术与分类的作用域必须一致");
        }
    }

    private void requireCategoryCodeAvailable(long tenantId, String code, Long ignored) {
        repository.findCategoryByCode(tenantId, code).filter(other -> !Objects.equals(other.id(), ignored))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "分类编码已存在");
                });
    }

    private void requireCreateVersion(Long version) {
        if (version != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "创建操作不需要 version");
        }
    }

    private void requireVersion(long actual, Long expected) {
        if (expected == null || actual != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, "配置已变化，请刷新后重试");
        }
    }

    private String code(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,47}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "编码只能使用大写字母、数字和下划线");
        }
        return normalized;
    }

    private String scene(String value) {
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "场景编码格式无效");
        }
        return normalized;
    }

    private QuickReplyCategory category(long tenantId, long id) {
        return repository.findCategoryById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
    }

    private QuickReplyCategory categoryForUpdate(long tenantId, long id) {
        return repository.findCategoryByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
    }

    private QuickReplyCategory categoryByCode(long tenantId, String code) {
        return repository.findCategoryByCode(tenantId, code).orElseThrow();
    }

    private QuickReplyTemplate template(long tenantId, long id) {
        return repository.findTemplateById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "话术不存在"));
    }

    private QuickReplyTemplate templateForUpdate(long tenantId, long id) {
        return repository.findTemplateByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "话术不存在"));
    }

    private QuickReplyTemplate templateByCode(long tenantId, String code) {
        return repository.findTemplateByCode(tenantId, code).orElseThrow();
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "配置已变化，请刷新后重试");
    }

    private void requireResource(IdempotencyRecord record, long id) {
        if (!Long.toString(id).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源不一致");
        }
    }

    private long resourceId(IdempotencyRecord record) {
        try {
            return Long.parseLong(record.getResourceId());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果资源无效");
        }
    }

    private void audit(CurrentUser user, String action, String type, long id, Object before, Object after,
                       String reason) {
        auditService.record(user.tenantId(), user.userId(), action, type, Long.toString(id), before, after,
                masker.required(reason, "操作理由", 1000));
    }

    private Map<String, Object> categorySnapshot(QuickReplyCategory category) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", category.name());
        value.put("code", category.code());
        value.put("scopeType", category.scopeType());
        value.put("scopeId", category.scopeId());
        value.put("status", category.status());
        value.put("version", category.version());
        return value;
    }

    private Map<String, Object> templateSnapshot(QuickReplyTemplate template) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", template.name());
        value.put("code", template.code());
        value.put("content", masker.mask(template.content(), 2000));
        value.put("scene", template.scene());
        value.put("aiEnabled", template.aiEnabled());
        value.put("featured", template.featured());
        value.put("scopeType", template.scopeType());
        value.put("scopeId", template.scopeId());
        value.put("status", template.status());
        value.put("version", template.version());
        return value;
    }

    private String payload(Object... values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等请求序列化失败", ex);
        }
    }

    private record Scope(String type, Long id) {
    }
}
