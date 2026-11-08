package com.whale.crm.customer.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手机号脱敏形态（纯函数）。
 */
class MobileSupportTest {

    @Test
    void mask_keepsFirst3Last4() {
        assertThat(MobileSupport.mask("13812345678")).isEqualTo("138****5678");
        assertThat(MobileSupport.mask("+8613812345678")).isEqualTo("861****5678");
    }

    @Test
    void mask_shortNumberIsFullyMasked() {
        assertThat(MobileSupport.mask("12345")).isEqualTo("*****");
        assertThat(MobileSupport.mask(null)).isNull();
    }

    @Test
    void maskExternalId() {
        assertThat(CustomerApplicationService.maskExternalId("wx-c02-001")).isEqualTo("w***1");
        assertThat(CustomerApplicationService.maskExternalId("ab")).isEqualTo("**");
    }
}
