package com.whale.crm.customer.privacy.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerPrivacyFieldSupport;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.privacy.api.dto.ApprovePrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.CreatePrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyAccessLogResponse;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyRequestResponse;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyRevealResponse;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyStatusResponse;
import com.whale.crm.customer.privacy.api.dto.RejectPrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.RevealCustomerPrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.RevokePrivacyRequest;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyAccessLog;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyField;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRepository;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRequest;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 客户隐私信息保护、上级审批限时授权与显式揭盲应用服务。
 * <p>
 * 强制边界：
 * 1. 任何接口默认绝不返回手机号/身份证号/邮箱/微信号明文；
 * 2. 非 ADMIN 必须经上级主管审批通过，并在限时有效期内主动点击揭盲才返回获批字段明文；
 * 3. ADMIN 必须通过专用揭盲接口显式触发并填写必填查看原因，强制写入不可篡改揭盲日志与全局审计日志；
 * 4. 严格禁止自审；每次揭盲实时复核 DataScope、权限、授权状态与过期时间；越权/过期拒绝同样留痕。
 */
@Service
public class CustomerPrivacyApplicationService {

    public static final String PERMISSION_REQUEST = "customer:privacy:request";
    public static final String PERMISSION_APPROVE = "customer:privacy:approve";
    public static final String PERMISSION_ADMIN_REVEAL = "customer:privacy:admin_reveal";
    public static final String PERMISSION_AUDIT = "customer:privacy:audit";
    public static final String IDEMPOTENCY_SCOPE_CREATE = "customer:privacy:request";
    public static final String MODE_ADMIN_DIRECT = "ADMIN_DIRECT";
    public static final String MODE_APPROVED_GRANT = "APPROVED_GRANT";
    public static final int DEFAULT_DURATION_MINUTES = 30;
    public static final int AUTO_HIDE_SECONDS = 120;
    public static final List<Integer> ALLOWED_DURATION_MINUTES = List.of(15, 30, 120, 1440);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final CustomerPrivacyRepository privacyRepository;
    private final CustomerRepository customerRepository;
    private final CustomerScopeSupport scopeSupport;
    private final CustomerPrivacyFieldSupport privacyFieldSupport;
    private final CustomerPrivacyApproverResolver approverResolver;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final NotificationRequestService notificationRequestService;
    private final ObjectMapper objectMapper;

    public CustomerPrivacyApplicationService(CustomerPrivacyRepository privacyRepository,
                                             CustomerRepository customerRepository,
                                             CustomerScopeSupport scopeSupport,
                                             CustomerPrivacyFieldSupport privacyFieldSupport,
                                             CustomerPrivacyApproverResolver approverResolver,
                                             UserRepository userRepository,
                                             AuditService auditService,
                                             IdempotencyService idempotencyService,
                                             NotificationRequestService notificationRequestService,
                                             ObjectMapper objectMapper) {
        this.privacyRepository = privacyRepository;
        this.customerRepository = customerRepository;
        this.scopeSupport = scopeSupport;
        this.privacyFieldSupport = privacyFieldSupport;
        this.approverResolver = approverResolver;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.notificationRequestService = notificationRequestService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CustomerPrivacyStatusResponse statusForCustomer(long tenantId, CurrentUser user, long customerId) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        Instant now = Instant.now();
        List<CustomerPrivacyStatusResponse.PrivacyFieldSummary> fields = new ArrayList<>();
        for (CustomerPrivacyField field : CustomerPrivacyField.values()) {
            boolean present = privacyFieldSupport.hasValue(customer, field);
            String masked = present ? privacyFieldSupport.maskForCustomer(customer, field) : null;
            fields.add(new CustomerPrivacyStatusResponse.PrivacyFieldSummary(
                    field.name(), field.label(), present, masked));
        }
        boolean canAdminDirectReveal = isEligibleAdminDirectReveal(user);
        boolean canRequestAccess = !user.isInstitutionUser()
                && user.permissions().contains(PERMISSION_REQUEST)
                && !canAdminDirectReveal;
        boolean canViewAuditLogs = !user.isInstitutionUser() && user.permissions().contains(PERMISSION_AUDIT);

        CustomerPrivacyStatusResponse.ResolvedApproverPreview approverPreview = null;
        if (canRequestAccess) {
            approverPreview = approverResolver.resolve(tenantId, user, customer)
                    .map(r -> new CustomerPrivacyStatusResponse.ResolvedApproverPreview(
                            r.userId(), r.displayName(), r.source()))
                    .orElse(null);
        }

        CustomerPrivacyRequestResponse pending = privacyRepository
                .findLatestPendingForApplicantAndCustomer(tenantId, user.userId(), customerId)
                .map(row -> toRequestResponse(row, now))
                .orElse(null);
        CustomerPrivacyRequestResponse activeGrant = privacyRepository
                .findLatestActiveGrantRow(tenantId, user.userId(), customerId, now)
                .map(row -> toRequestResponse(row, now))
                .orElse(null);

        return new CustomerPrivacyStatusResponse(
                customerId,
                fields,
                canAdminDirectReveal,
                canRequestAccess,
                canViewAuditLogs,
                approverPreview,
                pending,
                activeGrant,
                ALLOWED_DURATION_MINUTES);
    }

