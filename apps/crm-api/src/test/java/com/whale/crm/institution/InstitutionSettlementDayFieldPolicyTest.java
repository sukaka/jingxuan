package com.whale.crm.institution;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.whale.crm.institution.application.InstitutionFieldPolicy;
import com.whale.crm.institution.application.InstitutionSupport;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InstitutionSettlementDayFieldPolicyTest {
    @Test
    void settlementDayUsesMarketThenFinanceAndNullRestoresDefault() {
        assertThat(InstitutionFieldPolicy.firstStep("settlementDay")).isEqualTo("MARKET");
        assertThat(InstitutionFieldPolicy.bothSteps("settlementDay")).isTrue();
        assertThat(InstitutionFieldPolicy.cipherField("settlementDay")).isFalse();
        assertThat(InstitutionFieldPolicy.normalize("settlementDay", NullNode.getInstance())).isNull();
        assertThat(InstitutionFieldPolicy.normalize("settlementDay", TextNode.valueOf("LAST_DAY")))
                .isEqualTo("LAST_DAY");
        assertThat(InstitutionFieldPolicy.jsonValue("settlementDay", null)).isNull();
        assertThatThrownBy(() -> InstitutionFieldPolicy.normalize("settlementDay", TextNode.valueOf("29")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void currentValueReadsOnlyTheFormalProfileSnapshot() {
        InstitutionProfile profile = new InstitutionProfile(1L, "INST-1", "合成机构", null, null, null, null,
                null, null, null, null, "ACTIVE", "MONTHLY", "8", null, null, null, null, false, 0L,
                false, null, null, null, null, null, null, null, 7L);

        assertThat(InstitutionFieldPolicy.currentValue(profile, "settlementDay", mock(InstitutionSupport.class)))
                .isEqualTo("8");
    }
}
