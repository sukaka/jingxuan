package com.whale.crm.integration.youzan.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanSignature;
import com.whale.crm.integration.youzan.adapter.YouzanVerification;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class YouzanAdapterContractTest {
    private final MockYouzanAdapter adapter = new MockYouzanAdapter("acceptance-only-secret");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void validMockHmacPassesVerification() throws Exception {
        String rawBody = objectMapper.writeValueAsString(MockYouzanAdapter.paidEvent());
        long timestamp = Instant.now().getEpochSecond();
        String signature = adapter.signMock(rawBody, timestamp);

        YouzanVerification result = adapter.verifyWebhook(rawBody, new YouzanSignature(signature, timestamp));

        assertThat(result.valid()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void forgedOrExpiredHmacIsRejected() throws Exception {
        String rawBody = objectMapper.writeValueAsString(MockYouzanAdapter.paidEvent());
        long timestamp = Instant.now().getEpochSecond();
        String forged = adapter.signMock(rawBody + " ", timestamp);
        assertThat(adapter.verifyWebhook(rawBody, new YouzanSignature(forged, timestamp)).valid()).isFalse();

        long expiredTimestamp = timestamp - 301;
        String expired = adapter.signMock(rawBody, expiredTimestamp);
        YouzanVerification expiredResult = adapter.verifyWebhook(rawBody, new YouzanSignature(expired, expiredTimestamp));
        assertThat(expiredResult.valid()).isFalse();
        assertThat(expiredResult.errorCode()).isEqualTo("MOCK_SIGNATURE_EXPIRED");
    }

    @Test
    void paidAndWaitPayFixturesHaveDistinctTrustAndTimeSemantics() {
        YouzanOrderEvent paid = MockYouzanAdapter.paidEvent();
        YouzanOrderEvent waitPay = MockYouzanAdapter.pendingEvent();

        assertThat(paid.amountFen()).isEqualTo(362200L);
        assertThat(paid.externalTradeNo()).isEqualTo("YZ-MOCK-TRADE-001");
        assertThat(paid.externalOrderNo()).isEqualTo("YZ-MOCK-PAID-001");
        assertThat(paid.items().get(0).externalSkuId()).isEqualTo("YZ-SKU-301");
        assertThat(paid.items().get(0).quantity()).isEqualTo(2);
        assertThat(waitPay.externalOrderNo()).isEqualTo(paid.externalOrderNo());
        assertThat(waitPay.externalTradeNo()).isNull();
        assertThat(waitPay.externalUpdatedAt()).isBefore(paid.externalUpdatedAt());
    }
}
