package com.whale.crm.customer.application;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 外部渠道（如官方有赞）客户受控建档应用服务。
 * 严格遵循 CRM 客户主档红线：
 * 1. 客户唯一 ID 为服务端生成的 id / customer_no，绝不以手机号或昵称替代；
 * 2. 先按 (identityType, sourceApp, externalId) 强幂等去重；
 * 3. 若未绑定但手机号盲索引命中已有 CRM 活跃客户，绝不覆盖或自动合并，返回 MOBILE_CONFLICT 供人工确认；
 * 4. 手机号仅以 AES-GCM 密文与 HMAC 盲索引落库，审计与返回值仅含脱敏形式；
 * 5. 归属管家必须是在职 MANAGER / MANAGER_LEAD 且在操作人可分配范围内；
 * 6. 城市与标签严格受字典治理约束：仅写入 ACTIVE 城市编码与 ACTIVE 标签编码，停用标签不自动启用，老客仅补缺不覆盖。
 */
@Service
public class CustomerExternalImportService {

    public static final String OUTCOME_CREATED = "CREATED";
    public static final String OUTCOME_SKIPPED_EXISTING = "SKIPPED_EXISTING";
    public static final String OUTCOME_MOBILE_CONFLICT = "MOBILE_CONFLICT";
    public static final String OUTCOME_FAILED = "FAILED";
    private static final String DICT_CITIES = "cities";
    private static final int MAX_TAG_LABEL_LEN = 64;
    private static final int MAX_TAGS_PER_IMPORT = 30;

    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final CustomerTagRepository tagRepository;
    private final UserRepository userRepository;
    private final StewardDirectory stewardDirectory;
    private final MobileSupport mobileSupport;
    private final DictionaryApplicationService dictionaryService;
    private final AuditService auditService;

