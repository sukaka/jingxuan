package com.whale.crm.exporting.infrastructure.mapper;

import com.whale.crm.exporting.domain.ExportDataQuery;
import com.whale.crm.exporting.domain.ExportDataRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ExportDataMapper extends ExportDataRepository {
    @Override
    long count(@Param("q") ExportDataQuery query);

    @Override
    List<Map<String, Object>> page(@Param("q") ExportDataQuery query);
}
