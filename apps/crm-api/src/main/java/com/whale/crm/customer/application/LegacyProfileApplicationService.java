package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.LegacyProfileItemResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerLegacy;
import com.whale.crm.customer.domain.CustomerLegacyRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.LegacyFieldMapping;
import com.whale.crm.customer.domain.LegacyFieldMappingRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 原客户信息表 23 字段逐项视图（docs/15 第 1 节 + docs/14 第 2 节 legacy_field_mapping）。
 * 已有客户字段给真实值；手机号仅脱敏；legacy 扩展未导入 → PENDING_IMPORT；
 * 订单域未落地（下单数/订单总金额）→ PENDING_DOMAIN，不伪造 0；crm_customer_legacy 有原值时优先展示。
 */
@Service
public class LegacyProfileApplicationService {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String PENDING_IMPORT = "PENDING_IMPORT";
    public static final String PENDING_DOMAIN = "PENDING_DOMAIN";

    private final LegacyFieldMappingRepository mappingRepository;
    private final CustomerLegacyRepository legacyRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final CustomerScopeSupport scopeSupport;
    private final MobileSupport mobileSupport;
    private final ObjectMapper objectMapper;
    private final CustomerOrderOperations customerOrderOperations;

    public LegacyProfileApplicationService(LegacyFieldMappingRepository mappingRepository,
                                           CustomerLegacyRepository legacyRepository,
                                           CustomerRepository customerRepository,
                                           UserRepository userRepository,
                                           CustomerScopeSupport scopeSupport,
                                           MobileSupport mobileSupport,
                                           ObjectMapper objectMapper,
                                           CustomerOrderOperations customerOrderOperations) {
        this.mappingRepository = mappingRepository;
        this.legacyRepository = legacyRepository;
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.scopeSupport = scopeSupport;
        this.mobileSupport = mobileSupport;
        this.objectMapper = objectMapper;
        this.customerOrderOperations = customerOrderOperations;
    }

    @Transactional(readOnly = true)
    public List<LegacyProfileItemResponse> profile(long tenantId, CurrentUser user, long customerId) {
        Customer customer = scopeSupport.requireVisible(tenantId, customerId, user);
        CustomerLegacy legacy = legacyRepository.findByCustomerId(tenantId, customerId).orElse(null);
        JsonNode legacyFull = parseLegacyFull(legacy);
        String ownerName = userRepository.findById(tenantId, customer.getOwnerStaffId())
                .map(SysUser::getDisplayName).orElse(null);

        return mappingRepository.findEnabled(tenantId).stream()
                .map(mapping -> toItem(mapping, customer, legacy, legacyFull, ownerName, tenantId))
                .toList();
    }

