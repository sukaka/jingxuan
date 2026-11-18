package com.whale.crm.ai;

import com.whale.crm.ai.application.AiChatDelegationSafety;
import com.whale.crm.ai.application.AiWorkerClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatDelegationSafetyTest {
    private final AiChatDelegationSafety safety = new AiChatDelegationSafety();

    @Test
    void onlyTraceableUnverifiedTextWithoutRiskCanBeSent() {
        AiWorkerClient.InsightData safe = insight("PRICE_INQUIRY", false,
                "已记录您的需求，将由专属管家进一步核实并回复。", List.of());

        assertThat(safety.generationBlockReason(safe, "91", List.of())).isEmpty();
        assertThat(safety.generationBlockReason(insight("COMPLAINT", false, "抱歉", List.of()),
                "91", List.of())).contains("AI_INTENT_NOT_ALLOWED");
        assertThat(safety.generationBlockReason(insight("GENERAL", true, "可以修改订单", List.of()),
                "91", List.of())).contains("AI_FINANCIAL_WRITE_BLOCKED");
        assertThat(safety.generationBlockReason(insight("GENERAL", false, "请提供银行卡密码", List.of()),
                "91", List.of())).contains("AI_SENSITIVE_CONTENT_BLOCKED");
        assertThat(safety.generationBlockReason(insight("GENERAL", false, "正常回复", List.of("COMPLAINT_RISK")),
                "91", List.of())).contains("AI_RISK_BLOCKED");
        assertThat(safety.generationBlockReason(insight("GENERAL", false, "正常回复", List.of()),
                "91", List.of("正常回复"))).contains("AI_CONFIG_BLOCK_TERM");
    }

    @Test
    void draftMustReferenceSourceMessageAndCannotClaimBusinessFacts() {
        AiWorkerClient.InsightData forgedEvidence = insight("GENERAL", false, "正常回复", List.of());

        assertThat(safety.generationBlockReason(forgedEvidence, "different-message", List.of()))
                .contains("AI_EVIDENCE_MISMATCH");
        assertThat(safety.generationBlockReason(insight("GENERAL", false, "订单已支付，退款成功", List.of()),
                "91", List.of())).contains("AI_BUSINESS_FACT_CLAIM_BLOCKED");
    }

    @Test
    void appointmentFactClaimsAreBlockedAtGenerationAndSendGates() {
        for (String draft : List.of("预约已确认", "已为您预约", "已为您预约成功", "预约成功，请按时到院")) {
            assertThat(safety.generationBlockReason(insight("APPOINTMENT_INTENT", false, draft, List.of()),
                    "91", List.of())).contains("AI_BUSINESS_FACT_CLAIM_BLOCKED");
            assertThat(safety.replyBlockReason(draft, List.of())).contains("AI_BUSINESS_FACT_CLAIM_BLOCKED");
        }
        assertThat(safety.replyBlockReason("已记录您的预约意向，专属管家将人工核实。", List.of())).isEmpty();
    }

    @Test
    void newIntentTypesCannotBypassHumanReviewIntoAutoDelegation() {
        for (String type : List.of("PACKAGE_QUERY", "VISIT_INTENT", "AFTERCARE_FEEDBACK",
                "FOLLOWUP_REQUIRED", "RISK_EVENT", "REFUND_INTENT", "COMPLAINT", "SERVICE_COMPLETION_CLAIM")) {
            assertThat(safety.generationBlockReason(insight(type, false, "将由人工进一步核实", List.of()),
                    "91", List.of())).contains("AI_INTENT_NOT_ALLOWED");
        }
    }

    private AiWorkerClient.InsightData insight(String type, boolean financialWrite, String draft, List<String> risks) {
        return new AiWorkerClient.InsightData(type, List.of(), null, null, null, List.of("91"),
                new BigDecimal("0.80"), List.of(), "UNVERIFIED", financialWrite, "摘要", draft, null, risks);
    }
}
