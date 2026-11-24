package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Component
public class YouzanOfficialMessageParser {
    private static final int MAX_PAYLOAD_BYTES = 1_000_000;
    private static final int MAX_JSON_DEPTH = 64;

    private final ObjectMapper objectMapper;

    public YouzanOfficialMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public YouzanMessage parse(String rawBody, String eventType) {
        try {
            if (rawBody == null || rawBody.isBlank() || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞消息为空或超过1MB限制");
            }
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞官方消息格式无效");
            }
            validateDepth(root, 0);
            boolean test = root.path("test").asBoolean(false);
            String messageId = firstText(root, "msgId", "msg_id", "id");
            if (messageId == null && test) {
                messageId = "TEST";
            }
            String shopId = firstText(root, "kdtId", "kdt_id");
            JsonNode content = root.path("msg");
            if (content.isTextual()) {
                content = parseTextualMsg(content.textValue());
            }
            validateDepth(content, 0);
            String orderNo = extractOrderNo(root, content);
            if (messageId == null || (!test && shopId == null)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞消息缺少msgId或kdtId");
            }
            return new YouzanMessage(messageId, eventType, shopId, orderNo,
                    root.path("version").asLong(0), test, content);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞官方消息格式无效");
        }
    }

    private JsonNode parseTextualMsg(String text) {
        if (text == null || text.isBlank()) {
            return MissingNode.getInstance();
        }
        String decoded = decodeMsg(text);
        try {
            JsonNode parsed = objectMapper.readTree(decoded);
            return parsed == null ? MissingNode.getInstance() : parsed;
        } catch (JsonProcessingException ex) {
            return MissingNode.getInstance();
        }
    }

    private static String decodeMsg(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return URLDecoder.decode(trimmed, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return trimmed;
        }
    }

    private static String extractOrderNo(JsonNode root, JsonNode content) {
        String orderNo = firstText(content, "tid", "orderNo", "order_no");
        if (orderNo != null) {
            return orderNo;
        }
        if (content != null && !content.isMissingNode() && !content.isNull()) {
            orderNo = firstText(content.path("full_order_info").path("order_info"), "tid");
            if (orderNo != null) {
                return orderNo;
            }
        }
        return firstText(root, "tid", "orderNo", "order_no");
    }

    private static void validateDepth(JsonNode node, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞消息嵌套深度超过64层限制");
        }
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> validateDepth(entry.getValue(), depth + 1));
        } else if (node.isArray()) {
            node.forEach(child -> validateDepth(child, depth + 1));
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
