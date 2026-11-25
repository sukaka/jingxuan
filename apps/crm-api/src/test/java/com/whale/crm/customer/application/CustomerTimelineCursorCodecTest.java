package com.whale.crm.customer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerTimelineCursorCodecTest {

    private static final Instant BASE = Instant.parse("2026-11-21T10:00:00Z");
    private static final long CUSTOMER_ID = 100L;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CustomerTimelineCursorCodec codec = new CustomerTimelineCursorCodec(objectMapper);

    @Test
    void decodesValidCanonicalCursorsAcrossAllTypes() {
        assertThat(codec.decode(null, CUSTOMER_ID)).isNull();
        assertThat(codec.decode("   ", CUSTOMER_ID)).isNull();

        List<String> types = List.of(
                "OWNERSHIP", "FOLLOWUP", "INTENT", "BLOCKER", "NEXT_FOLLOWUP",
                "TASK", "ORDER", "APPOINTMENT", "REDEMPTION", "REFUND", "TIER", "COMPLAINT");
        long id = 42L;
        for (String type : types) {
            CustomerTimelineItemResponse item = CustomerTimelineItemResponse.of(
                    type, id, type, BASE, "标题", null, Map.of(), 1L);
            String encoded = codec.encode(item, CUSTOMER_ID);
            CustomerTimelineCursor decoded = codec.decode(encoded, CUSTOMER_ID);

            assertThat(decoded).isNotNull();
            assertThat(decoded.occurredAt()).isEqualTo(BASE);
            assertThat(decoded.type()).isEqualTo(type);
            assertThat(decoded.eventKey()).isEqualTo(String.format("%020d:%s", id, type));
            assertThat(decoded.sourceId()).isEqualTo(id);
            id++;
        }
    }

    @Test
    void rejectsNonCanonicalEventKeysAndMalformedPayloadsWithValidationFailed() throws Exception {
        List<String> invalidKeys = List.of(
                "bad",
                "123:FOLLOWUP",
                "0000000000000000012a:FOLLOWUP",
                "00000000000000000123-FOLLOWUP",
                "00000000000000000123:",
                "00000000000000000123:TASK",
                "00000000000000000123:FOLLOWUP:extra",
                "99999999999999999999:FOLLOWUP",
                "-0000000000000000123:FOLLOWUP",
                "00000000000000000123:followup",
                "   ");

        for (String invalidKey : invalidKeys) {
            String encoded = encodeRaw(1, CUSTOMER_ID, BASE, "FOLLOWUP", invalidKey);
            assertThatThrownBy(() -> codec.decode(encoded, CUSTOMER_ID))
                    .as("eventKey=%s 应被拒绝", invalidKey)
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        String nullKeyCursor = encodeRaw(1, CUSTOMER_ID, BASE, "FOLLOWUP", null);
        assertThatThrownBy(() -> codec.decode(nullKeyCursor, CUSTOMER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        String wrongCustomer = encodeRaw(1, 999L, BASE, "FOLLOWUP", "00000000000000000123:FOLLOWUP");
        assertThatThrownBy(() -> codec.decode(wrongCustomer, CUSTOMER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        String wrongVersion = encodeRaw(2, CUSTOMER_ID, BASE, "FOLLOWUP", "00000000000000000123:FOLLOWUP");
        assertThatThrownBy(() -> codec.decode(wrongVersion, CUSTOMER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private String encodeRaw(int version, long customerId, Instant occurredAt, String type, String eventKey)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("customerId", customerId);
        payload.put("occurredAt", occurredAt);
        payload.put("type", type);
        payload.put("eventKey", eventKey);
        byte[] json = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }
}
