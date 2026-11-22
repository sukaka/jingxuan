package com.whale.crm.refund.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockRefundProviderAdapterTest {
    private final MockRefundProviderAdapter adapter = new MockRefundProviderAdapter(true, "unit-secret");

    @Test
    void verifiesRawBodyAndTimestampAndRejectsTampering() {
        long timestamp = Instant.now().getEpochSecond();
        String raw = "{\"externalRefundNo\":\"MOCK-1\"}";
        String signature = adapter.sign(raw, timestamp);
        assertThat(adapter.supports("MOCK")).isTrue();
        assertThat(adapter.supports("YOUZAN")).isFalse();
        assertThat(adapter.verify(raw, signature, timestamp)).isTrue();
        assertThat(adapter.verify(raw + " ", signature, timestamp)).isFalse();
        long expired = timestamp - 301;
        assertThat(adapter.verify(raw, adapter.sign(raw, expired), expired)).isFalse();
    }

    @Test
    void disabledMockRejectsEvenValidSignature() {
        MockRefundProviderAdapter disabled = new MockRefundProviderAdapter(false, "unit-secret");
        long timestamp = Instant.now().getEpochSecond();
        String raw = "{}";
        assertThat(disabled.verify(raw, disabled.sign(raw, timestamp), timestamp)).isFalse();
    }

    @Test
    void blankOrNullSecretFailsClosedWithoutException() {
        long timestamp = Instant.now().getEpochSecond();
        String raw = "{\"externalRefundNo\":\"MOCK-1\"}";
        for (String secret : new String[]{"", "   ", null}) {
            MockRefundProviderAdapter blankSecretAdapter = new MockRefundProviderAdapter(true, secret);
            assertThat(blankSecretAdapter.verify(raw, "any-signature", timestamp)).isFalse();
            assertThatThrownBy(() -> blankSecretAdapter.sign(raw, timestamp))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void nullOrBlankRawBodyAndMissingHeadersFailClosed() {
        long timestamp = Instant.now().getEpochSecond();
        String sig = adapter.sign("{\"a\":1}", timestamp);
        assertThat(adapter.verify(null, sig, timestamp)).isFalse();
        assertThat(adapter.verify("", sig, timestamp)).isFalse();
        assertThat(adapter.verify("   ", sig, timestamp)).isFalse();
        assertThat(adapter.verify("{\"a\":1}", null, timestamp)).isFalse();
        assertThat(adapter.verify("{\"a\":1}", " ", timestamp)).isFalse();
        assertThat(adapter.verify("{\"a\":1}", sig, null)).isFalse();
        assertThatThrownBy(() -> adapter.sign(null, timestamp))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extremeTimestampValuesDoNotOverflowWindowCheck() {
        String raw = "{\"externalRefundNo\":\"MOCK-OVERFLOW\"}";
        long now = Instant.now().getEpochSecond();
        for (long extreme : new long[]{Long.MIN_VALUE, now + Long.MIN_VALUE, Long.MAX_VALUE}) {
            String sig = adapter.sign(raw, extreme);
            assertThat(adapter.verify(raw, sig, extreme)).isFalse();
        }
    }
}
