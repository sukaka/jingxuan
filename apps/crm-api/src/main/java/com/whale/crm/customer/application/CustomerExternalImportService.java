package com.whale.crm.customer.application;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 外部渠道（如官方有赞）客户受控建档应用服务。
 * 严格遵循 CRM 客户主档红线：
 * 1. 客户唯一 ID 为服务端生成的 id / customer_no，绝不以手机号或昵称替代；
 * 2. 先按 (identityType, sourceApp, externalId) 强幂等去重；
 * 3. 若未绑定但手机号盲索引命中已有 CRM 活跃客户，绝不覆盖或自动合并，返回 MOBILE_CONFLICT 供人工确认；
 * 4. 手机号仅以 AES-GCM 密文与 HMAC 盲索引落库，审计与返回值仅含脱敏形式；
 * 5. 归属管家必须是在职 MANAGER / MANAGER_LEAD 且在操作人可分配范围内。
 */
@Service
public class CustomerExternalImportService {

    public static final String OUTCOME_CREATED = "CREATED";
    public static final String OUTCOME_SKIPPED_EXISTING = "SKIPPED_EXISTING";
    public static final String OUTCOME_MOBILE_CONFLICT = "MOBILE_CONFLICT";
    public static final String OUTCOME_FAILED = "FAILED";

    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final StewardDirectory stewardDirectory;
    private final MobileSupport mobileSupport;
    private final DictionaryApplicationService dictionaryService;
    private final AuditService auditService;

    public CustomerExternalImportService(CustomerRepository customerRepository,
                                         CustomerIdentityRepository identityRepository,
                                         UserRepository userRepository,
                                         StewardDirectory stewardDirectory,
                                         MobileSupport mobileSupport,
                                         DictionaryApplicationService dictionaryService,
                                         AuditService auditService) {
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.stewardDirectory = stewardDirectory;
        this.mobileSupport = mobileSupport;
        this.dictionaryService = dictionaryService;
        this.auditService = auditService;
    }

