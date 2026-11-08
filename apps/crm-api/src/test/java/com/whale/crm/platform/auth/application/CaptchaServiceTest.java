package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations values = mock(ValueOperations.class);
    private final CaptchaService service = new CaptchaService(redis, "test:captcha:", "unit-secret", 300);

    @Test
    void captchaIsOneTimeAndDoesNotExposeAnswerInImagePayload() {
        when(redis.opsForValue()).thenReturn(values);
        CaptchaService.CaptchaChallenge challenge = service.issue();
        assertThat(challenge.captchaId()).hasSize(36);
        assertThat(challenge.imageBase64()).startsWith("iVBORw0KGgo");
        assertThat(challenge.answer()).isNotBlank();
        verify(values).set("test:captcha:" + challenge.captchaId(), service.hash(challenge.answer()), Duration.ofSeconds(300));

        when(values.getAndDelete(anyString())).thenReturn(service.hash(challenge.answer()), null);
        service.verify(challenge.captchaId(), challenge.answer());
        assertThatThrownBy(() -> service.verify(challenge.captchaId(), challenge.answer()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void wrongAnswerIsRejected() {
        when(redis.opsForValue()).thenReturn(values);
        CaptchaService.CaptchaChallenge challenge = service.issue();
        when(values.getAndDelete(anyString())).thenReturn(service.hash("different"));
        assertThatThrownBy(() -> service.verify(challenge.captchaId(), challenge.answer()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