    private LegacyProfileItemResponse toItem(LegacyFieldMapping mapping, Customer customer,
                                             CustomerLegacy legacy, JsonNode legacyFull,
                                             String ownerName, long tenantId) {
        String legacyValue = legacyValueOf(mapping, legacy, legacyFull);
        String value;
        String availability;
        switch (mapping.getFieldKey()) {
            case "USER_NAME" -> {
                value = customer.getDisplayName();
                availability = AVAILABLE;
            }
            case "NICKNAME" -> {
                value = customer.getNickname();
                availability = AVAILABLE;
            }
            case "MOBILE" -> {
                value = mobileSupport.maskFromCipher(customer.getMobileCipher());
                availability = AVAILABLE;
            }
            case "SOURCE" -> {
                value = customer.getSourceCode();
                availability = AVAILABLE;
            }
            case "CHANNEL" -> {
                value = customer.getChannelCode();
                availability = AVAILABLE;
            }
            case "NEW_OLD" -> {
                value = legacy != null ? legacy.getLegacyNewOld() : null;
                availability = value != null ? AVAILABLE : PENDING_IMPORT;
            }
            case "CITY" -> {
                value = customer.getCityCode();
                availability = AVAILABLE;
            }
            case "ATTENTION_PROJECTS" -> {
                value = customer.getAttentionProjects();
                availability = AVAILABLE;
            }
            case "OWNER_NAME" -> {
                value = ownerName;
                availability = AVAILABLE;
            }
            case "WRITER" -> {
                value = legacy != null ? legacy.getLegacyWriter() : null;
                availability = value != null ? AVAILABLE : PENDING_IMPORT;
            }
            case "OWNER_ACCOUNT" -> {
                value = legacy != null ? legacy.getLegacyAccount() : null;
                availability = value != null ? AVAILABLE : PENDING_IMPORT;
            }
            case "MOBILE_DUPLICATED" -> {
                if (customer.getMobileHash() == null) {
                    value = null;
                } else {
                    value = customerRepository.countByMobileHash(tenantId, customer.getMobileHash()) > 1
                            ? "是" : "否";
                }
                availability = AVAILABLE;
            }
            case "ORDER_COUNT" -> {
                value = Long.toString(customerOrderOperations.orderStats(tenantId, customer.getId()).orderCount());
                availability = AVAILABLE;
            }
            case "ORDER_TOTAL_AMOUNT" -> {
                var stats = customerOrderOperations.orderStats(tenantId, customer.getId());
                value = "原价 " + moneyText(stats.listAmountFen()) + " 元 / 实付 " + moneyText(stats.paidAmountFen()) + " 元";
                availability = AVAILABLE;
            }
            case "CUSTOMER_STATUS" -> {
                value = customer.getCustomerStatus();
                availability = AVAILABLE;
            }
            case "SUBMITTED_AT" -> {
                value = legacy != null && legacy.getLegacySubmittedAt() != null
                        ? legacy.getLegacySubmittedAt().toString() : null;
                availability = value != null ? AVAILABLE : PENDING_IMPORT;
            }
            case "FIRST_IMPORT_AT" -> {
                value = legacy != null && legacy.getLegacyFirstImportAt() != null
                        ? legacy.getLegacyFirstImportAt().toString() : null;
                availability = value != null ? AVAILABLE : PENDING_IMPORT;
            }
            case "FIRST_ADDED_AT" -> {
                value = instantText(customer.getFirstAddedAt());
                availability = AVAILABLE;
            }
            case "FIRST_ORDER_AT" -> {
                value = instantText(customer.getFirstOrderAt());
                availability = AVAILABLE;
            }
            case "LAST_FOLLOWUP_AT" -> {
                value = instantText(customer.getLastFollowupAt());
                availability = AVAILABLE;
            }
            case "BLOCKER_NOTE" -> {
                value = customer.getBlockerNote();
                availability = AVAILABLE;
            }
            case "REMARK" -> {
                value = customer.getRemark();
                availability = AVAILABLE;
            }
            case "REMARK_NAME" -> {
                value = customer.getRemarkName();
                availability = AVAILABLE;
            }
            default -> {
                value = null;
                availability = PENDING_DOMAIN;
            }
        }
        // legacy 扩展已有原值（原 23 字段原文）时优先展示
        if (legacyValue != null && (value == null || PENDING_IMPORT.equals(availability))) {
            value = legacyValue;
            availability = AVAILABLE;
        }
        return new LegacyProfileItemResponse(mapping.getFieldKey(), mapping.getOriginalName(),
                mapping.getOriginalTable(), mapping.getTargetField(), mapping.getSourceFormula(),
                value, availability);
    }

    /**
     * legacy_full_json 按键查找原值：先 field_key 后原字段名。
     */
    private String legacyValueOf(LegacyFieldMapping mapping, CustomerLegacy legacy, JsonNode legacyFull) {
        if (legacyFull == null) {
            return null;
        }
        JsonNode node = legacyFull.get(mapping.getFieldKey());
        if (node == null) {
            node = legacyFull.get(mapping.getOriginalName());
        }
        return node == null || node.isNull() ? null : node.asText();
    }

    private JsonNode parseLegacyFull(CustomerLegacy legacy) {
        if (legacy == null || legacy.getLegacyFullJson() == null || legacy.getLegacyFullJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(legacy.getLegacyFullJson());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String moneyText(long fen) {
        return java.math.BigDecimal.valueOf(fen, 2).toPlainString();
    }

    private static String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
