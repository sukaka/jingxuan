package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CreateCustomerOptionRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerOptionRequest;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.UpdateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 客户来源与客户渠道统一管理服务（仅 customer:source_channel:manage 可写：管家主管 / 系统管理员）。
 * 跨模块通过 DictionaryApplicationService 维护 customerSources / customerChannels 字典，
 * 禁止越权修改其他字典类型；普通管家仅能通过只读 options 下拉选择。
 */
@Service
public class CustomerSourceChannelAdminService {

    public static final String CUSTOMER_SOURCES_DICT_CODE = "customerSources";
    public static final String CUSTOMER_CHANNELS_DICT_CODE = "customerChannels";

    private final DictionaryApplicationService dictionaryService;

    public CustomerSourceChannelAdminService(DictionaryApplicationService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @Transactional(readOnly = true)
    public List<DictionaryItemResponse> listSources(long tenantId) {
        return dictionaryService.listItemsByDictCode(tenantId, CUSTOMER_SOURCES_DICT_CODE);
    }

    @Transactional
    public DictionaryItemResponse createSource(long tenantId, long actorUserId, CreateCustomerOptionRequest request) {
        String code = request.optionCode() != null ? request.optionCode() : generateCode("SRC_");
        return dictionaryService.createItemForType(tenantId, actorUserId, CUSTOMER_SOURCES_DICT_CODE,
                code, request.optionLabel(), request.sortNo(), true);
    }

    @Transactional
    public DictionaryItemResponse updateSource(long tenantId, long actorUserId, long itemId,
                                               UpdateCustomerOptionRequest request) {
        return dictionaryService.updateItemForType(tenantId, actorUserId, CUSTOMER_SOURCES_DICT_CODE, itemId,
                new UpdateDictionaryItemRequest(request.optionLabel(), request.sortNo(), request.version()), true);
    }

    @Transactional
    public DictionaryItemResponse disableSource(long tenantId, long actorUserId, long itemId) {
        return dictionaryService.disableItemForType(tenantId, actorUserId, CUSTOMER_SOURCES_DICT_CODE, itemId);
    }

    @Transactional
    public DictionaryItemResponse enableSource(long tenantId, long actorUserId, long itemId) {
        return dictionaryService.enableItemForType(tenantId, actorUserId, CUSTOMER_SOURCES_DICT_CODE, itemId);
    }

    @Transactional(readOnly = true)
    public List<DictionaryItemResponse> listChannels(long tenantId) {
        return dictionaryService.listItemsByDictCode(tenantId, CUSTOMER_CHANNELS_DICT_CODE);
    }

    @Transactional
    public DictionaryItemResponse createChannel(long tenantId, long actorUserId, CreateCustomerOptionRequest request) {
        String code = request.optionCode() != null ? request.optionCode() : generateCode("CHN_");
        return dictionaryService.createItemForType(tenantId, actorUserId, CUSTOMER_CHANNELS_DICT_CODE,
                code, request.optionLabel(), request.sortNo(), true);
    }

    @Transactional
    public DictionaryItemResponse updateChannel(long tenantId, long actorUserId, long itemId,
                                                UpdateCustomerOptionRequest request) {
        return dictionaryService.updateItemForType(tenantId, actorUserId, CUSTOMER_CHANNELS_DICT_CODE, itemId,
                new UpdateDictionaryItemRequest(request.optionLabel(), request.sortNo(), request.version()), true);
    }

    @Transactional
    public DictionaryItemResponse disableChannel(long tenantId, long actorUserId, long itemId) {
        return dictionaryService.disableItemForType(tenantId, actorUserId, CUSTOMER_CHANNELS_DICT_CODE, itemId);
    }

    @Transactional
    public DictionaryItemResponse enableChannel(long tenantId, long actorUserId, long itemId) {
        return dictionaryService.enableItemForType(tenantId, actorUserId, CUSTOMER_CHANNELS_DICT_CODE, itemId);
    }

    private String generateCode(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
