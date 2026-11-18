package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

@Component
public class CustomerTimelineCursorCodec {
    private final ObjectMapper objectMapper;

    public CustomerTimelineCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CustomerTimelineCursor decode(String encoded, long customerId) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            CursorPayload payload = objectMapper.readValue(decoded, CursorPayload.class);
            if (payload.version() != 1 || payload.customerId() != customerId || payload.occurredAt() == null
                    || payload.type() == null || payload.type().isBlank() || payload.type().length() > 48
                    || payload.eventKey() == null || payload.eventKey().isBlank() || payload.eventKey().length() > 128) {
                throw invalid();
            }
            return new CustomerTimelineCursor(payload.occurredAt(), payload.type(), payload.eventKey());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    public String encode(CustomerTimelineItemResponse item, long customerId) {
        try {
            CursorPayload payload = new CursorPayload(1, customerId, item.occurredAt(), item.type(), item.eventKey());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("时间线游标序列化失败", ex);
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "时间线游标无效或不属于该客户");
    }

    private record CursorPayload(int version, long customerId, Instant occurredAt, String type, String eventKey) {
    }
}
