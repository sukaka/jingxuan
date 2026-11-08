package com.whale.crm.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.risk.application.RiskEvidenceMasker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEvidenceMaskerTest {
    private final RiskEvidenceMasker masker = new RiskEvidenceMasker(new ObjectMapper().findAndRegisterModules());

    @Test
    void masksSensitiveJsonFieldsPhonesEmailsAndIdentityNumbers() {
        String masked = masker.mask("{\"name\":\"合成姓名\",\"contact\":{\"mobile\":\"13800138000\"},"
                + "\"note\":\"联系 test@example.com，证件11010119900307001X\"}");

        assertThat(masked).doesNotContain("合成姓名", "13800138000", "test@example.com", "11010119900307001X");
        assertThat(masked).contains("\"name\":\"***\"", "\"mobile\":\"***\"");
    }

    @Test
    void masksFreeTextPhoneWithoutKeepingFullNumber() {
        assertThat(masker.mask("联系电话13800138000")).isEqualTo("联系电话138****8000");
    }

    @Test
    void leavesNonSensitiveIntegerAmountEvidenceUsable() {
        String masked = masker.mask("expectedFen=1000 actualFen=900");

        assertThat(masked).isEqualTo("expectedFen=1000 actualFen=900");
    }
}
