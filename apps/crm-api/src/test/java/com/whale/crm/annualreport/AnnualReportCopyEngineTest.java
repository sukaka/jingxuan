package com.whale.crm.annualreport;

import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.annualreport.application.AnnualReportCopyEngine;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnualReportCopyEngineTest {

    private final AnnualReportCopyEngine engine = new AnnualReportCopyEngine(new QuickReplyTextMasker());

    @Test
    void infersToneFromProfileSignals() {
        var male = engine.resolveTone("AUTO", "MALE", List.of(), "A", 200, "超光子");
        assertThat(male.toneCode()).isEqualTo(AnnualReportCopyEngine.TONE_MINIMAL);

        var clinical = engine.resolveTone("AUTO", "FEMALE", List.of("ingredient_fan"), "A", 120, "黄金微针");
        assertThat(clinical.toneCode()).isEqualTo(AnnualReportCopyEngine.TONE_CLINICAL);

        var poetic = engine.resolveTone("AUTO", "FEMALE", List.of(), "S", 928, "M22超光子");
        assertThat(poetic.toneCode()).isEqualTo(AnnualReportCopyEngine.TONE_POETIC);
    }

    @Test
    void generatesDistinctPersonalizedCopyPerToneAndGender() {
        PublicAnnualReportResponse.Copywriting maleMinimal = engine.generate(new AnnualReportCopyEngine.CopyInput(
                2026, AnnualReportCopyEngine.TONE_MINIMAL, "MALE", "陈砚洲", "陆晨",
                "2025.08.06", 477, 4, 4, 2, "黑金超光子",
                true, "2026.08.29", "22:15", "轮廓紧致无创抗衰", null
        ));
        assertThat(maleMinimal.salutation()).isEqualTo("陈砚洲 先生，见字如晤");
        assertThat(maleMinimal.personaTitle()).isEqualTo("秩序感状态管理家");

        PublicAnnualReportResponse.Copywriting femaleClinical = engine.generate(new AnnualReportCopyEngine.CopyInput(
                2026, AnnualReportCopyEngine.TONE_CLINICAL, "FEMALE", "苏知夏", "知意",
                "2026.01.17", 312, 4, 5, 2, "黄金微针",
                true, "2026.06.15", "00:24", "夏日毛孔精雕组", null
        ));
        assertThat(femaleClinical.personaTitle()).isEqualTo("清醒成分美学派");
        assertThat(femaleClinical.midnightStory()).contains("夏日毛孔精雕组");
    }

    @Test
    void masksPhoneAndBlocksForbiddenMedicalClaims() {
        String masked = engine.sanitizeCustomNote("感谢支持，联系 13800138000 预约");
        assertThat(masked).contains("138****8000").doesNotContain("13800138000");

        assertThatThrownBy(() -> engine.sanitizeCustomNote("承诺永久年轻保证治愈"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("绝对化");
    }
}
