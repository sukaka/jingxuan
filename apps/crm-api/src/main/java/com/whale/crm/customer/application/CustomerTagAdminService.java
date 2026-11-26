package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CreateCustomerTagRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerTagRequest;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.UpdateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 客户标签库管理服务（仅 customer:tag:manage 角色可写：管家主管 / 系统管理员）。
 * 跨模块通过 DictionaryApplicationService 维护 customerTags 字典，禁止越权修改其他字典类型。
 */
@Service
public class CustomerTagAdminService {

    public static final String CUSTOMER_TAGS_DICT_CODE = "customerTags";

    private final DictionaryApplicationService dictionaryService;

    public CustomerTagAdminService(DictionaryApplicationService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @Transactional(readOnly = true)
    public List<DictionaryItemResponse> listTags(long tenantId) {
        return dictionaryService.listItemsByDictCode(tenantId, CUSTOMER_TAGS_DICT_CODE);
    }

    @Transactional
    public DictionaryItemResponse createTag(long tenantId, long actorUserId, CreateCustomerTagRequest request) {
        String code = request.optionCode() != null ? request.optionCode() : generateTagCode();
        return dictionaryService.createItemForType(tenantId, actorUserId, CUSTOMER_TAGS_DICT_CODE,
                code, request.optionLabel(), request.sortNo(), true);
    }

    @Transactional
    public DictionaryItemResponse updateTag(long tenantId, long actorUserId, long tagId,
                                            UpdateCustomerTagRequest request) {
        return dictionaryService.updateItemForType(tenantId, actorUserId, CUSTOMER_TAGS_DICT_CODE, tagId,
                new UpdateDictionaryItemRequest(request.optionLabel(), request.sortNo(), request.version()), true);
    }

    @Transactional
    public DictionaryItemResponse disableTag(long tenantId, long actorUserId, long tagId) {
        return dictionaryService.disableItemForType(tenantId, actorUserId, CUSTOMER_TAGS_DICT_CODE, tagId);
    }

    @Transactional
    public DictionaryItemResponse enableTag(long tenantId, long actorUserId, long tagId) {
        return dictionaryService.enableItemForType(tenantId, actorUserId, CUSTOMER_TAGS_DICT_CODE, tagId);
    }

    private String generateTagCode() {
        return "TAG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
