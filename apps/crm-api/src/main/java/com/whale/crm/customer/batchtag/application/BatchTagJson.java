package com.whale.crm.customer.batchtag.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BatchTagJson {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public BatchTagJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("批量重打标快照序列化失败", ex);
        }
    }

    public List<String> stringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (Exception ex) {
            throw new IllegalStateException("批量重打标标签快照无法解析", ex);
        }
    }

    public Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP);
        } catch (Exception ex) {
            throw new IllegalStateException("批量重打标来源快照无法解析", ex);
        }
    }

    public <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("批量重打标快照无法解析", ex);
        }
    }
}
