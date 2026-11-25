package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

class LoginAttemptServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations values = mock(ValueOperations.class);
    private final LoginAttemptService service = new LoginAttemptService(redis, "test:login:", 3, 5, 900);

    @Test
    @SuppressWarnings("unchecked")
    void successClearsOnlyTheAccountFailuresWithoutResettingTheIpBudget() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L, 1L, 2L, 2L, 3L, 3L);
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
        verify(redis).delete(argThat((String key) ->
                key.startsWith("test:login:account:") && !key.contains("admin")));
        verify(redis, times(6)).execute(any(RedisScript.class), anyList(), eq("900"));
        when(values.get(anyString())).thenReturn(null);
        assertThatCode(() -> service.checkAllowed("admin", "127.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void ipLimitAlsoAppliesAcrossDifferentUsernames() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("5");
        assertThatThrownBy(() -> service.checkAllowed("another-user", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void captchaIssuingIsAlsoRateLimited() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("60"))).thenReturn(31L);
        assertThatThrownBy(() -> service.checkCaptchaAllowed("127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void captchaFailureIncrementsOnlyIpBucketAndDoesNotLockAccount() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("900"))).thenReturn(1L, 5L);
        assertThatCode(() -> service.recordCaptchaFailure("192.0.2.10")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.recordCaptchaFailure("192.0.2.10"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        verify(redis, times(2)).execute(any(RedisScript.class),
                argThat((java.util.List<String> keys) -> keys.size() == 1
                        && keys.getFirst().startsWith("test:login:ip:")),
                eq("900"));
        verify(redis, never()).execute(any(RedisScript.class),
                argThat((java.util.List<String> keys) -> keys.size() == 1
                        && keys.getFirst().startsWith("test:login:account:")),
                anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountFailuresAndSuccessAreIsolatedAcrossTenantScopes() {
        Map<String, Long> store = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> {
            Long val = store.get(inv.getArgument(0, String.class));
            return val == null ? null : Long.toString(val);
        });
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(inv -> {
            String key = inv.<java.util.List<String>>getArgument(1).getFirst();
            return store.merge(key, 1L, Long::sum);
        });
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0, String.class)) != null);

        // Tenant 2 fails 3 times -> Tenant 2 locked, Tenant 1 still allowed
        service.recordFailure("tenant:2", "alice", "198.51.100.1");
        service.recordFailure("tenant:2", "alice", "198.51.100.2");
        assertThatThrownBy(() -> service.recordFailure("tenant:2", "alice", "198.51.100.3"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThatCode(() -> service.checkAllowed("tenant:1", "alice", "203.0.113.10"))
                .doesNotThrowAnyException();

        // Ambiguous & invalid org scopes also fail without locking Tenant 1
        service.recordFailure("ambiguous", "alice", "198.51.100.4");
        service.recordFailure("org:NO_ORG", "alice", "198.51.100.5");
        assertThatCode(() -> service.checkAllowed("tenant:1", "alice", "203.0.113.10"))
                .doesNotThrowAnyException();

        // Tenant 1 fails twice, then Tenant 2 succeeds -> Tenant 1 counter NOT reset, 3rd failure locks Tenant 1
        service.recordFailure("tenant:1", "alice", "203.0.113.10");
        service.recordFailure("tenant:1", "alice", "203.0.113.11");
        service.recordSuccess("tenant:2", "alice");
        assertThat(store).isNotEmpty();
        assertThatThrownBy(() -> service.recordFailure("tenant:1", "alice", "203.0.113.12"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void checkIpAllowed_blocksExceededIpWithoutTouchingAccountBucket() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(argThat((String key) -> key != null && key.startsWith("test:login:ip:")))).thenReturn("5");
        assertThatThrownBy(() -> service.checkIpAllowed("198.51.100.99"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void passwordChangeAttemptsAreIsolatedFromLoginAttemptsAndClearOnSuccess() {
        Map<String, Long> store = new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> {
            Long val = store.get(inv.getArgument(0, String.class));
            return val == null ? null : Long.toString(val);
        });
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(inv -> {
            String key = inv.<java.util.List<String>>getArgument(1).getFirst();
            return store.merge(key, 1L, Long::sum);
        });
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.getArgument(0, String.class)) != null);

        service.recordPasswordChangeFailure("tenant:1", 42L, "198.51.100.70");
        service.recordPasswordChangeFailure("tenant:1", 42L, "198.51.100.71");
        assertThatThrownBy(() -> service.recordPasswordChangeFailure("tenant:1", 42L, "198.51.100.72"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);

        // Password change lock must not block normal login bucket
        assertThatCode(() -> service.checkAllowed("tenant:1", "alice", "198.51.100.70"))
                .doesNotThrowAnyException();

        service.recordPasswordChangeSuccess("tenant:1", 42L);
        assertThatCode(() -> service.checkPasswordChangeAllowed("tenant:1", 42L, "198.51.100.73"))
                .doesNotThrowAnyException();
    }
}
