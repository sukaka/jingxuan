package com.whale.crm.ai;

import com.whale.crm.ai.api.dto.AiChatDelegationPolicyResponse;
import com.whale.crm.ai.api.dto.UpdateAiChatDelegationPolicyRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatDelegationDtoTest {
    @Test
    void policyDtoListsAreDefensiveImmutableCopies() {
        List<String> terms = new ArrayList<>(List.of("验证码"));
        List<UpdateAiChatDelegationPolicyRequest.ScopeRequest> scopes = new ArrayList<>();
        scopes.add(new UpdateAiChatDelegationPolicyRequest.ScopeRequest("STAFF", 8L, true));
        UpdateAiChatDelegationPolicyRequest request = new UpdateAiChatDelegationPolicyRequest(true, 60, 10,
                "09:00", "21:00", "Asia/Shanghai", terms, scopes, 0L, "测试");
        terms.add("银行卡");
        scopes.clear();

        assertThat(request.blockedTerms()).containsExactly("验证码");
        assertThat(request.scopes()).hasSize(1);
        assertThatThrownBy(() -> request.blockedTerms().add("密码")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.scopes().clear()).isInstanceOf(UnsupportedOperationException.class);

        List<String> responseTerms = new ArrayList<>(List.of("验证码"));
        List<AiChatDelegationPolicyResponse.ScopeResponse> responseScopes = new ArrayList<>();
        AiChatDelegationPolicyResponse response = new AiChatDelegationPolicyResponse(1L, true, false,
                60, 10, "09:00", "21:00", "Asia/Shanghai", responseTerms, responseScopes, 1);
        responseTerms.clear();
        responseScopes.add(new AiChatDelegationPolicyResponse.ScopeResponse("TEAM", 3, true));
        assertThat(response.blockedTerms()).containsExactly("验证码");
        assertThat(response.scopes()).isEmpty();
        assertThatThrownBy(() -> response.scopes().add(new AiChatDelegationPolicyResponse.ScopeResponse("TEAM", 3, true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
