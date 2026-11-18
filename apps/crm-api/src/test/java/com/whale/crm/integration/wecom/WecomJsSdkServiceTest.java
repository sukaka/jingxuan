package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.sidebar.application.WecomJsSdkService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WecomJsSdkServiceTest {
    @Test
    void signatureMatchesOfficialDocumentedSample() {
        String ticket = "sM4AOVdWfPE4DxkXGEs8VMCPGGVi4C3VM0P37wVUCFvkVAy_90u5h9nbSlYy3-Sl-HhTdfl2fzFy1AOcHKP7qg";
        String signature = WecomJsSdkService.signature(ticket, "Wm3WZYTPz0wzccnW", 1414587457L,
                "http://mp.weixin.qq.com?params=value");

        assertThat(signature).isEqualTo("0f9de62fce790f9a083d5c99e95740ceb90c27ed");
    }
}
