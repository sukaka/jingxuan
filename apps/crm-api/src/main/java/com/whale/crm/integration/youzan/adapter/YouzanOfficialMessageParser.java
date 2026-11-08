package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Component
public class YouzanOfficialMessageParser {
    private final ObjectMapper objectMapper;

    public YouzanOfficialMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public YouzanMessage parse(String rawBody, String eventType) {
        try {
            if (rawBody == null || rawBody.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞消息为空或超过1MB限制");
            }
            JsonNode root = objectMapper.readTree(rawBody);
            validateDepth(root, 0);
            String messageId = firstText(root, "msgId", "msg_id", "id");
            String shopId = firstText(root, "kdtId", "kdt_id");
            JsonNode content = root.path("msg");
            if (content.isTextual()) {
                String decoded = URLDecoder.decode(content.textValue(), StandardCharsets.UTF_8);
                content = objectMapper.readTree(decoded);
            }
            String orderNo = firstText(content, "tid", "orderNo", "order_no");
            if (messageId == null || shopId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞消息缺少msgId或kdtId");
            }
            return new YouzanMessage(messageId, eventType, shopId, orderNo,
                    root.path("version").asLong(0), root.path("test").asBoolean(false), content);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞官方消息格式无效");
        }
    }

    private static void validateDepth(JsonNode node, int depth) {
        if (depth > 64) {
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
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
