package com.whale.crm.platform.dictionary.infrastructure.mapper;

import com.whale.crm.platform.dictionary.domain.DictionaryItem;
import com.whale.crm.platform.dictionary.domain.DictionaryType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DictionaryMapper {

    List<DictionaryItem> selectActiveOptions(@Param("tenantId") long tenantId, @Param("dictCode") String dictCode);

    DictionaryItem selectActiveOption(@Param("tenantId") long tenantId, @Param("dictCode") String dictCode,
                                      @Param("optionCode") String optionCode);

    List<DictionaryType> selectTypes(@Param("tenantId") long tenantId);

    List<DictionaryItem> selectAllItems(@Param("tenantId") long tenantId);

    DictionaryType selectTypeByCode(@Param("tenantId") long tenantId, @Param("dictCode") String dictCode);

    DictionaryItem selectItemById(@Param("tenantId") long tenantId, @Param("id") long id);

    int countItemByCode(@Param("tenantId") long tenantId, @Param("dictTypeId") long dictTypeId,
                        @Param("optionCode") String optionCode);

    int insertItem(DictionaryItem item);

    int updateItem(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                   @Param("optionLabel") String optionLabel, @Param("sortNo") int sortNo);

    int updateItemStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);
}
