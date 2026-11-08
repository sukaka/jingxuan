package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAttemptServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations values = mock(ValueOperations.class);
    private final LoginAttemptService service = new LoginAttemptService(redis, "test:login:", 3, 5, 900);

    @Test
    void successClearsOnlyTheAccountFailuresWithoutResettingTheIpBudget() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L, 1L, 2L, 2L, 3L, 3L);
        service.recordFailure("admin", "127.0.0.1");
        service.recordFailure("admin", "127.0.0.1");
        assertThatThrownBy(() -> service.recordFailure("admin", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        when(values.get(anyString())).thenReturn("3");
        assertThatThrownBy(() -> service.checkAllowed("admin", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);

        service.recordSuccess("admin");
        verify(redis).delete(org.mockito.ArgumentMatchers.<String>argThat(key ->
                key.startsWith("test:login:account:") && !key.contains("admin")));
        verify(redis, org.mockito.Mockito.times(2)).expire(anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(900)));
        when(values.get(anyString())).thenReturn(null);
        assertThatCode(() -> service.checkAllowed("admin", "127.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void ipLimitAlsoAppliesAcrossDifferentUsernames() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L, 1L, 1L, 1L, 1L, 2L, 2L, 2L, 2L, 2L);
        when(values.get(anyString())).thenReturn("5");
        assertThatThrownBy(() -> service.checkAllowed("another-user", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void captchaIssuingIsAlsoRateLimited() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(31L);
        assertThatThrownBy(() -> service.checkCaptchaAllowed("127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }
}
