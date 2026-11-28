package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CreateCustomerRequest;
import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.api.dto.CustomerIdentityItemResponse;
import com.whale.crm.customer.api.dto.CustomerListItemResponse;
import com.whale.crm.customer.api.dto.CustomerPointsResponse;
import com.whale.crm.customer.api.dto.CustomerStewardRelationItemResponse;
import com.whale.crm.customer.api.dto.CustomerStewardSummaryResponse;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.api.dto.OwnershipHistoryItemResponse;
import com.whale.crm.customer.api.dto.PointsAdjustmentRequest;
import com.whale.crm.customer.api.dto.PointsLedgerItemResponse;
import com.whale.crm.customer.api.dto.UpdateCustomerRequest;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerAttentionProjectRepository;
import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.domain.PointsLedgerEntry;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.customer.tier.application.CustomerTierReviewService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.region.application.RegionCatalogService;
import com.whale.crm.platform.region.application.ResolvedRegion;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    public static final String TAG_MATCH_MODE_ANY = "ANY";
    public static final String TAG_MATCH_MODE_ALL = "ALL";
    private static final int MAX_FILTER_TAG_CODES = 20;
    private static final int MAX_TAG_CODE_LENGTH = 64;
    private static final Logger log = LoggerFactory.getLogger(CustomerApplicationService.class);

    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final CustomerTagRepository tagRepository;
    private final CustomerOwnerHistoryRepository ownerHistoryRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final MobileSupport mobileSupport;
    private final CustomerPrivacyFieldSupport privacyFieldSupport;
    private final AuditService auditService;
    private final DictionaryApplicationService dictionaryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final StewardDirectory stewardDirectory;
    private final CustomerTierReviewService tierReviewService;
    private final CustomerAttentionProjectRepository attentionProjectRepository;
    private final CustomerProjectSupport projectSupport;
    private final CustomerUniquenessService uniquenessService;
    private final ObjectProvider<CustomerTagSyncPort> tagSyncPortProvider;
    private final RegionCatalogService regionCatalogService;

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
                                      StewardDirectory stewardDirectory,
                                      CustomerTierReviewService tierReviewService,
                                      CustomerAttentionProjectRepository attentionProjectRepository,
                                      CustomerProjectSupport projectSupport) {
        this(customerRepository, identityRepository, tagRepository, ownerHistoryRepository, pointsLedgerRepository,
                userRepository, scopeSupport, mobileSupport, auditService, dictionaryService, idempotencyService,
                objectMapper, stewardDirectory, tierReviewService, attentionProjectRepository, projectSupport,
                null, null);
    }

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
                                      StewardDirectory stewardDirectory,
                                      CustomerTierReviewService tierReviewService,
                                      CustomerAttentionProjectRepository attentionProjectRepository,
                                      CustomerProjectSupport projectSupport,
                                      CustomerUniquenessService uniquenessService) {
        this(customerRepository, identityRepository, tagRepository, ownerHistoryRepository, pointsLedgerRepository,
                userRepository, scopeSupport, mobileSupport, auditService, dictionaryService, idempotencyService,
                objectMapper, stewardDirectory, tierReviewService, attentionProjectRepository, projectSupport,
                uniquenessService, null);
    }

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
                                      StewardDirectory stewardDirectory,
                                      CustomerTierReviewService tierReviewService,
                                      CustomerAttentionProjectRepository attentionProjectRepository,
                                      CustomerProjectSupport projectSupport,
                                      CustomerUniquenessService uniquenessService,
                                      ObjectProvider<CustomerTagSyncPort> tagSyncPortProvider) {
        this(customerRepository, identityRepository, tagRepository, ownerHistoryRepository, pointsLedgerRepository,
                userRepository, scopeSupport, mobileSupport, null, auditService, dictionaryService, idempotencyService,
                objectMapper, stewardDirectory, tierReviewService, attentionProjectRepository, projectSupport,
                uniquenessService, tagSyncPortProvider);
    }

    public CustomerApplicationService(CustomerRepository customerRepository,
                                      CustomerIdentityRepository identityRepository,
                                      CustomerTagRepository tagRepository,
                                      CustomerOwnerHistoryRepository ownerHistoryRepository,
                                      PointsLedgerRepository pointsLedgerRepository,
                                      UserRepository userRepository,
                                      CustomerScopeSupport scopeSupport,
                                      MobileSupport mobileSupport,
                                      CustomerPrivacyFieldSupport privacyFieldSupport,
                                      AuditService auditService,
                                      DictionaryApplicationService dictionaryService,
                                      IdempotencyService idempotencyService,
                                      ObjectMapper objectMapper,
                                      StewardDirectory stewardDirectory,
                                      CustomerTierReviewService tierReviewService,
                                      CustomerAttentionProjectRepository attentionProjectRepository,
                                      CustomerProjectSupport projectSupport,
                                      CustomerUniquenessService uniquenessService,
                                      ObjectProvider<CustomerTagSyncPort> tagSyncPortProvider) {
        this(customerRepository, identityRepository, tagRepository, ownerHistoryRepository, pointsLedgerRepository,
                userRepository, scopeSupport, mobileSupport, privacyFieldSupport, auditService, dictionaryService,
                idempotencyService, objectMapper, stewardDirectory, tierReviewService, attentionProjectRepository,
                projectSupport, uniquenessService, tagSyncPortProvider, new RegionCatalogService());
    }

    @Autowired
    public CustomerApplicationService(CustomerRepository customerRepository,
                                      CustomerIdentityRepository identityRepository,
                                      CustomerTagRepository tagRepository,
                                      CustomerOwnerHistoryRepository ownerHistoryRepository,
                                      PointsLedgerRepository pointsLedgerRepository,
                                      UserRepository userRepository,
                                      CustomerScopeSupport scopeSupport,
                                      MobileSupport mobileSupport,
                                      CustomerPrivacyFieldSupport privacyFieldSupport,
                                      AuditService auditService,
                                      DictionaryApplicationService dictionaryService,
                                      IdempotencyService idempotencyService,
                                      ObjectMapper objectMapper,
                                      StewardDirectory stewardDirectory,
                                      CustomerTierReviewService tierReviewService,
                                      CustomerAttentionProjectRepository attentionProjectRepository,
                                      CustomerProjectSupport projectSupport,
                                      CustomerUniquenessService uniquenessService,
                                      ObjectProvider<CustomerTagSyncPort> tagSyncPortProvider,
                                      RegionCatalogService regionCatalogService) {
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.tagRepository = tagRepository;
        this.ownerHistoryRepository = ownerHistoryRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.mobileSupport = mobileSupport;
        this.privacyFieldSupport = privacyFieldSupport;
        this.auditService = auditService;
        this.dictionaryService = dictionaryService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.stewardDirectory = stewardDirectory;
        this.tierReviewService = tierReviewService;
        this.attentionProjectRepository = attentionProjectRepository;
        this.projectSupport = projectSupport;
        this.uniquenessService = uniquenessService;
        this.tagSyncPortProvider = tagSyncPortProvider;
        this.regionCatalogService = regionCatalogService != null ? regionCatalogService : new RegionCatalogService();
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItemResponse> search(long tenantId, CurrentUser user, String keyword,
                                                         String status, String cityCode, String sourceCode,
                                                         String channelCode, String tierCode, Long ownerId,
                                                         Instant followupDueBefore, int page, int size) {
        return search(tenantId, user, keyword, status, cityCode, sourceCode, channelCode, tierCode, ownerId,
                followupDueBefore, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItemResponse> search(long tenantId, CurrentUser user, String keyword,
                                                         String status, String cityCode, String sourceCode,
                                                         String channelCode, String tierCode, Long ownerId,
                                                         Instant followupDueBefore, List<String> tagCodes,
                                                         String tagMatchMode, int page, int size) {
        return search(tenantId, user, keyword, status, cityCode, null, null, null, sourceCode, channelCode, tierCode,
                ownerId, followupDueBefore, tagCodes, tagMatchMode, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItemResponse> search(long tenantId, CurrentUser user, String keyword,
                                                         String status, String cityCode,
                                                         String provinceCode, String regionCityCode,
                                                         String districtCode, String sourceCode,
                                                         String channelCode, String tierCode, Long ownerId,
                                                         Instant followupDueBefore, List<String> tagCodes,
                                                         String tagMatchMode, int page, int size) {
        List<String> normalizedTagCodes = normalizeFilterTagCodes(tagCodes);
        String normalizedTagMatchMode = normalizeTagMatchMode(tagMatchMode);
        String cleanProvinceCode = blankToNull(provinceCode);
        String cleanRegionCityCode = blankToNull(regionCityCode);
        String cleanDistrictCode = blankToNull(districtCode);
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerPrivacyFieldSupport.PrivacySearchHashes hashes = privacyFieldSupport != null
                ? privacyFieldSupport.extractSearchHashes(keyword)
                : new CustomerPrivacyFieldSupport.PrivacySearchHashes(
                        mobileSupport.looksLikeMobile(keyword) ? mobileSupport.hash(mobileSupport.normalize(keyword)) : null,
                        null, null, null);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        List<CustomerListRow> rows = customerRepository.search(tenantId, query.scopeType(), query.teamIds(),
                query.selfId(), keyword, hashes.mobileHash(), hashes.idCardHash(), hashes.emailHash(),
                hashes.wechatIdHash(), status, cityCode, cleanProvinceCode, cleanRegionCityCode, cleanDistrictCode,
                sourceCode, channelCode, tierCode,
                ownerId, followupDueBefore, normalizedTagCodes, normalizedTagMatchMode, offset, size);
        Map<Long, CustomerStewardSummaryResponse> stewardSummaries = Map.of();
        if (uniquenessService != null && uniquenessService.canView(user) && !rows.isEmpty()) {
            List<Long> rowIds = rows.stream().map(CustomerListRow::getId).toList();
            List<Customer> pageCustomers = customerRepository.findActiveByIds(tenantId, rowIds);
            stewardSummaries = uniquenessService.summarizeForCustomers(tenantId, user, pageCustomers);
        }
        Map<Long, CustomerStewardSummaryResponse> finalSummaries = stewardSummaries;
        List<CustomerListItemResponse> items = rows.stream()
                .map(row -> toListItem(row, finalSummaries.get(row.getId())))
                .toList();
        long total = customerRepository.countSearch(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                keyword, hashes.mobileHash(), hashes.idCardHash(), hashes.emailHash(), hashes.wechatIdHash(),
                status, cityCode, cleanProvinceCode, cleanRegionCityCode, cleanDistrictCode,
                sourceCode, channelCode, tierCode, ownerId,
                followupDueBefore, normalizedTagCodes, normalizedTagMatchMode);
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

        if (request.tierCode() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "客户等级只能通过自动分级或专用人工调整接口设置");
        }
        boolean hasNickname = request.nickname() != null && !request.nickname().isBlank();
        boolean hasDisplayName = request.displayName() != null && !request.displayName().isBlank();
        if (!hasNickname && !hasDisplayName) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "昵称与客户称呼至少填写一项");
        }
        String mobile = privacyFieldSupport != null
                ? privacyFieldSupport.normalizeMobile(request.mobile())
                : mobileSupport.normalize(request.mobile());
        String idCard = privacyFieldSupport != null ? privacyFieldSupport.normalizeIdCard(request.idCard()) : null;
        String email = privacyFieldSupport != null ? privacyFieldSupport.normalizeEmail(request.email()) : null;
        String wechatId = privacyFieldSupport != null ? privacyFieldSupport.normalizeWechatId(request.wechatId()) : null;
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
        List<Long> projectIds = normalizeProjectIds(request.projectIds());
        projectSupport.requireExisting(tenantId, projectIds);
        List<String> tagCodes = normalizeTagCodes(request.tagCodes());
        for (String code : tagCodes) {
            dictionaryService.requireActiveOption(tenantId, "customerTags", code);
        }

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
        if (privacyFieldSupport != null) {
            customer.setIdCardCipher(privacyFieldSupport.encrypt(idCard));
            customer.setIdCardHash(privacyFieldSupport.hashIdCard(idCard));
            customer.setEmailCipher(privacyFieldSupport.encrypt(email));
            customer.setEmailHash(privacyFieldSupport.hashEmail(email));
            customer.setWechatIdCipher(privacyFieldSupport.encrypt(wechatId));
            customer.setWechatIdHash(privacyFieldSupport.hashWechatId(wechatId));
        }
        applyRegionForCreate(tenantId, customer, request);
        customer.setDetailAddress(blankToNull(request.detailAddress()));
        customer.setSourceCode(blankToNull(request.sourceCode()));
        customer.setChannelCode(blankToNull(request.channelCode()));
        customer.setCustomerStatus(blankToNull(request.customerStatus()));
        customer.setTierCode(null);
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
        for (Long projectId : projectIds) {
            attentionProjectRepository.insert(tenantId, customer.getId(), projectId, user.userId());
        }
        for (String code : tagCodes) {
            tagRepository.insert(tenantId, customer.getId(), code, user.userId());
        }
        List<String> persistedTags = tagRepository.findTagCodes(tenantId, customer.getId());

        idempotencyService.recordSuccess(tenantId, IDEMPOTENCY_SCOPE_CREATE, idempotencyKey,
                "CUSTOMER", Long.toString(customer.getId()), payload);
        Customer persisted = customerRepository.findById(tenantId, customer.getId()).orElseThrow();
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_CREATE, "CUSTOMER",
                Long.toString(customer.getId()), null, customerSnapshot(persisted), null);
        if (!persistedTags.isEmpty()) {
            notifyTagSync(tenantId, customer.getId(), List.of(), persistedTags, user.userId(), "CRM_WRITE");
        }
        return toDetail(persisted, owner.getDisplayName(), persistedTags, List.of());
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse detail(long tenantId, CurrentUser user, long customerId) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        CustomerStewardSummaryResponse stewardSummary = null;
        if (uniquenessService != null && uniquenessService.canView(user) && !customer.isDeleted()) {
            stewardSummary = uniquenessService.summarizeForCustomers(tenantId, user, List.of(customer)).get(customerId);
        }
        return toDetail(customer, ownerNameOf(tenantId, customer),
                tagRepository.findTagCodes(tenantId, customerId),
                identityRepository.findByCustomerId(tenantId, customerId),
                stewardSummary);
    }

    @Transactional
    public CustomerDetailResponse update(long tenantId, CurrentUser user, long customerId,
                                         UpdateCustomerRequest request) {
        Customer existing = scopeSupport.requireVisible(tenantId, customerId, user);
        if (request.tierCode() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "客户等级只能通过自动分级或专用人工调整接口设置");
        }
        if (request.attentionProjects() != null && !java.util.Objects.equals(
                blankToNull(request.attentionProjects()), existing.getAttentionProjects())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "历史关注项目文本仅作只读保留，请通过标准关注项目列表维护");
        }
        validateChangedCustomerDictionaryOptions(tenantId, request, existing);
        List<String> beforeTags = null;
        List<String> targetTags = null;
        if (request.tagCodes() != null) {
            beforeTags = tagRepository.findTagCodes(tenantId, customerId);
            targetTags = normalizeTagCodes(request.tagCodes());
            for (String code : targetTags) {
                if (!beforeTags.contains(code)) {
                    dictionaryService.requireActiveOption(tenantId, "customerTags", code);
                }
            }
        }
        Map<String, Object> beforeSnapshot = customerSnapshot(existing);

        Customer updated = new Customer();
        updated.setTenantId(tenantId);
        updated.setId(existing.getId());
        updated.setVersion(request.version());
        updated.setDisplayName(request.displayName() != null ? blankToNull(request.displayName()) : existing.getDisplayName());
        updated.setNickname(request.nickname() != null ? blankToNull(request.nickname()) : existing.getNickname());
        updated.setRemarkName(request.remarkName() != null ? blankToNull(request.remarkName()) : existing.getRemarkName());
        if (request.mobile() != null) {
            // 空字符串 = 清除手机号；否则归一化后重新加密+盲索引（拒绝含 * 脱敏占位符）
            String mobile = request.mobile().isBlank() ? null
                    : (privacyFieldSupport != null ? privacyFieldSupport.normalizeMobile(request.mobile())
                            : mobileSupport.normalize(request.mobile()));
            updated.setMobileCipher(mobileSupport.encrypt(mobile));
            updated.setMobileHash(mobileSupport.hash(mobile));
        } else {
            updated.setMobileCipher(existing.getMobileCipher());
            updated.setMobileHash(existing.getMobileHash());
        }
        if (request.idCard() != null && privacyFieldSupport != null) {
            String idCard = request.idCard().isBlank() ? null : privacyFieldSupport.normalizeIdCard(request.idCard());
            updated.setIdCardCipher(privacyFieldSupport.encrypt(idCard));
            updated.setIdCardHash(privacyFieldSupport.hashIdCard(idCard));
        } else {
            updated.setIdCardCipher(existing.getIdCardCipher());
            updated.setIdCardHash(existing.getIdCardHash());
        }
        if (request.email() != null && privacyFieldSupport != null) {
            String email = request.email().isBlank() ? null : privacyFieldSupport.normalizeEmail(request.email());
            updated.setEmailCipher(privacyFieldSupport.encrypt(email));
            updated.setEmailHash(privacyFieldSupport.hashEmail(email));
        } else {
            updated.setEmailCipher(existing.getEmailCipher());
            updated.setEmailHash(existing.getEmailHash());
        }
        if (request.wechatId() != null && privacyFieldSupport != null) {
            String wechatId = request.wechatId().isBlank() ? null
                    : privacyFieldSupport.normalizeWechatId(request.wechatId());
            updated.setWechatIdCipher(privacyFieldSupport.encrypt(wechatId));
            updated.setWechatIdHash(privacyFieldSupport.hashWechatId(wechatId));
        } else {
            updated.setWechatIdCipher(existing.getWechatIdCipher());
            updated.setWechatIdHash(existing.getWechatIdHash());
        }
        applyRegionForUpdate(tenantId, updated, existing, request);
        updated.setDetailAddress(request.detailAddress() != null
                ? blankToNull(request.detailAddress()) : existing.getDetailAddress());
        updated.setSourceCode(request.sourceCode() != null ? blankToNull(request.sourceCode()) : existing.getSourceCode());
        updated.setChannelCode(request.channelCode() != null ? blankToNull(request.channelCode()) : existing.getChannelCode());
        updated.setCustomerStatus(request.customerStatus() != null ? blankToNull(request.customerStatus()) : existing.getCustomerStatus());
        updated.setTierCode(existing.getTierCode());
        updated.setIntentLevel(request.intentLevel() != null ? blankToNull(request.intentLevel()) : existing.getIntentLevel());
        updated.setSpendingLevel(request.spendingLevel() != null ? blankToNull(request.spendingLevel()) : existing.getSpendingLevel());
        updated.setBirthday(request.birthday() != null ? request.birthday() : existing.getBirthday());
        updated.setAttentionProjects(existing.getAttentionProjects());
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
        if (request.projectIds() != null) {
            List<Long> projectIds = normalizeProjectIds(request.projectIds());
            projectSupport.requireExisting(tenantId, projectIds);
            attentionProjectRepository.deleteByCustomerId(tenantId, customerId);
            for (Long projectId : projectIds) {
                attentionProjectRepository.insert(tenantId, customerId, projectId, user.userId());
            }
        }
        List<String> afterTags;
        if (targetTags != null && beforeTags != null) {
            for (String code : beforeTags) {
                if (!targetTags.contains(code)) {
                    tagRepository.delete(tenantId, customerId, code);
                }
            }
            for (String code : targetTags) {
                if (!beforeTags.contains(code)) {
                    tagRepository.insert(tenantId, customerId, code, user.userId());
                }
            }
            afterTags = tagRepository.findTagCodes(tenantId, customerId);
            if (!beforeTags.equals(afterTags)) {
                auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_TAGS_UPDATE, "CUSTOMER",
                        Long.toString(customerId), Map.of("tagCodes", beforeTags), Map.of("tagCodes", afterTags),
                        null);
                notifyTagSync(tenantId, customerId, beforeTags, afterTags, user.userId(), "CRM_WRITE");
            }
        } else {
            afterTags = tagRepository.findTagCodes(tenantId, customerId);
        }
        Customer after = customerRepository.findById(tenantId, customerId).orElseThrow();
        tierReviewService.markCustomerChanged(tenantId, customerId, user.userId());
        auditService.record(tenantId, user.userId(), AuditService.ACTION_CUSTOMER_UPDATE, "CUSTOMER",
                Long.toString(customerId), beforeSnapshot, customerSnapshot(after), null);
        return toDetail(after, ownerNameOf(tenantId, after), afterTags,
                identityRepository.findByCustomerId(tenantId, customerId));
    }

    @Transactional
    public CustomerDetailResponse replaceTags(long tenantId, CurrentUser user, long customerId,
                                              List<String> tagCodes) {
        Customer existing = scopeSupport.requireVisible(tenantId, customerId, user);
        List<String> before = tagRepository.findTagCodes(tenantId, customerId);
        List<String> target = normalizeTagCodes(tagCodes);
        for (String code : target) {
            if (!before.contains(code)) {
                dictionaryService.requireActiveOption(tenantId, "customerTags", code);
            }
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
        if (!before.equals(after)) {
            notifyTagSync(tenantId, customerId, before, after, user.userId(), "CRM_WRITE");
        }
        return toDetail(existing, ownerNameOf(tenantId, existing), after,
                identityRepository.findByCustomerId(tenantId, customerId));
    }

    @Transactional(readOnly = true)
    public List<String> getCustomerTagCodesInternal(long tenantId, long customerId) {
        return tagRepository.findTagCodes(tenantId, customerId);
    }

    /**
     * 批量过滤当前用户在 CUSTOMER 数据范围内可见的客户 ID（供跨模块应用服务执行行级隔离，严禁跨模块偷查 Mapper）。
     */
    @Transactional(readOnly = true)
    public Set<Long> filterVisibleCustomerIds(long tenantId, CurrentUser user, Collection<Long> customerIds) {
        if (user == null || customerIds == null || customerIds.isEmpty()) {
            return Set.of();
        }
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return Set.of();
        }
        List<Long> distinctIds = customerIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Set.of();
        }
        if (scope.type() == DataScope.ScopeType.ALL) {
            return new LinkedHashSet<>(distinctIds);
        }
        Set<Long> visible = new LinkedHashSet<>();
        final int batchSize = 500;
        for (int start = 0; start < distinctIds.size(); start += batchSize) {
            List<Long> slice = distinctIds.subList(start, Math.min(start + batchSize, distinctIds.size()));
            for (Customer customer : customerRepository.findActiveByIds(tenantId, slice)) {
                if (!customer.isDeleted() && scopeSupport.isVisible(scope, user, customer)) {
                    visible.add(customer.getId());
                }
            }
        }
        return visible;
    }

    /**
     * 企微 → CRM 标签回流同步：仅更新已纳入企微映射管理的标签子集（managedTagCodes），
     * 完整保留未映射的 CRM 本地标签；写入 WECOM_CUSTOMER_TAG_PULL 审计且绝不反向触发 CustomerTagSyncPort，彻底杜绝回声循环。
     */
    @Transactional
    public WecomTagPullOutcome syncTagsFromWecom(long tenantId, long customerId, Set<String> managedTagCodes,
                                                 Set<String> wecomTargetTagCodes, Long actorUserId, String reason) {
        if (managedTagCodes == null || managedTagCodes.isEmpty()) {
            return new WecomTagPullOutcome(false, List.of(), List.of());
        }
        Optional<Customer> customerOpt = customerRepository.findById(tenantId, customerId);
        if (customerOpt.isEmpty() || customerOpt.get().isDeleted()) {
            return new WecomTagPullOutcome(false, List.of(), List.of());
        }
        Set<String> activeCatalogCodes = new LinkedHashSet<>();
        dictionaryService.listActiveOptions(tenantId, CustomerTagAdminService.CUSTOMER_TAGS_DICT_CODE)
                .forEach(opt -> activeCatalogCodes.add(opt.optionCode()));
        Set<String> effectiveManaged = new LinkedHashSet<>();
        for (String code : managedTagCodes) {
            if (code != null && activeCatalogCodes.contains(code)) {
                effectiveManaged.add(code);
            }
        }
        if (effectiveManaged.isEmpty()) {
            return new WecomTagPullOutcome(false, List.of(), List.of());
        }
        List<String> before = tagRepository.findTagCodes(tenantId, customerId);
        Set<String> targetManaged = new LinkedHashSet<>();
        if (wecomTargetTagCodes != null) {
            for (String code : wecomTargetTagCodes) {
                if (code != null && effectiveManaged.contains(code)) {
                    targetManaged.add(code);
                }
            }
        }
        for (String existingCode : before) {
            if (effectiveManaged.contains(existingCode) && !targetManaged.contains(existingCode)) {
                tagRepository.delete(tenantId, customerId, existingCode);
            }
        }
        for (String targetCode : targetManaged) {
            if (!before.contains(targetCode)) {
                tagRepository.insertIfAbsent(tenantId, customerId, targetCode, actorUserId);
            }
        }
        List<String> after = tagRepository.findTagCodes(tenantId, customerId);
        boolean changed = !before.equals(after);
        if (changed) {
            auditService.record(tenantId, actorUserId, AuditService.ACTION_WECOM_CUSTOMER_TAG_PULL, "CUSTOMER",
                    Long.toString(customerId), Map.of("tagCodes", before), Map.of("tagCodes", after), reason);
        }
        return new WecomTagPullOutcome(changed, before, after);
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
                        row.changedByName(), row.reason(), row.approvalRef(),
                        CustomerAssignmentApplicationService.resolveChangeMode(row.approvalRef()),
                        row.effectiveAt(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerTimelineItemResponse> timelineOwnership(long tenantId, CurrentUser user, long customerId,
                                                                 CustomerTimelineCursor cursor, int limit) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        int safeLimit = Math.max(1, limit);
        CustomerTimelineCursor.SqlCursor sqlCursor = CustomerTimelineCursor.sqlCursor(cursor, "OWNERSHIP");
        return ownerHistoryRepository.findTimelineRowsByCustomerId(tenantId, customerId,
                        sqlCursor.modeName(), sqlCursor.occurredAt(), sqlCursor.sourceId(), safeLimit).stream()
                .map(row -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("oldOwnerId", row.oldOwnerId());
                    details.put("oldOwnerName", row.oldOwnerName());
                    details.put("newOwnerId", row.newOwnerId());
                    details.put("newOwnerName", row.newOwnerName());
                    Instant occurredAt = row.effectiveAt() != null ? row.effectiveAt() : row.createdAt();
                    return CustomerTimelineItemResponse.of("OWNERSHIP", row.id(), "OWNERSHIP", occurredAt,
                            "客户归属变更", row.reason(), details, row.changedBy());
                })
                .filter(item -> cursor == null || cursor.allows(item))
                .limit(safeLimit)
                .toList();
    }

    // ========== 内部 ==========

    private String ownerNameOf(long tenantId, Customer customer) {
        return userRepository.findById(tenantId, customer.getOwnerStaffId())
                .map(SysUser::getDisplayName).orElse(null);
    }

    CustomerListItemResponse toListItem(CustomerListRow row) {
        return toListItem(row, null);
    }

    CustomerListItemResponse toListItem(CustomerListRow row, CustomerStewardSummaryResponse stewardSummary) {
        Integer addedStewardCount = stewardSummary != null ? stewardSummary.stewardCount() : null;
        List<String> addedStewardNames = stewardSummary != null
                ? stewardSummary.stewards().stream().map(CustomerStewardRelationItemResponse::staffName).toList()
                : null;
        String regionText = RegionCatalogService.formatRegionText(
                row.getProvinceName(), row.getRegionCityName(), row.getDistrictName());
        return new CustomerListItemResponse(row.getId(), row.getCustomerNo(), row.getDisplayName(),
                row.getNickname(), mobileSupport.maskFromCipher(row.getMobileCipher()),
                privacyFieldSupport == null ? null : privacyFieldSupport.maskIdCardFromCipher(row.getIdCardCipher()),
                privacyFieldSupport == null ? null : privacyFieldSupport.maskEmailFromCipher(row.getEmailCipher()),
                privacyFieldSupport == null ? null : privacyFieldSupport.maskWechatIdFromCipher(row.getWechatIdCipher()),
                row.getCityCode(), row.getProvinceCode(), row.getProvinceName(), row.getRegionCityCode(),
                row.getRegionCityName(), row.getDistrictCode(), row.getDistrictName(), regionText,
                row.getDetailAddress(),
                row.getCustomerStatus(), row.getTierCode(), row.getIntentLevel(), row.getOwnerStaffId(),
                row.getOwnerName(), row.getTeamId(), row.getPointsCached(), row.getLastFollowupAt(),
                row.getCreatedAt(), row.getVersion(), row.getSourceCode(), row.getChannelCode(), row.getSpendingLevel(),
                row.getBirthday(), row.getTagCodes(),
                row.getNextFollowupAt(), addedStewardCount, addedStewardNames);
    }

    CustomerDetailResponse toDetail(Customer customer, String ownerName, List<String> tagCodes,
                                    List<CustomerIdentity> identities) {
        return toDetail(customer, ownerName, tagCodes, identities, null);
    }

    CustomerDetailResponse toDetail(Customer customer, String ownerName, List<String> tagCodes,
                                    List<CustomerIdentity> identities,
                                    CustomerStewardSummaryResponse stewardSummary) {
        List<CustomerIdentityItemResponse> identityItems = identities == null ? List.of()
                : identities.stream()
                .map(i -> new CustomerIdentityItemResponse(i.getIdentityType(), i.getSourceApp(),
                        maskExternalId(i.getExternalId()), i.getStatus()))
                .toList();
        Integer addedStewardCount = stewardSummary != null ? stewardSummary.stewardCount() : null;
        List<CustomerStewardRelationItemResponse> addedStewards = stewardSummary != null
                ? stewardSummary.stewards() : null;
        String regionText = RegionCatalogService.formatRegionText(
                customer.getProvinceName(), customer.getRegionCityName(), customer.getDistrictName());
        return new CustomerDetailResponse(customer.getId(), customer.getCustomerNo(), customer.getDisplayName(),
                customer.getNickname(), mobileSupport.maskFromCipher(customer.getMobileCipher()),
                privacyFieldSupport == null ? null : privacyFieldSupport.maskIdCardFromCipher(customer.getIdCardCipher()),
                privacyFieldSupport == null ? null : privacyFieldSupport.maskEmailFromCipher(customer.getEmailCipher()),
                privacyFieldSupport == null ? null : privacyFieldSupport.maskWechatIdFromCipher(customer.getWechatIdCipher()),
                customer.getCityCode(), customer.getProvinceCode(), customer.getProvinceName(),
                customer.getRegionCityCode(), customer.getRegionCityName(), customer.getDistrictCode(),
                customer.getDistrictName(), regionText, customer.getDetailAddress(),
                customer.getCustomerStatus(), customer.getTierCode(),
                customer.getIntentLevel(), customer.getOwnerStaffId(), ownerName, customer.getTeamId(),
                customer.getPointsCached(), customer.getLastFollowupAt(), customer.getCreatedAt(),
                customer.getVersion(), customer.getSourceCode(), customer.getChannelCode(),
                customer.getSpendingLevel(), customer.getBirthday(), customer.getAttentionProjects(),
                customer.getBlockerNote(), customer.getRemark(), customer.getRemarkName(),
                customer.getFirstAddedAt(), customer.getFirstOrderAt(),
                tagCodes == null ? List.of() : new ArrayList<>(tagCodes), identityItems, customer.isDeleted(),
                attentionProjectRepository.findProjectIds(customer.getTenantId(), customer.getId()),
                addedStewardCount, addedStewards);
    }

    /**
     * 审计快照（白名单字段）：绝不包含手机号、身份证号、邮箱、微信号明文/密文/哈希。
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
        snapshot.put("hasIdCard", customer.getIdCardCipher() != null);
        snapshot.put("idCardMasked", privacyFieldSupport == null ? null
                : privacyFieldSupport.maskIdCardFromCipher(customer.getIdCardCipher()));
        snapshot.put("hasEmail", customer.getEmailCipher() != null);
        snapshot.put("emailMasked", privacyFieldSupport == null ? null
                : privacyFieldSupport.maskEmailFromCipher(customer.getEmailCipher()));
        snapshot.put("hasWechatId", customer.getWechatIdCipher() != null);
        snapshot.put("wechatIdMasked", privacyFieldSupport == null ? null
                : privacyFieldSupport.maskWechatIdFromCipher(customer.getWechatIdCipher()));
        snapshot.put("cityCode", customer.getCityCode());
        snapshot.put("provinceCode", customer.getProvinceCode());
        snapshot.put("provinceName", customer.getProvinceName());
        snapshot.put("regionCityCode", customer.getRegionCityCode());
        snapshot.put("regionCityName", customer.getRegionCityName());
        snapshot.put("districtCode", customer.getDistrictCode());
        snapshot.put("districtName", customer.getDistrictName());
        snapshot.put("hasDetailAddress", customer.getDetailAddress() != null);
        snapshot.put("sourceCode", customer.getSourceCode());
        snapshot.put("channelCode", customer.getChannelCode());
        snapshot.put("customerStatus", customer.getCustomerStatus());
        snapshot.put("tierCode", customer.getTierCode());
        snapshot.put("intentLevel", customer.getIntentLevel());
        snapshot.put("spendingLevel", customer.getSpendingLevel());
        snapshot.put("ownerStaffId", customer.getOwnerStaffId());
        snapshot.put("teamId", customer.getTeamId());
        snapshot.put("legacyAttentionProjects", customer.getAttentionProjects());
        snapshot.put("attentionProjectIds",
                attentionProjectRepository.findProjectIds(customer.getTenantId(), customer.getId()));
        snapshot.put("tagCodes", tagRepository.findTagCodes(customer.getTenantId(), customer.getId()));
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

    private void applyRegionForCreate(long tenantId, Customer customer, CreateCustomerRequest request) {
        boolean hasStructured = blankToNull(request.provinceCode()) != null
                || blankToNull(request.regionCityCode()) != null
                || blankToNull(request.districtCode()) != null;
        if (hasStructured) {
            ResolvedRegion resolved = regionCatalogService.resolveAndValidate(
                    request.provinceCode(), request.regionCityCode(), request.districtCode());
            applyResolvedRegion(customer, resolved);
            String explicitCityCode = blankToNull(request.cityCode());
            if (explicitCityCode != null) {
                customer.setCityCode(explicitCityCode);
            } else {
                customer.setCityCode(matchActiveCityCode(tenantId, resolved));
            }
            return;
        }
        String cityCode = blankToNull(request.cityCode());
        customer.setCityCode(cityCode);
        if (cityCode != null) {
            ResolvedRegion derived = resolveRegionFromCityDictionary(tenantId, cityCode);
            applyResolvedRegion(customer, derived);
        }
    }

    private void applyRegionForUpdate(long tenantId, Customer updated, Customer existing,
                                      UpdateCustomerRequest request) {
        boolean structuredSent = request.provinceCode() != null
                || request.regionCityCode() != null
                || request.districtCode() != null;
        if (structuredSent) {
            boolean allBlank = blankToNull(request.provinceCode()) == null
                    && blankToNull(request.regionCityCode()) == null
                    && blankToNull(request.districtCode()) == null;
            if (allBlank) {
                applyResolvedRegion(updated, ResolvedRegion.EMPTY);
                updated.setCityCode(request.cityCode() != null ? blankToNull(request.cityCode()) : null);
                return;
            }
            ResolvedRegion resolved = regionCatalogService.resolveAndValidate(
                    request.provinceCode(), request.regionCityCode(), request.districtCode());
            applyResolvedRegion(updated, resolved);
            if (request.cityCode() != null) {
                updated.setCityCode(blankToNull(request.cityCode()));
            } else {
                updated.setCityCode(matchActiveCityCode(tenantId, resolved));
            }
            return;
        }
        if (request.cityCode() != null) {
            String nextCityCode = blankToNull(request.cityCode());
            if (java.util.Objects.equals(nextCityCode, existing.getCityCode())) {
                copyRegion(existing, updated);
                updated.setCityCode(existing.getCityCode());
                return;
            }
            updated.setCityCode(nextCityCode);
            if (nextCityCode == null) {
                applyResolvedRegion(updated, ResolvedRegion.EMPTY);
            } else {
                ResolvedRegion derived = resolveRegionFromCityDictionary(tenantId, nextCityCode);
                applyResolvedRegion(updated, derived);
            }
            return;
        }
        copyRegion(existing, updated);
        updated.setCityCode(existing.getCityCode());
    }

    private void applyResolvedRegion(Customer target, ResolvedRegion resolved) {
        target.setProvinceCode(resolved.provinceCode());
        target.setProvinceName(resolved.provinceName());
        target.setRegionCityCode(resolved.regionCityCode());
        target.setRegionCityName(resolved.regionCityName());
        target.setDistrictCode(resolved.districtCode());
        target.setDistrictName(resolved.districtName());
    }

    private void copyRegion(Customer source, Customer target) {
        target.setProvinceCode(source.getProvinceCode());
        target.setProvinceName(source.getProvinceName());
        target.setRegionCityCode(source.getRegionCityCode());
        target.setRegionCityName(source.getRegionCityName());
        target.setDistrictCode(source.getDistrictCode());
        target.setDistrictName(source.getDistrictName());
    }

    private String matchActiveCityCode(long tenantId, ResolvedRegion resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return null;
        }
        List<DictionaryOptionResponse> activeCities = dictionaryService.listActiveOptions(tenantId, "cities");
        return regionCatalogService.matchActiveCityDictionaryCode(resolved, activeCities);
    }

    private ResolvedRegion resolveRegionFromCityDictionary(long tenantId, String cityCode) {
        List<DictionaryOptionResponse> activeCities = dictionaryService.listActiveOptions(tenantId, "cities");
        if (activeCities == null || activeCities.isEmpty()) {
            return ResolvedRegion.EMPTY;
        }
        for (DictionaryOptionResponse option : activeCities) {
            if (option != null && cityCode.equals(option.optionCode()) && option.optionLabel() != null) {
                return regionCatalogService.resolveByNames(null, option.optionLabel(), null);
            }
        }
        return ResolvedRegion.EMPTY;
    }

    private void validateNewCustomerDictionaryOptions(long tenantId, CreateCustomerRequest request) {
        requireActiveDictionaryOption(tenantId, "cities", request.cityCode());
        requireActiveDictionaryOption(tenantId, "customerSources", request.sourceCode());
        requireActiveDictionaryOption(tenantId, "customerChannels", request.channelCode());
        requireActiveDictionaryOption(tenantId, "customerStatuses", request.customerStatus());
    }

    private void validateChangedCustomerDictionaryOptions(long tenantId, UpdateCustomerRequest request,
                                                          Customer existing) {
        validateChangedDictionaryOption(tenantId, "cities", request.cityCode(), existing.getCityCode());
        validateChangedDictionaryOption(tenantId, "customerSources", request.sourceCode(), existing.getSourceCode());
        validateChangedDictionaryOption(tenantId, "customerChannels", request.channelCode(), existing.getChannelCode());
        validateChangedDictionaryOption(tenantId, "customerStatuses", request.customerStatus(),
                existing.getCustomerStatus());
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

    private List<String> normalizeFilterTagCodes(List<String> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String raw : input) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String part : raw.split(",")) {
                String trimmed = part.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.length() > MAX_TAG_CODE_LENGTH) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签编码最长 64 字符");
                }
                codes.add(trimmed);
                if (codes.size() > MAX_FILTER_TAG_CODES) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "单次最多按 20 个标签筛选");
                }
            }
        }
        return codes.isEmpty() ? null : List.copyOf(codes);
    }

    private String normalizeTagMatchMode(String tagMatchMode) {
        if (tagMatchMode == null || tagMatchMode.isBlank()) {
            return TAG_MATCH_MODE_ANY;
        }
        String normalized = tagMatchMode.strip().toUpperCase(Locale.ROOT);
        if (!TAG_MATCH_MODE_ANY.equals(normalized) && !TAG_MATCH_MODE_ALL.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签匹配模式仅支持 ANY 或 ALL");
        }
        return normalized;
    }

    private void notifyTagSync(long tenantId, long customerId, List<String> beforeTagCodes,
                               List<String> afterTagCodes, Long actorUserId, String triggerSource) {
        if (tagSyncPortProvider == null) {
            return;
        }
        CustomerTagSyncPort port = tagSyncPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.onCustomerTagsChanged(tenantId, customerId, beforeTagCodes, afterTagCodes, actorUserId, triggerSource);
        } catch (RuntimeException ex) {
            log.warn("企微客户标签异步同步入队失败，已忽略以保护本地写入: customerId={}", customerId);
        }
    }

    private List<String> normalizeTagCodes(List<String> input) {
        if (input == null) {
            return List.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : input) {
            if (code == null || code.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签不能为空");
            }
            codes.add(code.strip());
        }
        return List.copyOf(codes);
    }

    private List<Long> normalizeProjectIds(List<Long> input) {
        if (input == null) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : input) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "关注项目ID必须为正整数");
            }
            ids.add(id);
        }
        return ids.stream().sorted().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record WecomTagPullOutcome(boolean changed, List<String> beforeTagCodes, List<String> afterTagCodes) {
        public WecomTagPullOutcome {
            beforeTagCodes = beforeTagCodes == null ? List.of() : List.copyOf(beforeTagCodes);
            afterTagCodes = afterTagCodes == null ? List.of() : List.copyOf(afterTagCodes);
        }
    }
}
