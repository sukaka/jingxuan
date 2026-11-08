package com.whale.crm.platform.dictionary.domain;

import java.util.List;
import java.util.Optional;

public interface DictionaryRepository {

    /**
     * 查询某租户下某字典编码的 ACTIVE 选项，按 sort_no 升序。
     */
    List<DictionaryItem> findActiveOptions(long tenantId, String dictCode);

    Optional<DictionaryItem> findActiveOption(long tenantId, String dictCode, String optionCode);

    List<DictionaryType> findTypes(long tenantId);

    /**
     * 全部状态的选项（管理端用，含 INACTIVE/ARCHIVED 历史项）。
     */
    List<DictionaryItem> findAllItems(long tenantId);

    Optional<DictionaryType> findTypeByCode(long tenantId, String dictCode);

    Optional<DictionaryItem> findItemById(long tenantId, long id);

    boolean existsItemCode(long tenantId, long dictTypeId, String optionCode);

    void insertItem(DictionaryItem item);

    /**
     * 乐观锁更新展示字段（option_code 不可变）；返回受影响行数。
     */
    int updateItem(long tenantId, long id, long version, String optionLabel, int sortNo);

    /**
     * 状态迁移带原状态守卫（防并发重复停用）；返回受影响行数。
     */
    int updateItemStatus(long tenantId, long id, String fromStatus, String toStatus);
}
