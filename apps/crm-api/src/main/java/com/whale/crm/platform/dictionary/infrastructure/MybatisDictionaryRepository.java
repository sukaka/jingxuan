package com.whale.crm.platform.dictionary.infrastructure;

import com.whale.crm.platform.dictionary.domain.DictionaryItem;
import com.whale.crm.platform.dictionary.domain.DictionaryRepository;
import com.whale.crm.platform.dictionary.domain.DictionaryType;
import com.whale.crm.platform.dictionary.infrastructure.mapper.DictionaryMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisDictionaryRepository implements DictionaryRepository {

    private final DictionaryMapper dictionaryMapper;

    public MybatisDictionaryRepository(DictionaryMapper dictionaryMapper) {
        this.dictionaryMapper = dictionaryMapper;
    }

    @Override
    public List<DictionaryItem> findActiveOptions(long tenantId, String dictCode) {
        return dictionaryMapper.selectActiveOptions(tenantId, dictCode);
    }

    @Override
    public Optional<DictionaryItem> findActiveOption(long tenantId, String dictCode, String optionCode) {
        return Optional.ofNullable(dictionaryMapper.selectActiveOption(tenantId, dictCode, optionCode));
    }

    @Override
    public List<DictionaryType> findTypes(long tenantId) {
        return dictionaryMapper.selectTypes(tenantId);
    }

    @Override
    public List<DictionaryItem> findAllItems(long tenantId) {
        return dictionaryMapper.selectAllItems(tenantId);
    }

    @Override
    public Optional<DictionaryType> findTypeByCode(long tenantId, String dictCode) {
        return Optional.ofNullable(dictionaryMapper.selectTypeByCode(tenantId, dictCode));
    }

    @Override
    public Optional<DictionaryItem> findItemById(long tenantId, long id) {
        return Optional.ofNullable(dictionaryMapper.selectItemById(tenantId, id));
    }

    @Override
    public boolean existsItemCode(long tenantId, long dictTypeId, String optionCode) {
        return dictionaryMapper.countItemByCode(tenantId, dictTypeId, optionCode) > 0;
    }

    @Override
    public void insertItem(DictionaryItem item) {
        dictionaryMapper.insertItem(item);
    }

    @Override
    public int updateItem(long tenantId, long id, long version, String optionLabel, int sortNo) {
        return dictionaryMapper.updateItem(tenantId, id, version, optionLabel, sortNo);
    }

    @Override
    public int updateItemStatus(long tenantId, long id, String fromStatus, String toStatus) {
        return dictionaryMapper.updateItemStatus(tenantId, id, fromStatus, toStatus);
    }
}
