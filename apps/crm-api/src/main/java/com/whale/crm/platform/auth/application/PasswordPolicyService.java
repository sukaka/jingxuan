package com.whale.crm.platform.auth.application;

import com.whale.crm.platform.auth.api.dto.PasswordPolicyResponse;
import com.whale.crm.platform.security.PasswordPolicyProperties;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 密码复杂度策略校验服务。前端预校验仅用于交互提示，所有密码复杂度规则由本服务强制执行。
 */
@Service
public class PasswordPolicyService {

    private static final int MIN_USERNAME_CHECK_LENGTH = 3;

    private final PasswordPolicyProperties policy;

    public PasswordPolicyService(PasswordPolicyProperties policy) {
        this.policy = policy;
    }

    public PasswordPolicyResponse currentPolicy() {
        return new PasswordPolicyResponse(
                policy.minLength(),
                policy.maxLength(),
                policy.requireUppercase(),
                policy.requireLowercase(),
                policy.requireDigit(),
                policy.requireSpecial(),
                policy.forbidWhitespace(),
                policy.forbidUsername(),
                policy.forbidSameAsCurrent());
    }

    public void validateNewPassword(String newPassword, String confirmPassword, String username) {
        List<ApiResponse.ErrorDetail> details = checkNewPassword(newPassword, confirmPassword, username);
        if (!details.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, details.getFirst().reason(), details);
        }
    }

    public List<ApiResponse.ErrorDetail> checkNewPassword(String newPassword, String confirmPassword, String username) {
        List<ApiResponse.ErrorDetail> details = new ArrayList<>();
        if (newPassword == null || newPassword.isEmpty()) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码不能为空"));
            return details;
        }

        if (newPassword.length() < policy.minLength() || newPassword.length() > policy.maxLength()) {
            details.add(new ApiResponse.ErrorDetail(
                    "newPassword",
                    "新密码长度须为 " + policy.minLength() + "-" + policy.maxLength() + " 位"));
        }

        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        boolean hasWhitespaceOrControl = false;

        for (int i = 0; i < newPassword.length(); i++) {
            char ch = newPassword.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                hasWhitespaceOrControl = true;
            } else if (ch >= 'A' && ch <= 'Z') {
                hasUppercase = true;
            } else if (ch >= 'a' && ch <= 'z') {
                hasLowercase = true;
            } else if (ch >= '0' && ch <= '9') {
                hasDigit = true;
            } else if (isAsciiSpecial(ch)) {
                hasSpecial = true;
            }
        }

        if (policy.forbidWhitespace() && hasWhitespaceOrControl) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码不能包含空白字符或控制字符"));
        }
        if (policy.requireUppercase() && !hasUppercase) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码必须包含至少 1 个大写字母"));
        }
        if (policy.requireLowercase() && !hasLowercase) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码必须包含至少 1 个小写字母"));
        }
        if (policy.requireDigit() && !hasDigit) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码必须包含至少 1 个数字"));
        }
        if (policy.requireSpecial() && !hasSpecial) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码必须包含至少 1 个特殊字符"));
        }
        if (policy.forbidUsername() && containsUsername(newPassword, username)) {
            details.add(new ApiResponse.ErrorDetail("newPassword", "新密码不能包含当前用户名"));
        }
        if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
            details.add(new ApiResponse.ErrorDetail("confirmPassword", "两次输入的新密码不一致"));
        }
        return details;
    }

    public void validateNotSameAsCurrent(String currentPassword, String newPassword,
                                         String existingHash, PasswordEncoder passwordEncoder) {
        if (!policy.forbidSameAsCurrent()) {
            return;
        }
        boolean same = newPassword.equals(currentPassword)
                || (existingHash != null && !existingHash.isBlank()
                && passwordEncoder.matches(newPassword, existingHash));
        if (same) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "新密码不能与原密码相同",
                    List.of(new ApiResponse.ErrorDetail("newPassword", "新密码不能与原密码相同")));
        }
    }

    private static boolean isAsciiSpecial(char ch) {
        return ch >= 0x21 && ch <= 0x7E
                && !((ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9'));
    }

    private static boolean containsUsername(String password, String username) {
        if (username == null) {
            return false;
        }
        String normalizedUser = username.strip().toLowerCase(Locale.ROOT);
        if (normalizedUser.length() < MIN_USERNAME_CHECK_LENGTH) {
            return false;
        }
        return password.toLowerCase(Locale.ROOT).contains(normalizedUser);
    }
}
