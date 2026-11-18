package com.whale.crm.ai.application;

import com.whale.crm.ai.application.AiWorkerClient.InsightData;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AiChatDelegationSafety {
    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "GENERAL", "APPOINTMENT_INTENT", "PRICE_INQUIRY", "REPURCHASE_INTENT");
    private static final List<String> SENSITIVE_TERMS = List.of(
            "银行卡", "银行账号", "密码", "验证码", "身份证", "诊断", "处方", "保证治愈", "保证有效");
    private static final List<Pattern> BUSINESS_FACT_CLAIMS = List.of(
            Pattern.compile("订单.{0,8}(已确认|已支付|已创建)"),
            Pattern.compile("(已确认|已支付|已创建).{0,8}订单"),
            Pattern.compile("已(核销|退款|收款|到账)"),
            Pattern.compile("预约.{0,8}(已确认|成功|已落实|已生效)"),
            Pattern.compile("(已为您|已给你|已帮您|已替你).{0,6}(预约|预订|预定)"),
            Pattern.compile("(支付|退款|核销|合同|银行资料).{0,8}(成功|完成|已确认)"));

    public Optional<String> generationBlockReason(InsightData insight, String sourceMessageId,
                                                  List<String> configuredBlockedTerms) {
        if (insight == null || !"UNVERIFIED".equals(insight.factStatus())) {
            return Optional.of("AI_FACT_STATUS_BLOCKED");
        }
        if (!Boolean.FALSE.equals(insight.allowFinancialWrite())) {
            return Optional.of("AI_FINANCIAL_WRITE_BLOCKED");
        }
        if (insight.evidenceMessageIds() == null || !insight.evidenceMessageIds().contains(sourceMessageId)) {
            return Optional.of("AI_EVIDENCE_MISMATCH");
        }
        if (insight.insightType() == null || !ALLOWED_INTENTS.contains(insight.insightType())) {
            return Optional.of("AI_INTENT_NOT_ALLOWED");
        }
        if (insight.riskFlags() != null && !insight.riskFlags().isEmpty()) {
            return Optional.of("AI_RISK_BLOCKED");
        }
        return replyBlockReason(insight.replyDraft(), configuredBlockedTerms);
    }

    public Optional<String> replyBlockReason(String reply, List<String> configuredBlockedTerms) {
        String draft = reply == null ? "" : reply.strip();
        if (draft.isEmpty() || draft.length() > 2000) {
            return Optional.of("AI_REPLY_EMPTY_OR_TOO_LONG");
        }
        String normalized = draft.toLowerCase(Locale.ROOT);
        if (SENSITIVE_TERMS.stream().anyMatch(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))) {
            return Optional.of("AI_SENSITIVE_CONTENT_BLOCKED");
        }
        if (BUSINESS_FACT_CLAIMS.stream().anyMatch(pattern -> pattern.matcher(draft).find())) {
            return Optional.of("AI_BUSINESS_FACT_CLAIM_BLOCKED");
        }
        if (configuredBlockedTerms != null && configuredBlockedTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .anyMatch(term -> normalized.contains(term.strip().toLowerCase(Locale.ROOT)))) {
            return Optional.of("AI_CONFIG_BLOCK_TERM");
        }
        return Optional.empty();
    }
}
