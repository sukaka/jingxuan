package com.whale.crm.exporting.domain;

import java.util.List;
import java.util.Map;

public interface ExportDataRepository {
    long count(ExportDataQuery query);

    List<Map<String, Object>> page(ExportDataQuery query);
}
