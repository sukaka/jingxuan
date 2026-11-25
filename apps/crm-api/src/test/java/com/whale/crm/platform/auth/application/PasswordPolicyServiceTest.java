package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.auth.api.dto.ChangePasswordRequest;
import com.whale.crm.platform.security.PasswordPolicyProperties;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyServiceTest {

    private final PasswordPolicyService service = new PasswordPolicyService(PasswordPolicyProperties.defaultPolicy());

    @Test
    void currentPolicy_exposesDefaultComplexityRules() {
        var policy = service.currentPolicy();
        assertThat(policy.minLength()).isEqualTo(8);
        assertThat(policy.maxLength()).isEqualTo(64);
        assertThat(policy.requireUppercase()).isTrue();
        assertThat(policy.requireLowercase()).isTrue();
        assertThat(policy.requireDigit()).isTrue();
        assertThat(policy.requireSpecial()).isTrue();
        assertThat(policy.forbidWhitespace()).isTrue();
        assertThat(policy.forbidUsername()).isTrue();
        assertThat(policy.forbidSameAsCurrent()).isTrue();
    }

    @Test
    void validateNewPassword_acceptsCompliantStrongPasswords() {
        assertThatCode(() -> service.validateNewPassword("Secure@2026", "Secure@2026", "alice"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validateNewPassword("Aa1!5678", "Aa1!5678", "ab"))
                .doesNotThrowAnyException();
        String maxLen = "Aa1!" + "x".repeat(60);
        assertThatCode(() -> service.validateNewPassword(maxLen, maxLen, "alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateNewPassword_rejectsComplexityViolationsAndMismatch() {
        assertFailsField("Aa1!567", "Aa1!567", "alice", "newPassword", "8-64");
        String tooLong = "Aa1!" + "x".repeat(61);
        assertFailsField(tooLong, tooLong, "alice", "newPassword", "8-64");
        assertFailsField("abcdef1!", "abcdef1!", "alice", "newPassword", "大写字母");
        assertFailsField("ABCDEF1!", "ABCDEF1!", "alice", "newPassword", "小写字母");
        assertFailsField("Abcdef!@", "Abcdef!@", "alice", "newPassword", "数字");
        assertFailsField("Abcdef12", "Abcdef12", "alice", "newPassword", "特殊字符");
        assertFailsField("Abcdef1! ", "Abcdef1! ", "alice", "newPassword", "空白字符");
        assertFailsField("Abcdef1!\n", "Abcdef1!\n", "alice", "newPassword", "空白字符");
        assertFailsField("MyAlice@2026", "MyAlice@2026", "alice", "newPassword", "当前用户名");
        assertFailsField("Secure@2026", "Secure@2027", "alice", "confirmPassword", "两次输入的新密码不一致");
    }

    @Test
    void validateNotSameAsCurrent_rejectsMatchingPlaintextOrHash() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("OldPass@2026");

        assertThatThrownBy(() -> service.validateNotSameAsCurrent("OldPass@2026", "OldPass@2026", hash, encoder))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatCode(() -> service.validateNotSameAsCurrent("OldPass@2026", "NewPass@2026", hash, encoder))
                .doesNotThrowAnyException();
    }

    @Test
    void customPolicyConfiguration_respectsConfiguredBoundsAndFlags() {
        PasswordPolicyProperties custom = new PasswordPolicyProperties(12, 32, true, true, true, false, true, false, false);
        PasswordPolicyService customService = new PasswordPolicyService(custom);

        assertThat(customService.checkNewPassword("Alice123456", "Alice123456", "alice"))
                .extracting(ApiResponse.ErrorDetail::reason)
                .anyMatch(msg -> msg.contains("12-32"));
        assertThat(customService.checkNewPassword("Alice1234567", "Alice1234567", "alice")).isEmpty();

        PasswordPolicyProperties clamped = new PasswordPolicyProperties(2, 999, true, true, true, true, true, true, true);
        assertThat(clamped.minLength()).isEqualTo(PasswordPolicyProperties.MIN_ALLOWED_LENGTH);
        assertThat(clamped.maxLength()).isEqualTo(PasswordPolicyProperties.MAX_ALLOWED_LENGTH);
    }

    @Test
    void changePasswordRequest_toStringRedactsSecrets() {
        ChangePasswordRequest req = new ChangePasswordRequest("Old@12345", "New@12345", "New@12345");
        assertThat(req.toString())
                .doesNotContain("Old@12345")
                .doesNotContain("New@12345");
    }

    private void assertFailsField(String newPassword, String confirmPassword, String username,
                                  String expectedField, String expectedMessagePart) {
        List<ApiResponse.ErrorDetail> details = service.checkNewPassword(newPassword, confirmPassword, username);
        assertThat(details)
                .anySatisfy(detail -> {
                    assertThat(detail.field()).isEqualTo(expectedField);
                    assertThat(detail.reason()).contains(expectedMessagePart);
                });
    }
}
