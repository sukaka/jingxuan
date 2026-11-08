package com.whale.crm.refund.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
}