    @Transactional
    public CustomerPrivacyRequestResponse createRequest(long tenantId, CurrentUser user, long customerId,
                                                        CreatePrivacyRequest request, String idempotencyKey) {
        if (user.isInstitutionUser()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号不可申请查看客户隐私信息");
        }
        List<CustomerPrivacyField> requestedFields = parseAndNormalizeFields(request.fields());
        int durationMinutes = normalizeDurationMinutes(request.durationMinutes(), DEFAULT_DURATION_MINUTES);
        String reason = requireNonBlankReason(request.reason(), "请填写申请查看原因");

        String payload = customerId + "|" + user.userId() + "|" + toCanonicalJson(Map.of(
                "fields", requestedFields.stream().map(Enum::name).toList(),
                "durationMinutes", durationMinutes,
                "reason", reason));
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_CREATE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            long existingId = Long.parseLong(replay.get().getResourceId());
            CustomerPrivacyRepository.RequestRow row = privacyRepository.findRequestRowById(tenantId, existingId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "申请单不存在"));
            return toRequestResponse(row, Instant.now());
        }

        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        for (CustomerPrivacyField field : requestedFields) {
            if (!privacyFieldSupport.hasValue(customer, field)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该客户暂未维护「" + field.label() + "」，无需申请查看");
            }
        }
        if (privacyRepository.existsPendingForApplicantAndCustomer(tenantId, user.userId(), customerId)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "您对该客户已有待审批的隐私查看申请，请等待上级审批或先撤回原申请");
        }

        CustomerPrivacyApproverResolver.ResolvedApprover approver = approverResolver.resolve(tenantId, user, customer)
                .orElseThrow(() -> new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                        "当前未配置可用的上级审批主管，请联系管理员在用户管理中配置直属上级或团队负责人"));

        CustomerPrivacyRequest entity = new CustomerPrivacyRequest();
        entity.setTenantId(tenantId);
        entity.setRequestNo("TMP-" + UUID.randomUUID());
        entity.setCustomerId(customerId);
        entity.setApplicantUserId(user.userId());
        entity.setApplicantTeamId(user.teamId());
        entity.setRequestedFieldsJson(writeJson(requestedFields.stream().map(Enum::name).toList()));
        entity.setReason(reason);
        entity.setStatus(CustomerPrivacyRequest.STATUS_PENDING);
        entity.setAssignedApproverId(approver.userId());
        entity.setApproverSource(approver.source());
        entity.setRequestedDurationMinutes(durationMinutes);
        privacyRepository.insertRequest(entity);

        String requestNo = String.format("PR%010d", entity.getId());
        privacyRepository.backfillRequestNo(tenantId, entity.getId(), requestNo);
        entity.setRequestNo(requestNo);

        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_CREATE, idempotencyKey,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(entity.getId()), payload);

        CustomerPrivacyRepository.RequestRow persisted = privacyRepository.findRequestRowById(tenantId, entity.getId())
                .orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_PRIVACY_REQUEST,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(entity.getId()), null,
                requestAuditSnapshot(persisted), reason);

        notifyIfActive(tenantId, approver.userId(), "CUSTOMER_PRIVACY_REQUEST_PENDING",
                "待审批：客户隐私信息查看申请",
                "员工「" + user.displayName() + "」申请查看客户 " + customer.getCustomerNo()
                        + " 的隐私字段（" + joinFieldLabels(requestedFields) + "），申请时长 "
                        + durationLabel(durationMinutes) + "，请及时审批。",
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(entity.getId()),
                "privacy:req:pending:" + entity.getId(),
                "/customers/privacy-approvals?tab=pending&requestId=" + entity.getId());

        return toRequestResponse(persisted, Instant.now());
    }

    @Transactional
    public CustomerPrivacyRequestResponse cancelRequest(long tenantId, CurrentUser user, long requestId, long version) {
        CustomerPrivacyRequest existing = privacyRepository.findRequestByIdForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "隐私查看申请单不存在"));
        if (!Objects.equals(existing.getApplicantUserId(), user.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅申请人本人可撤回待审批申请");
        }
        if (!Objects.equals(existing.getVersion(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        if (!existing.isPending()) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅待审批状态的申请单可撤回");
        }
        int updated = privacyRepository.cancelRequest(tenantId, requestId, version);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        CustomerPrivacyRepository.RequestRow after = privacyRepository.findRequestRowById(tenantId, requestId)
                .orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_PRIVACY_CANCEL,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                Map.of("status", CustomerPrivacyRequest.STATUS_PENDING),
                requestAuditSnapshot(after), "申请人主动撤回");
        return toRequestResponse(after, Instant.now());
    }

    @Transactional
    public CustomerPrivacyRequestResponse approveRequest(long tenantId, CurrentUser user, long requestId,
                                                         ApprovePrivacyRequest request) {
        CustomerPrivacyRequest existing = privacyRepository.findRequestByIdForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "隐私查看申请单不存在"));
        requireApproverAuthority(tenantId, user, existing, "审批");
        if (!Objects.equals(existing.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        if (!existing.isPending()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该申请单已处理，无法重复审批");
        }

        List<CustomerPrivacyField> requestedFields = parseStoredFields(existing.getRequestedFieldsJson());
        List<CustomerPrivacyField> approvedFields = request.approvedFields() == null || request.approvedFields().isEmpty()
                ? requestedFields
                : parseAndNormalizeFields(request.approvedFields());
        if (!requestedFields.containsAll(approvedFields)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "批准字段必须为申请字段的子集");
        }
        int durationMinutes = normalizeDurationMinutes(
                request.durationMinutes(),
                existing.getRequestedDurationMinutes() == null ? DEFAULT_DURATION_MINUTES
                        : existing.getRequestedDurationMinutes());
        String reviewReason = request.reviewReason() == null || request.reviewReason().isBlank()
                ? "同意按业务需要限时查看" : request.reviewReason().strip();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(durationMinutes));
        int updated = privacyRepository.approveRequest(tenantId, requestId, request.version(),
                writeJson(approvedFields.stream().map(Enum::name).toList()),
                durationMinutes, user.userId(), now, reviewReason, now, expiresAt);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }

        CustomerPrivacyRepository.RequestRow after = privacyRepository.findRequestRowById(tenantId, requestId)
                .orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_PRIVACY_APPROVE,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                Map.of("status", CustomerPrivacyRequest.STATUS_PENDING),
                requestAuditSnapshot(after), reviewReason);

        notifyIfActive(tenantId, existing.getApplicantUserId(), "CUSTOMER_PRIVACY_REQUEST_APPROVED",
                "隐私查看申请已批准",
                "主管「" + user.displayName() + "」已批准您查看客户 " + after.customerNo()
                        + " 的隐私字段（" + joinFieldLabels(approvedFields) + "），有效期 "
                        + durationLabel(durationMinutes) + "，请在客户详情页点击按钮查看。",
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                "privacy:req:approved:" + requestId,
                "/customers/privacy-approvals?tab=my&requestId=" + requestId);

        return toRequestResponse(after, now);
    }

    @Transactional
    public CustomerPrivacyRequestResponse rejectRequest(long tenantId, CurrentUser user, long requestId,
                                                        RejectPrivacyRequest request) {
        CustomerPrivacyRequest existing = privacyRepository.findRequestByIdForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "隐私查看申请单不存在"));
        requireApproverAuthority(tenantId, user, existing, "驳回");
        if (!Objects.equals(existing.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        if (!existing.isPending()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该申请单已处理，无法驳回");
        }
        String reviewReason = requireNonBlankReason(request.reviewReason(), "请填写驳回原因");
        Instant now = Instant.now();
        int updated = privacyRepository.rejectRequest(tenantId, requestId, request.version(),
                user.userId(), now, reviewReason);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        CustomerPrivacyRepository.RequestRow after = privacyRepository.findRequestRowById(tenantId, requestId)
                .orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_PRIVACY_REJECT,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                Map.of("status", CustomerPrivacyRequest.STATUS_PENDING),
                requestAuditSnapshot(after), reviewReason);

        notifyIfActive(tenantId, existing.getApplicantUserId(), "CUSTOMER_PRIVACY_REQUEST_REJECTED",
                "隐私查看申请已驳回",
                "主管「" + user.displayName() + "」已驳回您对客户 " + after.customerNo()
                        + " 的隐私查看申请，原因：" + reviewReason,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                "privacy:req:rejected:" + requestId,
                "/customers/privacy-approvals?tab=my&requestId=" + requestId);

        return toRequestResponse(after, now);
    }

    @Transactional
    public CustomerPrivacyRequestResponse revokeRequest(long tenantId, CurrentUser user, long requestId,
                                                        RevokePrivacyRequest request) {
        CustomerPrivacyRequest existing = privacyRepository.findRequestByIdForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "隐私查看申请单不存在"));
        requireRevokeAuthority(tenantId, user, existing);
        if (!Objects.equals(existing.getVersion(), request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Instant now = Instant.now();
        if (!existing.isActiveGrantAt(now)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该授权已到期或非生效状态，无需撤销");
        }
        String revokeReason = requireNonBlankReason(request.revokeReason(), "请填写撤销授权原因");
        int updated = privacyRepository.revokeRequest(tenantId, requestId, request.version(),
                user.userId(), now, revokeReason);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        CustomerPrivacyRepository.RequestRow after = privacyRepository.findRequestRowById(tenantId, requestId)
                .orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_PRIVACY_REVOKE,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                Map.of("status", CustomerPrivacyRequest.STATUS_APPROVED, "grantExpiresAt", existing.getGrantExpiresAt()),
                requestAuditSnapshot(after), revokeReason);

        notifyIfActive(tenantId, existing.getApplicantUserId(), "CUSTOMER_PRIVACY_REQUEST_REVOKED",
                "隐私查看授权已被提前撤销",
                "您对客户 " + after.customerNo() + " 的限时隐私查看授权已被「" + user.displayName()
                        + "」提前撤销，原因：" + revokeReason,
                "CUSTOMER_PRIVACY_REQUEST", Long.toString(requestId),
                "privacy:req:revoked:" + requestId,
                "/customers/privacy-approvals?tab=my&requestId=" + requestId);

        return toRequestResponse(after, now);
    }

    /**
     * 显式揭盲入口：
     * - ADMIN 通过按钮点击 + 必填原因直接揭盲，写 ADMIN_DIRECT_REVEAL 日志；
     * - 非 ADMIN 必须存在本人、本客户、有效期内且覆盖目标字段的已批准授权单，写 APPROVED_GRANT_REVEAL 日志。
     */
    @Transactional
    public CustomerPrivacyRevealResponse reveal(long tenantId, CurrentUser user, long customerId,
                                                RevealCustomerPrivacyRequest request,
                                                String clientIp, String userAgent) {
        if (user.isInstitutionUser()) {
            recordDeniedReveal(tenantId, user.userId(), customerId, "INSTITUTION_FORBIDDEN", request.reason());
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号无权查看客户隐私明文");
        }
        List<CustomerPrivacyField> requestedFields = parseAndNormalizeFields(request.fields());
        Customer customer;
        try {
            customer = scopeSupport.requireVisible(tenantId, customerId, user);
        } catch (BusinessException ex) {
            recordDeniedReveal(tenantId, user.userId(), customerId, "CUSTOMER_OUT_OF_SCOPE", request.reason());
            throw ex;
        }

        String requestedMode = request.mode() == null || request.mode().isBlank()
                ? null : request.mode().strip().toUpperCase(Locale.ROOT);
        boolean wantsAdminDirect = MODE_ADMIN_DIRECT.equals(requestedMode)
                || (requestedMode == null && request.requestId() == null && isEligibleAdminDirectReveal(user));

        if (wantsAdminDirect) {
            if (!isEligibleAdminDirectReveal(user)) {
                recordDeniedReveal(tenantId, user.userId(), customerId, "ADMIN_DIRECT_FORBIDDEN", request.reason());
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "当前角色不能直接查看客户隐私，请先向上级主管提交查看申请");
            }
            String reason = requireNonBlankReason(request.reason(), "管理员查看隐私信息必须填写查看原因");
            return executeRevealAndAudit(tenantId, user, customer, requestedFields,
                    CustomerPrivacyAccessLog.ACCESS_MODE_ADMIN_DIRECT, null, null, null,
                    reason, clientIp, userAgent, AuditService.ACTION_CUSTOMER_PRIVACY_ADMIN_REVEAL);
        }

        if (!user.permissions().contains(PERMISSION_REQUEST)) {
            recordDeniedReveal(tenantId, user.userId(), customerId, "MISSING_REQUEST_PERMISSION", request.reason());
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权查看客户隐私信息");
        }

        Instant now = Instant.now();
        CustomerPrivacyRequest grant = resolveValidGrant(tenantId, user.userId(), customerId, request.requestId(), now);
        if (grant == null) {
            recordDeniedReveal(tenantId, user.userId(), customerId, "NO_ACTIVE_GRANT", request.reason());
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前没有有效的上级审批授权（未审批、已过期或已撤销），请先向上级主管申请");
        }
        List<CustomerPrivacyField> approvedFields = parseStoredFields(grant.getApprovedFieldsJson());
        if (!approvedFields.containsAll(requestedFields)) {
            recordDeniedReveal(tenantId, user.userId(), customerId, "FIELD_NOT_APPROVED", request.reason());
            throw new BusinessException(ErrorCode.FORBIDDEN, "所选隐私字段超出上级批准范围");
        }
        String effectiveReason = request.reason() != null && !request.reason().isBlank()
                ? request.reason().strip() : grant.getReason();

        privacyRepository.recordRevealHit(tenantId, grant.getId(), now);
        return executeRevealAndAudit(tenantId, user, customer, requestedFields,
                CustomerPrivacyAccessLog.ACCESS_MODE_APPROVED_GRANT, grant.getId(), grant.getRequestNo(),
                grant.getGrantExpiresAt(), effectiveReason, clientIp, userAgent,
                AuditService.ACTION_CUSTOMER_PRIVACY_REVEAL);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerPrivacyRequestResponse> listRequests(long tenantId, CurrentUser user, String view,
                                                                     String status, String keyword, Long customerId,
                                                                     int page, int size) {
        if (user.isInstitutionUser()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号无权访问客户隐私审批模块");
        }
        String viewMode = normalizeViewMode(view, user);
        if (("PENDING_APPROVAL".equals(viewMode) || "APPROVALS".equals(viewMode))
                && !user.permissions().contains(PERMISSION_APPROVE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无审批查看权限");
        }
        if ("MY".equals(viewMode) && !user.permissions().contains(PERMISSION_REQUEST)
                && !user.permissions().contains(PERMISSION_APPROVE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权查看隐私申请记录");
        }
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery scopeQuery = scopeSupport.queryOf(scope, user);
        boolean adminAll = user.roles().contains("ADMIN");
        int offset = (page - 1) * size;
        Instant now = Instant.now();
        String normalizedStatus = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.strip();

        List<CustomerPrivacyRequestResponse> items = privacyRepository
                .searchRequests(tenantId, viewMode, adminAll, scopeQuery.scopeType(), scopeQuery.teamIds(),
                        user.userId(), normalizedStatus, normalizedKeyword, customerId, offset, size)
                .stream()
                .map(row -> toRequestResponse(row, now))
                .toList();
        long total = privacyRepository.countRequests(tenantId, viewMode, adminAll, scopeQuery.scopeType(),
                scopeQuery.teamIds(), user.userId(), normalizedStatus, normalizedKeyword, customerId);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerPrivacyAccessLogResponse> listAccessLogs(long tenantId, CurrentUser user,
                                                                         Long customerId, Long actorUserId,
                                                                         String accessMode, int page, int size) {
        if (user.isInstitutionUser() || !user.permissions().contains(PERMISSION_AUDIT)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权查看客户隐私访问日志");
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        if (customerId != null) {
            scopeSupport.requireVisible(tenantId, customerId, user);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        String normalizedMode = accessMode == null || accessMode.isBlank()
                ? null : accessMode.strip().toUpperCase(Locale.ROOT);
        int offset = (page - 1) * size;

        List<CustomerPrivacyAccessLogResponse> items = privacyRepository
                .searchAccessLogs(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                        customerId, actorUserId, normalizedMode, offset, size)
                .stream()
                .map(this::toAccessLogResponse)
                .toList();
        long total = privacyRepository.countAccessLogs(tenantId, query.scopeType(), query.teamIds(),
                query.selfId(), customerId, actorUserId, normalizedMode);
        return new PageResponse<>(items, total, page, size);
    }

    // ========== 内部辅助 ==========

    private CustomerPrivacyRevealResponse executeRevealAndAudit(long tenantId, CurrentUser user, Customer customer,
                                                                List<CustomerPrivacyField> requestedFields,
                                                                String accessMode, Long requestId, String requestNo,
                                                                Instant grantExpiresAt, String reason,
                                                                String clientIp, String userAgent,
                                                                String auditActionCode) {
        Map<String, String> plaintextValues = new LinkedHashMap<>();
        Map<String, String> maskedSnapshot = new LinkedHashMap<>();
        List<String> fieldCodes = new ArrayList<>();
        for (CustomerPrivacyField field : requestedFields) {
            if (!privacyFieldSupport.hasValue(customer, field)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该客户暂未维护「" + field.label() + "」，无法查看");
            }
            String plaintext = privacyFieldSupport.decryptForAuthorizedReveal(customer, field);
            String masked = privacyFieldSupport.maskForCustomer(customer, field);
            fieldCodes.add(field.name());
            plaintextValues.put(field.name(), plaintext);
            maskedSnapshot.put(field.name(), masked);
        }

        Instant now = Instant.now();
        CustomerPrivacyAccessLog accessLog = new CustomerPrivacyAccessLog();
        accessLog.setTenantId(tenantId);
        accessLog.setCustomerId(customer.getId());
        accessLog.setActorUserId(user.userId());
        accessLog.setActorRoleSnapshot(String.join(",", user.sortedRoles()));
        accessLog.setAccessMode(accessMode);
        accessLog.setRequestId(requestId);
        accessLog.setRevealedFieldsJson(writeJson(fieldCodes));
        accessLog.setMaskedValuesSnapshotJson(writeJson(maskedSnapshot));
        accessLog.setReason(reason);
        accessLog.setClientIp(trimToLength(clientIp, 64));
        accessLog.setUserAgent(trimToLength(userAgent, 255));
        accessLog.setTraceId(trimToLength(MDC.get(RequestIdFilter.MDC_KEY), 96));
        privacyRepository.insertAccessLog(accessLog);

        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("accessLogId", accessLog.getId());
        auditAfter.put("customerId", customer.getId());
        auditAfter.put("customerNo", customer.getCustomerNo());
        auditAfter.put("accessMode", accessMode);
        auditAfter.put("requestId", requestId);
        auditAfter.put("requestNo", requestNo);
        auditAfter.put("revealedFields", fieldCodes);
        auditAfter.put("maskedValuesSnapshot", maskedSnapshot);
        auditService.record(tenantId, user.userId(), auditActionCode, "CUSTOMER",
                Long.toString(customer.getId()), null, auditAfter, reason);

        return new CustomerPrivacyRevealResponse(
                customer.getId(),
                accessMode,
                requestId,
                requestNo,
                fieldCodes,
                plaintextValues,
                now,
                grantExpiresAt,
                AUTO_HIDE_SECONDS,
                accessLog.getId());
    }

    private CustomerPrivacyRequest resolveValidGrant(long tenantId, long userId, long customerId,
                                                     Long requestId, Instant now) {
        if (requestId != null) {
            return privacyRepository.findRequestById(tenantId, requestId)
                    .filter(r -> Objects.equals(r.getApplicantUserId(), userId))
                    .filter(r -> Objects.equals(r.getCustomerId(), customerId))
                    .filter(r -> r.isActiveGrantAt(now))
                    .orElse(null);
        }
        return privacyRepository.findLatestActiveGrant(tenantId, userId, customerId, now).orElse(null);
    }

    private void requireApproverAuthority(long tenantId, CurrentUser actor, CustomerPrivacyRequest req,
                                          String actionVerb) {
        if (Objects.equals(req.getApplicantUserId(), actor.userId())) {
            auditService.recordIndependent(tenantId, actor.userId(), AuditService.ACTION_CUSTOMER_PRIVACY_REVEAL_DENIED,
                    "CUSTOMER_PRIVACY_REQUEST", Long.toString(req.getId()), null,
                    Map.of("reasonCode", "SELF_APPROVAL_FORBIDDEN", "action", actionVerb), "禁止自审本人隐私申请");
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能" + actionVerb + "本人发起的隐私查看申请");
        }
        boolean isAdmin = actor.roles().contains("ADMIN");
        boolean isAssignedApprover = Objects.equals(req.getAssignedApproverId(), actor.userId());
        if (!isAdmin && !isAssignedApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅指定的上级主管或系统管理员可" + actionVerb + "该申请");
        }
        Customer customer = customerRepository.findById(tenantId, req.getCustomerId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标客户不存在或已归档"));
        if (!isAdmin && !scopeSupport.isVisible(scopeSupport.scope(actor), actor, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "隐私查看申请单不存在");
        }
    }

    private void requireRevokeAuthority(long tenantId, CurrentUser actor, CustomerPrivacyRequest req) {
        if (Objects.equals(req.getApplicantUserId(), actor.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能自行撤销本人已获批授权，请联系审批上级或管理员");
        }
        boolean isAdmin = actor.roles().contains("ADMIN");
        boolean isApprover = Objects.equals(req.getAssignedApproverId(), actor.userId())
                || Objects.equals(req.getReviewedBy(), actor.userId());
        if (!isAdmin && !isApprover) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅审批上级或系统管理员可撤销该授权");
        }
    }

    private boolean isEligibleAdminDirectReveal(CurrentUser user) {
        return !user.isInstitutionUser()
                && user.roles().contains("ADMIN")
                && user.permissions().contains(PERMISSION_ADMIN_REVEAL);
    }

    private void recordDeniedReveal(long tenantId, long actorUserId, long customerId,
                                    String reasonCode, String userReason) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("customerId", customerId);
        snapshot.put("reasonCode", reasonCode);
        auditService.recordIndependent(tenantId, actorUserId, AuditService.ACTION_CUSTOMER_PRIVACY_REVEAL_DENIED,
                "CUSTOMER", Long.toString(customerId), null, snapshot,
                userReason == null || userReason.isBlank() ? reasonCode : trimToLength(userReason, 500));
    }

    private void notifyIfActive(long tenantId, Long recipientUserId, String notificationType, String title,
                                String contentMasked, String sourceType, String sourceId,
                                String dedupeKey, String deepLink) {
        if (recipientUserId == null) {
            return;
        }
        boolean active = userRepository.findById(tenantId, recipientUserId)
                .filter(SysUser::isActive)
                .isPresent();
        if (!active) {
            return;
        }
        notificationRequestService.request(new NotificationRequest(
                tenantId,
                recipientUserId,
                notificationType,
                title,
                contentMasked,
                sourceType,
                sourceId,
                dedupeKey,
                deepLink,
                Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP)));
    }

    private List<CustomerPrivacyField> parseAndNormalizeFields(List<String> rawFields) {
        if (rawFields == null || rawFields.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请至少选择一项隐私字段");
        }
        EnumSet<CustomerPrivacyField> unique = EnumSet.noneOf(CustomerPrivacyField.class);
        for (String raw : rawFields) {
            unique.add(CustomerPrivacyField.fromCode(raw));
        }
        return unique.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
    }

    private List<CustomerPrivacyField> parseStoredFields(String json) {
        return parseStringList(json).stream().map(CustomerPrivacyField::fromCode).toList();
    }

    private int normalizeDurationMinutes(Integer requested, int defaultValue) {
        int duration = requested == null ? defaultValue : requested;
        if (!ALLOWED_DURATION_MINUTES.contains(duration)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "授权时长仅支持 15 分钟、30 分钟、2 小时或 24 小时");
        }
        return duration;
    }

    private String requireNonBlankReason(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "原因说明最长 500 字符");
        }
        return trimmed;
    }

    private String normalizeViewMode(String view, CurrentUser user) {
        if (view == null || view.isBlank()) {
            return user.permissions().contains(PERMISSION_APPROVE) ? "PENDING_APPROVAL" : "MY";
        }
        String upper = view.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "MY", "PENDING_APPROVAL", "APPROVALS" -> upper;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的列表视图：" + view);
        };
    }

    private CustomerPrivacyRequestResponse toRequestResponse(CustomerPrivacyRepository.RequestRow row, Instant now) {
        String effectiveStatus = row.status();
        Long remainingSeconds = null;
        if (CustomerPrivacyRequest.STATUS_APPROVED.equals(row.status()) && row.grantExpiresAt() != null) {
            long sec = Duration.between(now, row.grantExpiresAt()).getSeconds();
            if (sec <= 0) {
                effectiveStatus = "EXPIRED";
                remainingSeconds = 0L;
            } else {
                remainingSeconds = sec;
            }
        }
        return new CustomerPrivacyRequestResponse(
                row.id(),
                row.requestNo(),
                row.customerId(),
                row.customerNo(),
                row.customerName(),
                row.applicantUserId(),
                row.applicantName(),
                row.applicantTeamId(),
                parseStringList(row.requestedFieldsJson()),
                parseStringList(row.approvedFieldsJson()),
                row.reason(),
                row.status(),
                effectiveStatus,
                row.assignedApproverId(),
                row.assignedApproverName(),
                row.approverSource(),
                row.requestedDurationMinutes(),
                row.approvedDurationMinutes(),
                row.reviewedBy(),
                row.reviewedByName(),
                row.reviewedAt(),
                row.reviewReason(),
                row.grantStartsAt(),
                row.grantExpiresAt(),
                remainingSeconds,
                row.revokedBy(),
                row.revokedByName(),
                row.revokedAt(),
                row.revokeReason(),
                row.revealCount(),
                row.lastRevealedAt(),
                row.createdAt(),
                row.version());
    }

    private CustomerPrivacyAccessLogResponse toAccessLogResponse(CustomerPrivacyRepository.AccessLogRow row) {
        return new CustomerPrivacyAccessLogResponse(
                row.id(),
                row.customerId(),
                row.customerNo(),
                row.customerName(),
                row.actorUserId(),
                row.actorUsername(),
                row.actorDisplayName(),
                row.actorRoleSnapshot(),
                row.accessMode(),
                row.requestId(),
                row.requestNo(),
                parseStringList(row.revealedFieldsJson()),
                parseStringMap(row.maskedValuesSnapshotJson()),
                row.reason(),
                row.clientIp(),
                row.userAgent(),
                row.traceId(),
                row.createdAt());
    }

    private Map<String, Object> requestAuditSnapshot(CustomerPrivacyRepository.RequestRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", row.id());
        snapshot.put("requestNo", row.requestNo());
        snapshot.put("customerId", row.customerId());
        snapshot.put("customerNo", row.customerNo());
        snapshot.put("applicantUserId", row.applicantUserId());
        snapshot.put("assignedApproverId", row.assignedApproverId());
        snapshot.put("approverSource", row.approverSource());
        snapshot.put("requestedFields", parseStringList(row.requestedFieldsJson()));
        snapshot.put("approvedFields", parseStringList(row.approvedFieldsJson()));
        snapshot.put("status", row.status());
        snapshot.put("requestedDurationMinutes", row.requestedDurationMinutes());
        snapshot.put("approvedDurationMinutes", row.approvedDurationMinutes());
        snapshot.put("grantExpiresAt", row.grantExpiresAt());
        return snapshot;
    }

    private String joinFieldLabels(List<CustomerPrivacyField> fields) {
        return fields.stream().map(CustomerPrivacyField::label).collect(Collectors.joining("、"));
    }

    private String durationLabel(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            return (minutes / 60) + " 小时";
        }
        return minutes + " 分钟";
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("隐私记录序列化失败", ex);
        }
    }

    private String toCanonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