    /**
     * 预检目标专属管家及可选来源/渠道字典，避免批量拉取开始后才暴露配置无效。
     */
    @Transactional(readOnly = true)
    public ValidatedImportTarget validateImportTarget(long tenantId, CurrentUser operator,
                                                      long ownerStaffId, String sourceCode, String channelCode) {
        if (operator == null || !"EMPLOYEE".equals(operator.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可执行外部客户导入");
        }
        SysUser owner = userRepository.findById(tenantId, ownerStaffId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "目标专属管家不存在"));
        if (!stewardDirectory.isEligibleSteward(tenantId, owner)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标专属管家必须为在职管家或管家主管");
        }
        if (!stewardDirectory.canAssignTo(operator, owner)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "目标专属管家超出当前操作人数据范围");
        }
        String cleanSource = blankToNull(sourceCode);
        if (cleanSource != null) {
            dictionaryService.requireActiveOption(tenantId, "customerSources", cleanSource);
        }
        String cleanChannel = blankToNull(channelCode);
        if (cleanChannel != null) {
            dictionaryService.requireActiveOption(tenantId, "customerChannels", cleanChannel);
        }
        return new ValidatedImportTarget(owner.getId(), owner.getDisplayName(), owner.getTeamId(),
                cleanSource, cleanChannel);
    }

    /**
     * 若外部映射（如人工客户链接）已指向某个 CRM 客户，核实该客户未归档并补齐 crm_customer_identity。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExistingCustomerRef> ensureIdentityLinked(long tenantId, long customerId,
                                                              String identityType, String sourceApp,
                                                              String externalId) {
        Optional<Customer> existingOpt = customerRepository.findById(tenantId, customerId);
        if (existingOpt.isEmpty() || existingOpt.get().isDeleted()) {
            return Optional.empty();
        }
        Customer customer = existingOpt.get();
        if (identityRepository.findByIdentity(tenantId, identityType, sourceApp, externalId).isEmpty()) {
            try {
                CustomerIdentity identity = new CustomerIdentity();
                identity.setTenantId(tenantId);
                identity.setCustomerId(customer.getId());
                identity.setIdentityType(identityType);
                identity.setSourceApp(sourceApp);
                identity.setExternalId(externalId);
                identity.setVerifiedAt(Instant.now());
                identity.setStatus(CustomerIdentity.STATUS_LINKED);
                identityRepository.insert(identity);
            } catch (DuplicateKeyException ignored) {
                // 并发写入同一外部身份，幂等忽略
            }
        }
        return Optional.of(new ExistingCustomerRef(
                customer.getId(),
                customer.getCustomerNo(),
                firstNonBlank(customer.getDisplayName(), customer.getNickname()),
                mobileSupport.maskFromCipher(customer.getMobileCipher())));
    }

    /**
     * 单条有赞客户建档（独立事务，单条失败绝不影响同批次其他客户）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExternalImportResult importCustomer(long tenantId, CurrentUser operator,
                                               ValidatedImportTarget target,
                                               ExternalCustomerCommand command) {
        String externalId = blankToNull(command.externalId());
        if (externalId == null) {
            return new ExternalImportResult(OUTCOME_FAILED, null, null, null, null,
                    List.of(), List.of(), "外部客户标识为空，无法建档");
        }

        // 1. 强幂等：已存在 (identityType, sourceApp, externalId) → 直接跳过
        Optional<CustomerIdentity> existingIdentity = identityRepository.findByIdentity(
                tenantId, command.identityType(), command.sourceApp(), externalId);
        if (existingIdentity.isPresent()) {
            Optional<Customer> existingCustomer = customerRepository.findById(
                    tenantId, existingIdentity.get().getCustomerId());
            if (existingCustomer.isPresent() && !existingCustomer.get().isDeleted()) {
                Customer c = existingCustomer.get();
                return new ExternalImportResult(
                        OUTCOME_SKIPPED_EXISTING,
                        c.getId(),
                        c.getCustomerNo(),
                        firstNonBlank(c.getDisplayName(), c.getNickname()),
                        mobileSupport.maskFromCipher(c.getMobileCipher()),
                        List.of(),
                        List.of(),
                        "该有赞客户已绑定 CRM 档案 " + c.getCustomerNo() + "，自动跳过");
            }
            return new ExternalImportResult(OUTCOME_FAILED, null, null,
                    safeDisplayName(command.displayName(), command.nickname(), externalId), null,
                    List.of(), List.of(), "该有赞身份已关联的 CRM 客户已归档，请人工核查");
        }

        // 2. 手机号归一化与盲索引冲突检查（绝不自动覆盖老客户）
        String normalizedMobile = tryNormalizeMobile(command.mobile());
        String mobileHash = normalizedMobile == null ? null : mobileSupport.hash(normalizedMobile);
        String mobileMasked = normalizedMobile == null ? null : MobileSupport.mask(normalizedMobile);
        String displayName = safeDisplayName(command.displayName(), command.nickname(), externalId);
        String nickname = truncate(blankToNull(command.nickname()), 128);

        if (mobileHash != null) {
            List<Customer> matched = customerRepository.findActiveByMobileHashes(tenantId, List.of(mobileHash));
            if (!matched.isEmpty()) {
                List<Long> conflictIds = matched.stream().map(Customer::getId).toList();
                List<String> conflictNos = matched.stream().map(Customer::getCustomerNo).toList();
                return new ExternalImportResult(
                        OUTCOME_MOBILE_CONFLICT,
                        null,
                        null,
                        displayName,
                        mobileMasked,
                        conflictIds,
                        conflictNos,
                        "手机号命中已有 CRM 客户（" + String.join("、", conflictNos) + "），已跳过建档，请人工确认关联");
            }
        }

        // 3. 创建正式 CRM 客户 + 回填 customer_no + 绑定 crm_customer_identity
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setOwnerStaffId(target.ownerStaffId());
        customer.setTeamId(target.teamId());
        customer.setCustomerNo("TMP-" + UUID.randomUUID());
        customer.setDisplayName(displayName);
        customer.setNickname(nickname != null ? nickname : displayName);
        customer.setMobileCipher(mobileSupport.encrypt(normalizedMobile));
        customer.setMobileHash(mobileHash);
        customer.setSourceCode(target.sourceCode());
        customer.setChannelCode(target.channelCode());
        customer.setBirthday(command.birthday());
        customer.setRemark(truncate(blankToNull(command.remark()), 500));
        customer.setFirstAddedAt(command.firstAddedAt() != null ? command.firstAddedAt() : Instant.now());

        customerRepository.insert(customer);
        String customerNo = String.format("C%010d", customer.getId());
        customerRepository.backfillCustomerNo(tenantId, customer.getId(), customerNo);

        CustomerIdentity identity = new CustomerIdentity();
        identity.setTenantId(tenantId);
        identity.setCustomerId(customer.getId());
        identity.setIdentityType(command.identityType());
        identity.setSourceApp(command.sourceApp());
        identity.setExternalId(externalId);
        identity.setVerifiedAt(Instant.now());
        identity.setStatus(CustomerIdentity.STATUS_LINKED);
        try {
            identityRepository.insert(identity);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "并发导入冲突：该外部客户标识已被其他任务建档");
        }

        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("id", customer.getId());
        auditAfter.put("customerNo", customerNo);
        auditAfter.put("displayName", displayName);
        auditAfter.put("ownerStaffId", target.ownerStaffId());
        auditAfter.put("teamId", target.teamId());
        auditAfter.put("hasMobile", normalizedMobile != null);
        auditAfter.put("mobileMasked", mobileMasked);
        auditAfter.put("identityType", command.identityType());
        auditAfter.put("sourceApp", command.sourceApp());
        auditService.record(tenantId, operator.userId(), AuditService.ACTION_CUSTOMER_CREATE, "CUSTOMER",
                Long.toString(customer.getId()), null, auditAfter, command.importReason());

        return new ExternalImportResult(
                OUTCOME_CREATED,
                customer.getId(),
                customerNo,
                displayName,
                mobileMasked,
                List.of(),
                List.of(),
                "新建 CRM 客户 " + customerNo + " 并绑定有赞身份");
    }

    private String tryNormalizeMobile(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mobileSupport.normalize(raw);
        } catch (BusinessException ex) {
            return null;
        }
    }

    private static String safeDisplayName(String displayName, String nickname, String externalId) {
        String primary = meaningful(displayName);
        if (primary != null) {
            return truncate(primary, 96);
        }
        String secondary = meaningful(nickname);
        if (secondary != null) {
            return truncate(secondary, 96);
        }
        return "有赞客户 " + maskExternalId(externalId);
    }

    private static String meaningful(String raw) {
        String clean = blankToNull(raw);
        if (clean == null || "微信用户".equals(clean) || "有赞用户".equals(clean)) {
            return null;
        }
        return clean;
    }

    private static String maskExternalId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "****";
        }
        String clean = raw.trim();
        if (clean.length() <= 6) {
            return clean.substring(0, Math.min(2, clean.length())) + "***";
        }
        return clean.substring(0, 3) + "***" + clean.substring(clean.length() - 3);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    public record ValidatedImportTarget(
            long ownerStaffId,
            String ownerDisplayName,
            Long teamId,
            String sourceCode,
            String channelCode
    ) {
    }

    public record ExistingCustomerRef(
            long customerId,
            String customerNo,
            String displayName,
            String mobileMasked
    ) {
    }

    public record ExternalCustomerCommand(
            String identityType,
            String sourceApp,
            String externalId,
            String displayName,
            String nickname,
            String mobile,
            LocalDate birthday,
            Instant firstAddedAt,
            String remark,
            String importReason
    ) {
    }

    public record ExternalImportResult(
            String outcome,
            Long crmCustomerId,
            String crmCustomerNo,
            String displayNameMasked,
            String mobileMasked,
            List<Long> conflictCustomerIds,
            List<String> conflictCustomerNos,
            String detailMasked
    ) {
        public ExternalImportResult {
            conflictCustomerIds = conflictCustomerIds == null ? List.of() : List.copyOf(conflictCustomerIds);
            conflictCustomerNos = conflictCustomerNos == null ? List.of() : List.copyOf(conflictCustomerNos);
        }
    }
}
