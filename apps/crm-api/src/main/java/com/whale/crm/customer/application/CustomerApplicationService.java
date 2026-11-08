package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CreateCustomerRequest;
import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.api.dto.CustomerIdentityItemResponse;
import com.whale.crm.customer.api.dto.CustomerListItemResponse;
import com.whale.crm.customer.api.dto.CustomerPointsResponse;
import com.whale.crm.customer.api.dto.OwnershipHistoryItemResponse;
import com.whale.crm.customer.api.dto.PointsAdjustmentRequest;
import com.whale.crm.customer.api.dto.PointsLedgerItemResponse;
import com.whale.crm.customer.api.dto.UpdateCustomerRequest;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.domain.PointsLedgerEntry;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 客户应用服务：CRUD、标签、积分、归属历史。
 * 硬规则：客户唯一 ID ≠ 手机号/昵称；customer_no 服务端生成（C+id 左补零，同事务回填）；
 * 手机号只存密文+盲索引，接口仅返回脱敏；所有读写显式 tenantId + DataScopeService 范围；
 * ownerId 参数只允许收窄；积分只追加流水同事务刷新缓存，余额不得为负。
 */
@Service
public class CustomerApplicationService {

    public static final String IDEMPOTENCY_SCOPE_CREATE = "customer:create";
    public static final String IDEMPOTENCY_SCOPE_POINTS = "customer:points_adjust";
    public static final String POINTS_SOURCE_MANUAL = "MANUAL_ADJUST";

    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final CustomerTagRepository tagRepository;
    private final CustomerOwnerHistoryRepository ownerHistoryRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final MobileSupport mobileSupport;
    private final AuditService auditService;
    private final DictionaryApplicationService dictionaryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final StewardDirectory stewardDirectory;