    public CustomerExternalImportService(CustomerRepository customerRepository,
                                         CustomerIdentityRepository identityRepository,
                                         CustomerTagRepository tagRepository,
                                         UserRepository userRepository,
                                         StewardDirectory stewardDirectory,
                                         MobileSupport mobileSupport,
                                         DictionaryApplicationService dictionaryService,
                                         AuditService auditService) {
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.tagRepository = tagRepository;
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

        // 3. 创建正式 CRM 客户 + 回填 customer_no + 绑定 crm_customer_identity + 写入已治理标签
        String resolvedCityCode = resolveCityCode(tenantId, command.cityName());
        List<String> validTagCodes = filterActiveTagCodes(tenantId, command.tagCodes());

        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setOwnerStaffId(target.ownerStaffId());
        customer.setTeamId(target.teamId());
        customer.setCustomerNo("TMP-" + UUID.randomUUID());
        customer.setDisplayName(displayName);
        customer.setNickname(nickname != null ? nickname : displayName);
        customer.setMobileCipher(mobileSupport.encrypt(normalizedMobile));
        customer.setMobileHash(mobileHash);
        customer.setCityCode(resolvedCityCode);
        customer.setSourceCode(target.sourceCode());
        customer.setChannelCode(target.channelCode());
        customer.setBirthday(command.birthday());
        customer.setRemark(truncate(blankToNull(command.remark()), 500));
        customer.setFirstAddedAt(command.firstAddedAt() != null ? command.firstAddedAt() : Instant.now());

        customerRepository.insert(customer);
        String customerNo = String.format("C%010d", customer.getId());
        customerRepository.backfillCustomerNo(tenantId, customer.getId(), customerNo);

        int appliedTags = 0;
        for (String tagCode : validTagCodes) {
            if (tagRepository.insertIfAbsent(tenantId, customer.getId(), tagCode, operator.userId()) > 0) {
                appliedTags++;
            }
        }

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
        auditAfter.put("cityCode", resolvedCityCode);
        auditAfter.put("hasRemark", customer.getRemark() != null);
        auditAfter.put("tagCount", appliedTags);
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

    /**
     * 将有赞原标签名称映射到 CRM customerTags 字典编码：
     * - 已有 ACTIVE 标签直接复用；
     * - 已有 INACTIVE/ARCHIVED 标签尊重停用状态跳过，绝不自动重新启用；
     * - 缺失标签仅在当前操作人具备 customer:tag:manage 时自动创建 ACTIVE 字典项（带独立事务防回滚污染）。
     */
    public List<String> resolveOrCreateTagCodes(long tenantId, CurrentUser operator, List<String> rawTagNames) {
        if (rawTagNames == null || rawTagNames.isEmpty()) {
            return List.of();
        }
        List<String> cleanedLabels = normalizeTagLabels(rawTagNames);
        if (cleanedLabels.isEmpty()) {
            return List.of();
        }
        Map<String, DictionaryItemResponse> byLabel = loadTagDictionaryByLabel(tenantId);
        boolean canCreateTag = operator != null && operator.permissions().contains("customer:tag:manage");
        Set<String> resolved = new LinkedHashSet<>();
        for (String label : cleanedLabels) {
            String key = label.toLowerCase(Locale.ROOT);
            DictionaryItemResponse existing = byLabel.get(key);
            if (existing != null) {
                if ("ACTIVE".equals(existing.status())) {
                    resolved.add(existing.optionCode());
                }
                continue;
            }
            if (!canCreateTag) {
                continue;
            }
            String generatedCode = generateYouzanTagCode(label);
            try {
                DictionaryItemResponse created = dictionaryService.createItemForType(
                        tenantId, operator.userId(), CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE,
                        generatedCode, label, 0, true);
                byLabel.put(key, created);
                if ("ACTIVE".equals(created.status())) {
                    resolved.add(created.optionCode());
                }
            } catch (BusinessException ex) {
                // 并发创建冲突或字典异常时回查一次最新字典，绝不中断客户建档
                Map<String, DictionaryItemResponse> reloaded = loadTagDictionaryByLabel(tenantId);
                byLabel.putAll(reloaded);
                DictionaryItemResponse concurrent = byLabel.get(key);
                if (concurrent != null && "ACTIVE".equals(concurrent.status())) {
                    resolved.add(concurrent.optionCode());
                }
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * 已绑定有赞客户的非破坏性空缺资料补全（独立事务）：
     * - 仅当 CRM 原字段为空时才补全城市、备注、生日，绝不覆盖运营已手工维护的任何值；
     * - 标签仅做增量 insertIfAbsent 追加，绝不删除客户已有标签；
     * - 绝不修改姓名、昵称、手机号、归属管家等核心字段。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BackfillProfileResult backfillMissingProfile(long tenantId, CurrentUser operator, long customerId,
                                                        BackfillProfileCommand command) {
        if (command == null) {
            return BackfillProfileResult.unchanged();
        }
        Optional<Customer> existingOpt = customerRepository.findByIdForUpdate(tenantId, customerId);
        if (existingOpt.isEmpty() || existingOpt.get().isDeleted()) {
            return BackfillProfileResult.unchanged();
        }
        Customer customer = existingOpt.get();
        List<String> updatedFields = new ArrayList<>();
        Map<String, Object> beforeSnapshot = new LinkedHashMap<>();
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        boolean profileDirty = false;

        if (blankToNull(customer.getCityCode()) == null && blankToNull(command.cityName()) != null) {
            String resolvedCity = resolveCityCode(tenantId, command.cityName());
            if (resolvedCity != null) {
                beforeSnapshot.put("cityCode", null);
                afterSnapshot.put("cityCode", resolvedCity);
                customer.setCityCode(resolvedCity);
                updatedFields.add("城市");
                profileDirty = true;
            }
        }
        String candidateRemark = truncate(blankToNull(command.remark()), 500);
        if (blankToNull(customer.getRemark()) == null && candidateRemark != null) {
            beforeSnapshot.put("hasRemark", false);
            afterSnapshot.put("hasRemark", true);
            customer.setRemark(candidateRemark);
            updatedFields.add("备注");
            profileDirty = true;
        }
        if (customer.getBirthday() == null && command.birthday() != null) {
            beforeSnapshot.put("birthday", null);
            afterSnapshot.put("birthday", command.birthday().toString());
            customer.setBirthday(command.birthday());
            updatedFields.add("生日");
            profileDirty = true;
        }

        if (profileDirty) {
            customerRepository.updateProfile(customer);
        }

        List<String> validTagCodes = filterActiveTagCodes(tenantId, command.tagCodes());
        List<String> addedTagCodes = new ArrayList<>();
        for (String tagCode : validTagCodes) {
            if (tagRepository.insertIfAbsent(tenantId, customerId, tagCode, operator.userId()) > 0) {
                addedTagCodes.add(tagCode);
            }
        }
        if (!addedTagCodes.isEmpty()) {
            afterSnapshot.put("addedTagCodes", addedTagCodes);
            updatedFields.add("标签(" + addedTagCodes.size() + ")");
        }

        if (profileDirty || !addedTagCodes.isEmpty()) {
            auditService.record(tenantId, operator.userId(), AuditService.ACTION_CUSTOMER_UPDATE, "CUSTOMER",
                    Long.toString(customerId), beforeSnapshot, afterSnapshot,
                    firstNonBlank(command.reason(), "有赞客户同步补全空缺资料"));
            return new BackfillProfileResult(true, List.copyOf(updatedFields), addedTagCodes.size());
        }
        return BackfillProfileResult.unchanged();
    }

    /**
     * 按当前租户启用中的 cities 字典匹配城市名称（支持精确匹配、去“市/自治州”后缀及省市组合文本），
     * 未命中返回 null，严禁写入字典外脏编码。
     */
    public String resolveCityCode(long tenantId, String rawCityName) {
        String clean = blankToNull(rawCityName);
        if (clean == null) {
            return null;
        }
        List<DictionaryOptionResponse> activeCities = dictionaryService.listActiveOptions(tenantId, DICT_CITIES);
        if (activeCities == null || activeCities.isEmpty()) {
            return null;
        }
        String normalizedInput = stripCitySuffix(clean);
        for (DictionaryOptionResponse opt : activeCities) {
            if (opt == null || blankToNull(opt.optionCode()) == null || blankToNull(opt.optionLabel()) == null) {
                continue;
            }
            if (clean.equalsIgnoreCase(opt.optionCode().strip())
                    || clean.equals(opt.optionLabel().strip())
                    || normalizedInput.equals(stripCitySuffix(opt.optionLabel().strip()))) {
                return opt.optionCode().strip();
            }
        }
        List<DictionaryOptionResponse> sortedByLabelLen = activeCities.stream()
                .filter(opt -> opt != null && blankToNull(opt.optionCode()) != null && blankToNull(opt.optionLabel()) != null)
                .sorted(Comparator.comparingInt((DictionaryOptionResponse o) -> stripCitySuffix(o.optionLabel().strip()).length())
                        .reversed())
                .toList();
        for (DictionaryOptionResponse opt : sortedByLabelLen) {
            String normalizedLabel = stripCitySuffix(opt.optionLabel().strip());
            if (normalizedLabel.length() >= 2 && clean.contains(normalizedLabel)) {
                return opt.optionCode().strip();
            }
        }
        return null;
    }

    private List<String> filterActiveTagCodes(long tenantId, List<String> candidateCodes) {
        if (candidateCodes == null || candidateCodes.isEmpty()) {
            return List.of();
        }
        List<DictionaryOptionResponse> activeOptions = dictionaryService.listActiveOptions(
                tenantId, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE);
        if (activeOptions == null || activeOptions.isEmpty()) {
            return List.of();
        }
        Set<String> activeSet = new LinkedHashSet<>();
        for (DictionaryOptionResponse opt : activeOptions) {
            if (opt != null && blankToNull(opt.optionCode()) != null) {
                activeSet.add(opt.optionCode().strip());
            }
        }
        Set<String> valid = new LinkedHashSet<>();
        for (String code : candidateCodes) {
            String clean = blankToNull(code);
            if (clean != null && activeSet.contains(clean)) {
                valid.add(clean);
            }
        }
        return List.copyOf(valid);
    }

    private Map<String, DictionaryItemResponse> loadTagDictionaryByLabel(long tenantId) {
        Map<String, DictionaryItemResponse> map = new HashMap<>();
        try {
            List<DictionaryItemResponse> items = dictionaryService.listItemsByDictCode(
                    tenantId, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE);
            if (items != null) {
                for (DictionaryItemResponse item : items) {
                    if (item != null && blankToNull(item.optionLabel()) != null) {
                        map.putIfAbsent(item.optionLabel().strip().toLowerCase(Locale.ROOT), item);
                    }
                }
            }
        } catch (BusinessException ignored) {
            // 租户尚未初始化 customerTags 字典时安全跳过
        }
        return map;
    }

    private static List<String> normalizeTagLabels(List<String> rawTagNames) {
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : rawTagNames) {
            if (unique.size() >= MAX_TAGS_PER_IMPORT) {
                break;
            }
            String clean = blankToNull(raw);
            if (clean == null) {
                continue;
            }
            if (clean.length() >= 8 && clean.startsWith("$") && clean.endsWith("$")) {
                continue;
            }
            unique.add(truncate(clean, MAX_TAG_LABEL_LEN));
        }
        return List.copyOf(unique);
    }

    private static String generateYouzanTagCode(String label) {
        String digest = IdempotencyService.sha256("youzan:tag:" + label.toLowerCase(Locale.ROOT));
        return "YZ_TAG_" + digest.substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static String stripCitySuffix(String raw) {
        String text = raw == null ? "" : raw.strip();
        for (String suffix : List.of("特别行政区", "自治州", "地区", "市", "盟")) {
            if (text.length() > suffix.length() && text.endsWith(suffix)) {
                return text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
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
            String importReason,
            String cityName,
            List<String> tagCodes
    ) {
        public ExternalCustomerCommand {
            tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
        }

        public ExternalCustomerCommand(
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
            this(identityType, sourceApp, externalId, displayName, nickname, mobile, birthday,
                    firstAddedAt, remark, importReason, null, List.of());
        }
    }

    public record BackfillProfileCommand(
            String cityName,
            String remark,
            LocalDate birthday,
            List<String> tagCodes,
            String reason
    ) {
        public BackfillProfileCommand {
            tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
        }
    }

    public record BackfillProfileResult(
            boolean updated,
            List<String> updatedFields,
            int addedTagCount
    ) {
        public BackfillProfileResult {
            updatedFields = updatedFields == null ? List.of() : List.copyOf(updatedFields);
        }

        public static BackfillProfileResult unchanged() {
            return new BackfillProfileResult(false, List.of(), 0);
        }
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
