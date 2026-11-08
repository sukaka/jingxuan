package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiJobResponse;
import com.whale.crm.ai.api.dto.AiQualitySummaryResponse;
import com.whale.crm.ai.api.dto.ConfirmAiJobRequest;
import com.whale.crm.ai.api.dto.CreateAiJobRequest;
import com.whale.crm.ai.domain.AiInsight;
import com.whale.crm.ai.domain.AiJob;
import com.whale.crm.ai.domain.AiJobRepository;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.TaskApplicationService;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiJobApplicationService {
    private static final Set<String> ACTIONS = Set.of("VERIFY_BALANCE", "ASK_PREFERRED_INSTITUTION", "CREATE_FOLLOWUP_DRAFT", "VERIFY_SERVICE_RECORD", "OFFER_HUMAN_SUPPORT", "VERIFY_PAYMENT_RECORD");
    private final AiJobRepository repository;
    private final WecomRepository wecomRepository;
    private final WecomArchiveService archiveService;
    private final CustomerScopeSupport scopeSupport;
    private final CurrentUserService currentUserService;
    private final CatalogApplicationService catalogService;
    private final TaskApplicationService taskService;
    private final AiWorkerClient workerClient;
    private final AiProviderConfigService providerConfigService;
    private final AuditService auditService;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public AiJobApplicationService(AiJobRepository repository, WecomRepository wecomRepository,
                                   WecomArchiveService archiveService, CustomerScopeSupport scopeSupport,
                                   CurrentUserService currentUserService, CatalogApplicationService catalogService,
                                   TaskApplicationService taskService, AiWorkerClient workerClient,
                                   AiProviderConfigService providerConfigService, AuditService auditService,
                                   ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.wecomRepository = wecomRepository;
        this.archiveService = archiveService;
        this.scopeSupport = scopeSupport;
        this.currentUserService = currentUserService;
        this.catalogService = catalogService;
        this.taskService = taskService;
        this.workerClient = workerClient;
        this.providerConfigService = providerConfigService;
        this.auditService = auditService;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public AiJobResponse create(CurrentUser user, CreateAiJobRequest request, String key) {
        String hash = hash(String.join(",", request.messageIds().stream().distinct().sorted().map(String::valueOf).toList()));
        var existing = repository.findByIdempotency(user.tenantId(), user.userId(), key);
        if (existing.isPresent()) {
            requireSame(existing.get().requestHash(), hash);
            return view(existing.get());
        }
        if (request.messageIds().stream().distinct().count() != request.messageIds().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "消息 ID 不能重复");
        }
        AuthorizedContext context = authorizedContext(user, request.messageIds());
        AiJob input = new AiJob(null, user.tenantId(), context.customerId(), context.conversationId(), user.userId(),
                json(request.messageIds()), hash, key, "QUEUED", null, null, 0, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        repository.insert(input);
        AiJob created = repository.findByIdempotency(user.tenantId(), user.userId(), key).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "AI_JOB_CREATE", "AI_JOB", created.id().toString(),
                null, Map.of("messageIds", request.messageIds(), "status", created.status()), null);
        return view(created);
    }

    @Transactional(readOnly = true)
    public AiJobResponse get(CurrentUser user, long id) {
        return view(scoped(user, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<AiJobResponse> search(CurrentUser user, String conversationId, String status, int page, int size) {
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        List<AiJobResponse> items = repository.search(user.tenantId(), conversationId, status, query.scopeType(),
                query.teamIds(), query.selfId(), (page - 1) * size, size).stream().map(this::view).toList();
        return new PageResponse<>(items, repository.countSearch(user.tenantId(), conversationId, status,
                query.scopeType(), query.teamIds(), query.selfId()), page, size);
    }

    @Transactional(readOnly = true)
    public AiQualitySummaryResponse quality(CurrentUser user) {
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        AiJobRepository.AiQualityCount count = repository.qualityCount(user.tenantId(), query.scopeType(), query.teamIds(), query.selfId());
        return new AiQualitySummaryResponse(count.pendingReview(), count.riskSuggested(), count.failed(), count.confirmed(),
                "风险标记仅为 AI 建议，必须结合原始证据人工核实，不作为处罚依据");
    }

    @Transactional
    public AiJobResponse confirm(CurrentUser user, long id, ConfirmAiJobRequest request, String key) {
        AiJob job = repository.findByIdForUpdate(user.tenantId(), id).orElseThrow(this::notFound);
        scoped(user, id);
        String hash = hash(request.decision() + "|" + request.title() + "|" + request.description() + "|" + request.dueAt() + "|" + request.reason());
        if (List.of("CONFIRMED", "REJECTED").contains(job.status())) {
            if (key.equals(job.confirmationKey()) && hash.equals(job.confirmationHash())) {
                return view(job);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "该 AI 建议已完成人工处理，不能重复确认");
        }
        if (!"PROPOSED".equals(job.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待人工确认的抽取结果可以处理");
        }
        if (!job.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 任务已被他人处理，请刷新后重试");
        }
        Long taskId = null;
        String target = "REJECT".equals(request.decision()) ? "REJECTED" : "CONFIRMED";
        if ("CONFIRM".equals(request.decision())) {
            if (request.title() == null || request.title().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "确认生成待办时标题不能为空");
            }
            taskId = taskService.createFromAi(user.tenantId(), user, job.customerId(), "AI_JOB:" + id,
                    request.title().strip(), request.description(), request.dueAt());
        } else if (request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "驳回 AI 建议必须填写原因");
        }
        if (repository.confirm(user.tenantId(), id, job.version(), target, user.userId(), Instant.now(),
                request.reason(), key, hash, taskId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 任务状态已变化，请刷新后重试");
        }
        AiJob after = repository.findById(user.tenantId(), id).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "AI_JOB_" + target, "AI_JOB", Long.toString(id),
                Map.of("status", job.status()), Map.of("status", after.status(), "taskId", taskId), request.reason());
        return view(after);
    }

    public void execute(long tenantId, long jobId) {
        AiJob job = repository.findById(tenantId, jobId).orElseThrow();
        try {
            CurrentUser user = currentUserService.loadById(job.requestedBy())
                    .filter(value -> value.permissions().contains("ai:write"))
                    .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "任务创建人已停用或不再具备 AI 权限"));
            AuthorizedContext context = authorizedContext(user, messageIds(job.messageIdsJson()));
            AiWorkerClient.ExtractionResult result = workerClient.extract(job.id(), job.customerId(), context.messages(),
                    catalogService.projects(tenantId, null, "ACTIVE", 1, 100).items().stream()
                            .map(project -> new AiWorkerClient.ProjectInput(project.projectCode(), project.projectName())).toList(),
                    catalogService.institutions(tenantId, user, null, "ACTIVE", 1, 100).items().stream()
                            .map(item -> new AiWorkerClient.InstitutionInput(Long.toString(item.id()), item.fullName())).toList(),
                    providerConfigService.workerConfig(tenantId));
            validateResult(result, context);
            transactions.execute(status -> {
                AiJob running = repository.findByIdForUpdate(tenantId, jobId).orElseThrow();
                AiWorkerClient.InsightData value = result.insight();
                repository.insertInsight(new AiInsight(null, tenantId, jobId, value.insightType(), json(value.projectCodes()),
                        value.mentionedInstitution(), value.mentionedTime(), value.absoluteTime(), json(value.evidenceMessageIds()),
                        value.confidence(), json(value.proposedActions()), value.factStatus(), value.allowFinancialWrite(),
                        value.reasoningSummary(), value.replyDraft(), json(value.followupDraft()), json(value.riskFlags()), null));
                if (repository.markProposed(tenantId, jobId, running.version(), result.provider(), result.model()) != 1) {
                    throw new IllegalStateException("AI 任务状态已变化");
                }
                auditService.record(tenantId, job.requestedBy(), "AI_JOB_PROPOSED", "AI_JOB", Long.toString(jobId),
                        null, Map.of("provider", result.provider(), "model", result.model(), "factStatus", "UNVERIFIED"), null);
                return null;
            });
        } catch (BusinessException ex) {
            fail(job, ex.getMessage(), true);
        } catch (AiWorkerException ex) {
            fail(job, ex.getMessage(), ex.terminal());
        } catch (Exception ex) {
            fail(job, "AI 抽取处理失败，请稍后重试", false);
        }
    }

    private AuthorizedContext authorizedContext(CurrentUser user, List<Long> ids) {
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        List<WecomMessage> messages = new ArrayList<>();
        for (Long id : ids) {
            WecomMessage message = wecomRepository.findReadableMessage(user.tenantId(), id, query.scopeType(), query.teamIds(), query.selfId())
                    .orElseThrow(this::notFound);
            if (!"TEXT".equals(message.messageType())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前只支持授权文本消息抽取");
            }
            messages.add(message);
        }
        messages.sort(Comparator.comparing(WecomMessage::sentAt));
        Long customerId = messages.getFirst().customerId();
        String conversationId = messages.getFirst().conversationId() == null ? messages.getFirst().externalUserid() : messages.getFirst().conversationId();
        if (customerId == null || messages.stream().anyMatch(item -> !customerId.equals(item.customerId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "一次任务只能分析同一已匹配客户");
        }
        String finalConversationId = conversationId;
        if (messages.stream().anyMatch(item -> !finalConversationId.equals(item.conversationId() == null ? item.externalUserid() : item.conversationId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "一次任务只能分析同一会话");
        }
        scopeSupport.requireVisible(user.tenantId(), customerId, user);
        List<AiWorkerClient.MessageInput> input = messages.stream()
                .map(message -> new AiWorkerClient.MessageInput(Long.toString(message.id()), message.senderType(), message.sentAt(), archiveService.readContent(message)))
                .filter(message -> !message.text().isBlank()).toList();
        if (input.isEmpty() || input.stream().mapToInt(message -> message.text().length()).sum() > 30000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "授权文本为空或超过 30000 字");
        }
        return new AuthorizedContext(customerId, conversationId, input);
    }

    private void validateResult(AiWorkerClient.ExtractionResult result, AuthorizedContext context) {
        AiWorkerClient.InsightData value = result.insight();
        if (value == null || !"UNVERIFIED".equals(value.factStatus()) || !Boolean.FALSE.equals(value.allowFinancialWrite())
                || value.evidenceMessageIds() == null || value.evidenceMessageIds().isEmpty()
                || value.proposedActions() == null || !ACTIONS.containsAll(value.proposedActions())) {
            throw new AiWorkerException("AI 输出未通过安全校验", true);
        }
        Set<String> messageIds = context.messages().stream().map(AiWorkerClient.MessageInput::id).collect(java.util.stream.Collectors.toSet());
        if (!messageIds.containsAll(value.evidenceMessageIds())) {
            throw new AiWorkerException("AI 输出包含伪造证据", true);
        }
    }

    private void fail(AiJob job, String message, boolean terminal) {
        transactions.execute(status -> {
            AiJob running = repository.findByIdForUpdate(job.tenantId(), job.id()).orElse(null);
            if (running == null || !"RUNNING".equals(running.status())) {
                return null;
            }
            boolean stop = terminal || running.attemptCount() >= 5;
            long delay = Math.min(60000, 1000L * (1L << Math.min(6, running.attemptCount())));
            repository.markRetry(job.tenantId(), job.id(), running.version(), message == null ? "AI 抽取失败" : message.substring(0, Math.min(500, message.length())),
                    stop ? null : Instant.now().plusMillis(delay), stop);
            auditService.record(job.tenantId(), job.requestedBy(), "AI_JOB_FAILED", "AI_JOB", job.id().toString(),
                    null, Map.of("terminal", stop, "attemptCount", running.attemptCount()), null);
            return null;
        });
    }

    private AiJob scoped(CurrentUser user, long id) {
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scopeSupport.scope(user), user);
        return repository.findScopedById(user.tenantId(), id, query.scopeType(), query.teamIds(), query.selfId()).orElseThrow(this::notFound);
    }

    private AiJobResponse view(AiJob job) {
        return AiJobResponse.of(job, repository.findInsight(job.tenantId(), job.id()).orElse(null), mapper);
    }

    private List<Long> messageIds(String json) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception ex) {
            throw new AiWorkerException("AI 任务消息快照损坏", true);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI 结构序列化失败", ex);
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void requireSame(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一幂等键不能用于不同请求");
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "资源不存在或不在数据范围内");
    }

    private record AuthorizedContext(Long customerId, String conversationId, List<AiWorkerClient.MessageInput> messages) { }
}