    public CustomerApplicationService(CustomerRepository customerRepository,
                                      CustomerIdentityRepository identityRepository,
                                      CustomerTagRepository tagRepository,
                                      CustomerOwnerHistoryRepository ownerHistoryRepository,
                                      PointsLedgerRepository pointsLedgerRepository,
                                      UserRepository userRepository,
                                      CustomerScopeSupport scopeSupport,
                                      MobileSupport mobileSupport,
                                      AuditService auditService,
                                      DictionaryApplicationService dictionaryService,
                                      IdempotencyService idempotencyService,
                                      ObjectMapper objectMapper,
                                      StewardDirectory stewardDirectory) {
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.tagRepository = tagRepository;
        this.ownerHistoryRepository = ownerHistoryRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.mobileSupport = mobileSupport;
        this.auditService = auditService;
        this.dictionaryService = dictionaryService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.stewardDirectory = stewardDirectory;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItemResponse> search(long tenantId, CurrentUser user, String keyword,
                                                         String status, String cityCode, String sourceCode,
                                                         String channelCode, String tierCode, Long ownerId,
                                                         Instant followupDueBefore, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        String mobileHashKeyword = mobileSupport.looksLikeMobile(keyword)
                ? mobileSupport.hash(mobileSupport.normalize(keyword)) : null;
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<CustomerListItemResponse> items = customerRepository
                .search(tenantId, query.scopeType(), query.teamIds(), query.selfId(), keyword, mobileHashKeyword,
                        status, cityCode, sourceCode, channelCode, tierCode, ownerId, followupDueBefore, offset, size)
                .stream().map(this::toListItem).toList();
        long total = customerRepository.countSearch(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                keyword, mobileHashKeyword, status, cityCode, sourceCode, channelCode, tierCode, ownerId,
                followupDueBefore);
        return new PageResponse<>(items, total, page, size);
    }

    @Transactional
    public CustomerDetailResponse create(long tenantId, CurrentUser user, CreateCustomerRequest request,
                                         String idempotencyKey) {
        String payload = toCanonicalJson(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_CREATE,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            long originalId = Long.parseLong(replay.get().getResourceId());
            return detail(tenantId, user, originalId);
        }

        boolean hasNickname = request.nickname() != null && !request.nickname().isBlank();
        boolean hasDisplayName = request.displayName() != null && !request.displayName().isBlank();
        if (!hasNickname && !hasDisplayName) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "昵称与客户称呼至少填写一项");
        }
        String mobile = mobileSupport.normalize(request.mobile());
        boolean canDefaultToSelf = user.roles().contains("MANAGER") || user.roles().contains("MANAGER_LEAD");
        if (request.ownerStaffId() == null && !canDefaultToSelf) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择所属管家");
        }
        long ownerId = request.ownerStaffId() != null ? request.ownerStaffId() : user.userId();
        SysUser owner = userRepository.findById(tenantId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "所属管家不存在"));
        if (!stewardDirectory.isEligibleSteward(tenantId, owner)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "所属管家必须为在职管家或管家主管");
        }
        if (!stewardDirectory.canAssignTo(user, owner)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "所属管家超出当前数据范围");
        }
        validateNewCustomerDictionaryOptions(tenantId, request);

        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setOwnerStaffId(owner.getId());
        customer.setTeamId(owner.getTeamId());
        customer.setCustomerNo("TMP-" + UUID.randomUUID());
        customer.setDisplayName(blankToNull(request.displayName()));
        customer.setNickname(blankToNull(request.nickname()));
        customer.setRemarkName(blankToNull(request.remarkName()));
        customer.setMobileCipher(mobileSupport.encrypt(mobile));
        customer.setMobileHash(mobileSupport.hash(mobile));
        customer.setCityCode(blankToNull(request.cityCode()));
        customer.setSourceCode(blankToNull(request.sourceCode()));
        customer.setChannelCode(blankToNull(request.channelCode()));
        customer.setCustomerStatus(blankToNull(request.customerStatus()));
        customer.setTierCode(blankToNull(request.tierCode()));
        customer.setIntentLevel(blankToNull(request.intentLevel()));
        customer.setSpendingLevel(blankToNull(request.spendingLevel()));
        customer.setBirthday(request.birthday());
        customer.setAttentionProjects(blankToNull(request.attentionProjects()));
        customer.setBlockerNote(blankToNull(request.blockerNote()));
        customer.setRemark(blankToNull(request.remark()));
        customer.setFirstAddedAt(request.firstAddedAt() != null ? request.firstAddedAt() : Instant.now());
        customerRepository.insert(customer);
        String customerNo = String.format("C%010d", customer.getId());
        customerRepository.backfillCustomerNo(tenantId, customer.getId(), customerNo);

        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_CREATE, idempotencyKey,
                "CUSTOMER", Long.toString(customer.getId()), payload);
        Customer persisted = customerRepository.findById(tenantId, customer.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_CREATE, "CUSTOMER",
                Long.toString(customer.getId()), null, customerSnapshot(persisted), null);
        return toDetail(persisted, owner.getDisplayName(), List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse detail(long tenantId, CurrentUser user, long customerId) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        return toDetail(customer, ownerNameOf(tenantId, customer),
                tagRepository.findTagCodes(tenantId, customerId),
                identityRepository.findByCustomerId(tenantId, customerId));
    }

    @Transactional
    public CustomerDetailResponse update(long tenantId, CurrentUser user, long customerId,
                                         UpdateCustomerRequest request) {
        Customer existing = scopeSupport.requireVisible(tenantId, customerId, user);
        validateChangedCustomerDictionaryOptions(tenantId, request, existing);
        Map<String, Object> beforeSnapshot = customerSnapshot(existing);

        Customer updated = new Customer();
        updated.setTenantId(tenantId);
        updated.setId(existing.getId());
        updated.setVersion(request.version());
        updated.setDisplayName(request.displayName() != null ? blankToNull(request.displayName()) : existing.getDisplayName());
        updated.setNickname(request.nickname() != null ? blankToNull(request.nickname()) : existing.getNickname());
        updated.setRemarkName(request.remarkName() != null ? blankToNull(request.remarkName()) : existing.getRemarkName());
        if (request.mobile() != null) {
            // 空字符串 = 清除手机号；否则归一化后重新加密+盲索引
            String mobile = request.mobile().isBlank() ? null : mobileSupport.normalize(request.mobile());
            updated.setMobileCipher(mobileSupport.encrypt(mobile));
            updated.setMobileHash(mobileSupport.hash(mobile));
        } else {
            updated.setMobileCipher(existing.getMobileCipher());
            updated.setMobileHash(existing.getMobileHash());
        }
        updated.setCityCode(request.cityCode() != null ? blankToNull(request.cityCode()) : existing.getCityCode());
        updated.setSourceCode(request.sourceCode() != null ? blankToNull(request.sourceCode()) : existing.getSourceCode());
        updated.setChannelCode(request.channelCode() != null ? blankToNull(request.channelCode()) : existing.getChannelCode());
        updated.setCustomerStatus(request.customerStatus() != null ? blankToNull(request.customerStatus()) : existing.getCustomerStatus());
        updated.setTierCode(request.tierCode() != null ? blankToNull(request.tierCode()) : existing.getTierCode());
        updated.setIntentLevel(request.intentLevel() != null ? blankToNull(request.intentLevel()) : existing.getIntentLevel());
        updated.setSpendingLevel(request.spendingLevel() != null ? blankToNull(request.spendingLevel()) : existing.getSpendingLevel());
        updated.setBirthday(request.birthday() != null ? request.birthday() : existing.getBirthday());
        updated.setAttentionProjects(request.attentionProjects() != null ? blankToNull(request.attentionProjects()) : existing.getAttentionProjects());
        updated.setBlockerNote(request.blockerNote() != null ? blankToNull(request.blockerNote()) : existing.getBlockerNote());
        updated.setRemark(request.remark() != null ? blankToNull(request.remark()) : existing.getRemark());
        updated.setFirstAddedAt(existing.getFirstAddedAt());

        if (blankToNull(updated.getNickname()) == null && blankToNull(updated.getDisplayName()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "昵称与客户称呼至少保留一项");
        }
        int rows = customerRepository.updateProfile(updated);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        Customer after = customerRepository.findById(tenantId, customerId).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_UPDATE, "CUSTOMER",
                Long.toString(customerId), beforeSnapshot, customerSnapshot(after), null);
        return toDetail(after, ownerNameOf(tenantId, after),
                tagRepository.findTagCodes(tenantId, customerId),
                identityRepository.findByCustomerId(tenantId, customerId));
    }

    @Transactional
    public CustomerDetailResponse replaceTags(long tenantId, CurrentUser user, long customerId,
                                              List<String> tagCodes) {
        Customer existing = scopeSupport.requireVisible(tenantId, customerId, user);
        List<String> before = tagRepository.findTagCodes(tenantId, customerId);
        LinkedHashSet<String> target = new LinkedHashSet<>();
        for (String code : tagCodes) {
            if (code == null || code.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签代码不能为空");
            }
            target.add(code.strip());
        }
        for (String code : before) {
            if (!target.contains(code)) {
                tagRepository.delete(tenantId, customerId, code);
            }
        }
        for (String code : target) {
            if (!before.contains(code)) {
                tagRepository.insert(tenantId, customerId, code, user.userId());
            }
        }
        List<String> after = tagRepository.findTagCodes(tenantId, customerId);
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_TAGS_UPDATE, "CUSTOMER",
                Long.toString(customerId), Map.of("tagCodes", before), Map.of("tagCodes", after), null);
        return toDetail(existing, ownerNameOf(tenantId, existing), after,
                identityRepository.findByCustomerId(tenantId, customerId));
    }

    @Transactional(readOnly = true)
    public CustomerPointsResponse points(long tenantId, CurrentUser user, long customerId) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        List<PointsLedgerItemResponse> ledger = pointsLedgerRepository.findByCustomerId(tenantId, customerId)
                .stream().map(this::toLedgerItem).toList();
        // 余额以账本为准（points_cached 仅是缓存）
        return new CustomerPointsResponse(pointsLedgerRepository.sumDelta(tenantId, customerId), ledger);
    }

    @Transactional
    public CustomerPointsResponse adjustPoints(long tenantId, CurrentUser user, long customerId,
                                               PointsAdjustmentRequest request, String idempotencyKey) {
        scopeSupport.requireVisibleForUpdate(tenantId, customerId, user);
        if (request.delta() == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "调整分值不能为 0");
        }
        String payload = customerId + "|" + toCanonicalJson(request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, IDEMPOTENCY_SCOPE_POINTS,
                idempotencyKey, payload);
        if (replay.isPresent()) {
            return points(tenantId, user, customerId);
        }

        long before = pointsLedgerRepository.sumDelta(tenantId, customerId);
        long after = before + request.delta();
        if (after < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "积分余额不足，调整后不得为负");
        }
        PointsLedgerEntry entry = new PointsLedgerEntry();
        entry.setTenantId(tenantId);
        entry.setCustomerId(customerId);
        entry.setDelta(request.delta());
        entry.setReasonCode(request.reasonCode());
        entry.setSourceType(POINTS_SOURCE_MANUAL);
        entry.setSourceRef(idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey : "PA-" + UUID.randomUUID());
        entry.setOperatorUserId(user.userId());
        try {
            pointsLedgerRepository.insert(entry);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发同幂等键（source_ref）撞账本唯一键：按冲突处理，提示查询结果而非重复入账
            throw new BusinessException(ErrorCode.CONFLICT, "相同幂等键的调整已入账，请查询积分结果");
        }
        customerRepository.refreshPointsCached(tenantId, customerId, Math.toIntExact(after));

        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_POINTS, idempotencyKey,
                "CUSTOMER_POINTS", Long.toString(customerId), payload);
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_POINTS_ADJUST, "CUSTOMER",
                Long.toString(customerId), Map.of("balance", before),
                Map.of("balance", after, "delta", request.delta(), "reasonCode", request.reasonCode()),
                request.reason());
        return new CustomerPointsResponse(after, pointsLedgerRepository
                .findByCustomerId(tenantId, customerId).stream().map(this::toLedgerItem).toList());
    }

    @Transactional
    public void recordFirstOrder(long tenantId, CurrentUser user, long customerId, Instant paidAt) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        customerRepository.markFirstOrderIfNull(tenantId, customerId, paidAt);
    }

    @Transactional(readOnly = true)
    public List<OwnershipHistoryItemResponse> ownershipHistory(long tenantId, CurrentUser user, long customerId) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        return ownerHistoryRepository.findRowsByCustomerId(tenantId, customerId).stream()
                .map(row -> new OwnershipHistoryItemResponse(row.id(), row.customerId(), row.oldOwnerId(),
                        row.oldOwnerName(), row.newOwnerId(), row.newOwnerName(), row.changedBy(),
                        row.changedByName(), row.reason(), row.effectiveAt(), row.createdAt()))
                .toList();
    }

    // ========== 内部 ==========

    private String ownerNameOf(long tenantId, Customer customer) {
        return userRepository.findById(tenantId, customer.getOwnerStaffId())
                .map(SysUser::getDisplayName).orElse(null);
    }

    CustomerListItemResponse toListItem(CustomerListRow row) {
        return new CustomerListItemResponse(row.getId(), row.getCustomerNo(), row.getDisplayName(),
                row.getNickname(), mobileSupport.maskFromCipher(row.getMobileCipher()), row.getCityCode(),
                row.getCustomerStatus(), row.getTierCode(), row.getIntentLevel(), row.getOwnerStaffId(),
                row.getOwnerName(), row.getTeamId(), row.getPointsCached(), row.getLastFollowupAt(),
                row.getCreatedAt(), row.getVersion(), row.getSourceCode(), row.getChannelCode(), row.getSpendingLevel(),
                row.getBirthday(), row.getTagCodes() == null ? List.of() : List.of(row.getTagCodes().split(",")),
                row.getNextFollowupAt());
    }

    CustomerDetailResponse toDetail(Customer customer, String ownerName, List<String> tagCodes,
                                    List<CustomerIdentity> identities) {
        List<CustomerIdentityItemResponse> identityItems = identities == null ? List.of()
                : identities.stream()
                .map(i -> new CustomerIdentityItemResponse(i.getIdentityType(), i.getSourceApp(),
                        maskExternalId(i.getExternalId()), i.getStatus()))
                .toList();
        return new CustomerDetailResponse(customer.getId(), customer.getCustomerNo(), customer.getDisplayName(),
                customer.getNickname(), mobileSupport.maskFromCipher(customer.getMobileCipher()),
                customer.getCityCode(), customer.getCustomerStatus(), customer.getTierCode(),
                customer.getIntentLevel(), customer.getOwnerStaffId(), ownerName, customer.getTeamId(),
                customer.getPointsCached(), customer.getLastFollowupAt(), customer.getCreatedAt(),
                customer.getVersion(), customer.getSourceCode(), customer.getChannelCode(),
                customer.getSpendingLevel(), customer.getBirthday(), customer.getAttentionProjects(),
                customer.getBlockerNote(), customer.getRemark(), customer.getRemarkName(),
                customer.getFirstAddedAt(), customer.getFirstOrderAt(),
                tagCodes == null ? List.of() : new ArrayList<>(tagCodes), identityItems, customer.isDeleted());
    }

    /**
     * 审计快照（白名单字段）：绝不包含手机号明文/密文/哈希。
     */
    Map<String, Object> customerSnapshot(Customer customer) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", customer.getId());
        snapshot.put("customerNo", customer.getCustomerNo());
        snapshot.put("displayName", customer.getDisplayName());
        snapshot.put("nickname", customer.getNickname());
        snapshot.put("remarkName", customer.getRemarkName());
        snapshot.put("hasMobile", customer.getMobileCipher() != null);
        snapshot.put("mobileMasked", mobileSupport.maskFromCipher(customer.getMobileCipher()));
        snapshot.put("cityCode", customer.getCityCode());
        snapshot.put("sourceCode", customer.getSourceCode());
        snapshot.put("channelCode", customer.getChannelCode());
        snapshot.put("customerStatus", customer.getCustomerStatus());
        snapshot.put("tierCode", customer.getTierCode());
        snapshot.put("intentLevel", customer.getIntentLevel());
        snapshot.put("spendingLevel", customer.getSpendingLevel());
        snapshot.put("ownerStaffId", customer.getOwnerStaffId());
        snapshot.put("teamId", customer.getTeamId());
        snapshot.put("deleted", customer.isDeleted());
        return snapshot;
    }

    static String maskExternalId(String externalId) {
        if (externalId == null || externalId.isEmpty()) {
            return "";
        }
        if (externalId.length() <= 2) {
            return "*".repeat(externalId.length());
        }
        return externalId.charAt(0) + "***" + externalId.charAt(externalId.length() - 1);
    }

    private PointsLedgerItemResponse toLedgerItem(PointsLedgerEntry entry) {
        return new PointsLedgerItemResponse(entry.getId(), entry.getDelta(), entry.getReasonCode(),
                entry.getSourceType(), entry.getSourceRef(), entry.getOperatorUserId(), entry.getCreatedAt());
    }

    private String toCanonicalJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            return String.valueOf(request);
        }
    }

    private void validateNewCustomerDictionaryOptions(long tenantId, CreateCustomerRequest request) {
        requireActiveDictionaryOption(tenantId, "cities", request.cityCode());
        requireActiveDictionaryOption(tenantId, "customerSources", request.sourceCode());
        requireActiveDictionaryOption(tenantId, "customerChannels", request.channelCode());
        requireActiveDictionaryOption(tenantId, "customerStatuses", request.customerStatus());
        requireActiveDictionaryOption(tenantId, "customerTiers", request.tierCode());
    }

    private void validateChangedCustomerDictionaryOptions(long tenantId, UpdateCustomerRequest request,
                                                          Customer existing) {
        validateChangedDictionaryOption(tenantId, "cities", request.cityCode(), existing.getCityCode());
        validateChangedDictionaryOption(tenantId, "customerSources", request.sourceCode(), existing.getSourceCode());
        validateChangedDictionaryOption(tenantId, "customerChannels", request.channelCode(), existing.getChannelCode());
        validateChangedDictionaryOption(tenantId, "customerStatuses", request.customerStatus(),
                existing.getCustomerStatus());
        validateChangedDictionaryOption(tenantId, "customerTiers", request.tierCode(), existing.getTierCode());
    }

    private void validateChangedDictionaryOption(long tenantId, String dictCode, String requestedCode,
                                                 String currentCode) {
        if (requestedCode == null) {
            return;
        }
        String normalized = blankToNull(requestedCode);
        if (!java.util.Objects.equals(normalized, currentCode)) {
            requireActiveDictionaryOption(tenantId, dictCode, normalized);
        }
    }

    private void requireActiveDictionaryOption(long tenantId, String dictCode, String optionCode) {
        String normalized = blankToNull(optionCode);
        if (normalized != null) {
            dictionaryService.requireActiveOption(tenantId, dictCode, normalized);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
