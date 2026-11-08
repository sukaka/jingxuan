package com.whale.crm.platform.dictionary.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.CreateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.api.dto.DictionaryTypeResponse;
import com.whale.crm.platform.dictionary.api.dto.UpdateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.domain.DictionaryItem;
import com.whale.crm.platform.dictionary.domain.DictionaryRepository;
import com.whale.crm.platform.dictionary.domain.DictionaryType;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典应用服务。只读 options 供全角色下拉；管理端 CRUD/停用写操作落审计。
 * 停用（ACTIVE→INACTIVE）不物理删除且不可新选；业务创建时保存 code/label 快照，历史不回读当前值覆盖。
 */
@Service
public class DictionaryApplicationService {

    private final DictionaryRepository dictionaryRepository;
    private final AuditService auditService;

    public DictionaryApplicationService(DictionaryRepository dictionaryRepository, AuditService auditService) {
        this.dictionaryRepository = dictionaryRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DictionaryOptionResponse> listActiveOptions(long tenantId, String dictCode) {
        return dictionaryRepository.findActiveOptions(tenantId, dictCode).stream()
                .map(item -> new DictionaryOptionResponse(item.getOptionCode(), item.getOptionLabel(), item.getSortNo()))
                .toList();
    }

    /**
     * 新业务选择校验并返回创建时 code/label 快照；调用方须将返回值随业务记录保存，
     * 历史读取不得通过当前字典覆盖该快照。
     */
    @Transactional(readOnly = true)
    public DictionaryOptionResponse requireActiveOption(long tenantId, String dictCode, String optionCode) {
        DictionaryItem item = dictionaryRepository.findActiveOption(tenantId, dictCode, optionCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "字典 " + dictCode + " 的选项 " + optionCode + " 不存在或已停用，不能用于新业务"));
        return new DictionaryOptionResponse(item.getOptionCode(), item.getOptionLabel(), item.getSortNo());
    }

    @Transactional(readOnly = true)
    public List<DictionaryTypeResponse> listTypesWithItems(long tenantId) {
        Map<Long, List<DictionaryItemResponse>> itemsByType = new LinkedHashMap<>();
        dictionaryRepository.findAllItems(tenantId).forEach(item -> itemsByType
                .computeIfAbsent(item.getDictTypeId(), key -> new java.util.ArrayList<>())
                .add(toItemResponse(item)));
        return dictionaryRepository.findTypes(tenantId).stream()
                .map(type -> new DictionaryTypeResponse(type.getId(), type.getDictCode(), type.getDictName(),
                        type.getStatus(), type.getVersion(),
                        itemsByType.getOrDefault(type.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DictionaryItemResponse> listItemsOfType(long tenantId, long dictTypeId) {
        return dictionaryRepository.findAllItems(tenantId).stream()
                .filter(item -> item.getDictTypeId() != null && item.getDictTypeId() == dictTypeId)
                .map(this::toItemResponse)
                .toList();
    }

    @Transactional
    public DictionaryItemResponse createItem(long tenantId, long actorUserId, CreateDictionaryItemRequest request) {
        DictionaryType type = dictionaryRepository.findTypeByCode(tenantId, request.dictCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "字典类型不存在"));
        if (dictionaryRepository.existsItemCode(tenantId, type.getId(), request.optionCode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该字典下选项代码已存在");
        }
        DictionaryItem item = new DictionaryItem();
        item.setTenantId(tenantId);
        item.setDictTypeId(type.getId());
        item.setOptionCode(request.optionCode());
        item.setOptionLabel(request.optionLabel());
        item.setSortNo(request.sortNo() == null ? Integer.valueOf(0) : request.sortNo());
        item.setStatus(DictionaryItem.STATUS_ACTIVE);
        dictionaryRepository.insertItem(item);

        DictionaryItem persisted = dictionaryRepository.findItemById(tenantId, item.getId()).orElseThrow();
        auditService.record(tenantId, actorUserId, AuditService.ACTION_DICTIONARY_ITEM_CREATE, "DICTIONARY_ITEM",
                Long.toString(item.getId()), null, itemSnapshot(type.getDictCode(), persisted), null);
        return toItemResponse(persisted);
    }

    @Transactional
    public DictionaryItemResponse updateItem(long tenantId, long actorUserId, long itemId,
                                             UpdateDictionaryItemRequest request) {
        DictionaryItem existing = dictionaryRepository.findItemById(tenantId, itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "字典项不存在或不可见"));
        if (!existing.isEditable()) {
            throw new BusinessException(ErrorCode.CONFLICT, "已归档字典项不可修改");
        }
        int updated = dictionaryRepository.updateItem(tenantId, itemId, request.version(),
                request.optionLabel(), request.sortNo());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        DictionaryItem after = dictionaryRepository.findItemById(tenantId, itemId).orElseThrow();
        String dictCode = dictionaryRepository.findTypes(tenantId).stream()
                .filter(type -> type.getId().equals(after.getDictTypeId()))
                .map(DictionaryType::getDictCode)
                .findFirst().orElse("-");
        auditService.record(tenantId, actorUserId, AuditService.ACTION_DICTIONARY_ITEM_UPDATE, "DICTIONARY_ITEM",
                Long.toString(itemId), itemSnapshot(dictCode, existing), itemSnapshot(dictCode, after), null);
        return toItemResponse(after);
    }

    @Transactional
    public DictionaryItemResponse disableItem(long tenantId, long actorUserId, long itemId) {
        DictionaryItem existing = dictionaryRepository.findItemById(tenantId, itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "字典项不存在或不可见"));
        String dictCode = dictionaryRepository.findTypes(tenantId).stream()
                .filter(type -> type.getId().equals(existing.getDictTypeId()))
                .map(DictionaryType::getDictCode)
                .findFirst().orElse("-");
        Map<String, Object> beforeSnapshot = itemSnapshot(dictCode, existing);
        try {
            existing.disable();
        } catch (IllegalStateException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅启用中的字典项可停用（当前状态 "
                    + existing.getStatus() + "）");
        }
        int updated = dictionaryRepository.updateItemStatus(tenantId, itemId,
                DictionaryItem.STATUS_ACTIVE, DictionaryItem.STATUS_INACTIVE);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        DictionaryItem after = dictionaryRepository.findItemById(tenantId, itemId).orElseThrow();
        auditService.record(tenantId, actorUserId, AuditService.ACTION_DICTIONARY_ITEM_DISABLE, "DICTIONARY_ITEM",
                Long.toString(itemId), beforeSnapshot, itemSnapshot(dictCode, after), null);
        return toItemResponse(after);
    }

    private Map<String, Object> itemSnapshot(String dictCode, DictionaryItem item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", item.getId());
        snapshot.put("dictCode", dictCode);
        snapshot.put("optionCode", item.getOptionCode());
        snapshot.put("optionLabel", item.getOptionLabel());
        snapshot.put("sortNo", item.getSortNo());
        snapshot.put("status", item.getStatus());
        return snapshot;
    }

    private DictionaryItemResponse toItemResponse(DictionaryItem item) {
        return new DictionaryItemResponse(item.getId(), item.getOptionCode(), item.getOptionLabel(),
                item.getSortNo(), item.getStatus(), item.getVersion(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
