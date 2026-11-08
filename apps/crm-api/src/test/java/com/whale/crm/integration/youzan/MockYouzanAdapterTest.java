package com.whale.crm.integration.youzan;

import com.whale.crm.integration.youzan.adapter.DisabledYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanPullPage;
import com.whale.crm.integration.youzan.adapter.YouzanSignature;
import com.whale.crm.integration.youzan.adapter.YouzanVerification;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockYouzanAdapterTest {
    private final MockYouzanAdapter adapter = new MockYouzanAdapter("unit-test-secret");

    @Test
    void validMockSignatureUsesTimestampAndRawBody() {
        long timestamp = Instant.now().getEpochSecond();
        String raw = "{\"event_id\":\"IT-SIGN-001\"}";
        YouzanVerification result = adapter.verifyWebhook(raw,
                new YouzanSignature(adapter.signMock(raw, timestamp), timestamp));
        assertThat(result.valid()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void tamperedOrExpiredSignatureIsRejected() {
        long timestamp = Instant.now().getEpochSecond();
        String raw = "{\"event_id\":\"IT-SIGN-002\"}";
        String signature = adapter.signMock(raw, timestamp);
        assertThat(adapter.verifyWebhook(raw + " ", new YouzanSignature(signature, timestamp)).valid()).isFalse();
        long expiredAt = timestamp - 301;
        assertThat(adapter.verifyWebhook(raw, new YouzanSignature(adapter.signMock(raw, expiredAt), expiredAt)).valid()).isFalse();
    }

    @Test
    void mockPaginationIsDeterministicAndContainsNoRealEndpoint() {
        YouzanPullPage first = adapter.pullOrders("MOCK-SHOP-001", "0", 1);
        assertThat(first.events()).hasSize(1);
        assertThat(first.nextCursor()).isEqualTo("1");
        assertThat(first.hasMore()).isTrue();
        YouzanPullPage second = adapter.pullOrders("MOCK-SHOP-001", first.nextCursor(), 1);
        assertThat(second.events()).hasSize(1);
        assertThat(second.events().get(0).items().get(0).externalSkuId()).isEqualTo("YZ-SKU-MISSING");
        assertThat(second.hasMore()).isFalse();
        assertThat(adapter.name()).isEqualTo("MOCK_DEV_ONLY");
    }

    @Test
    void realAdapterRemainsDisabled() {
        DisabledYouzanAdapter disabled = new DisabledYouzanAdapter();
        assertThat(disabled.name()).isEqualTo("REAL_YOUZAN_DISABLED");
        assertThat(disabled.verifyWebhook("{}", new YouzanSignature("x", Instant.now().getEpochSecond())).valid()).isFalse();
        assertThatThrownBy(() -> disabled.pullOrders("MOCK-SHOP-001", "0", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FEATURE_REAL_YOUZAN");
    }
}
