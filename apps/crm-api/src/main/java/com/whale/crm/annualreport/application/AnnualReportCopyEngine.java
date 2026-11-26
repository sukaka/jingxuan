package com.whale.crm.annualreport.application;

import com.whale.crm.annualreport.api.dto.PublicAnnualReportResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 鲸选美学年度盘点千人千面文案引擎（后端生产版）。
 * 支持按客户性别提示、标签、客龄、会员等级与偏好项目自动推断语调，
 * 并内置医美绝对化违禁词护栏与脱敏清洗。
 */
@Component
public class AnnualReportCopyEngine {

    public static final String TONE_POETIC = "POETIC";
    public static final String TONE_CLINICAL = "CLINICAL";
    public static final String TONE_MINIMAL = "MINIMAL";

    private static final Set<String> FORBIDDEN_CLAIMS = Set.of(
            "保证治愈", "保证有效", "百分之百", "百分百", "根治", "永久有效",
            "永久年轻", "零风险", "立竿见影", "最低价", "绝对无痛"
    );

    private final QuickReplyTextMasker masker;

    public AnnualReportCopyEngine(QuickReplyTextMasker masker) {
        this.masker = masker;
    }

    public ToneDecision resolveTone(String requestedTone, String genderHint, List<String> tagCodes,
                                    String tierCode, long daysTogether, String favoriteProjectName) {
        if (requestedTone != null && !"AUTO".equals(requestedTone)) {
            return switch (requestedTone) {
                case TONE_POETIC -> new ToneDecision(TONE_POETIC, "诗意松弛风", "管家指定：诗意松弛风");
                case TONE_CLINICAL -> new ToneDecision(TONE_CLINICAL, "清醒专业风", "管家指定：清醒专业风");
                case TONE_MINIMAL -> new ToneDecision(TONE_MINIMAL, "极简高效风", "管家指定：极简高效风");
                default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的文案语调编码");
            };
        }
        String joinedTags = String.join(",", tagCodes == null ? List.of() : tagCodes).toLowerCase(Locale.ROOT);
        String favLower = favoriteProjectName == null ? "" : favoriteProjectName.toLowerCase(Locale.ROOT);
        if ("MALE".equals(genderHint) || joinedTags.contains("male") || joinedTags.contains("minimal")
                || joinedTags.contains("efficient")) {
            return new ToneDecision(TONE_MINIMAL, "极简高效风", "自动匹配：极简高效状态管理画像");
        }
        if (joinedTags.contains("clinical") || joinedTags.contains("ingredient") || joinedTags.contains("microneedle")
                || favLower.contains("微针") || favLower.contains("胶原") || favLower.contains("刷酸")) {
            return new ToneDecision(TONE_CLINICAL, "清醒专业风", "自动匹配：成分功课与周期维养偏好");
        }
        if (daysTogether >= 365 || "S".equalsIgnoreCase(tierCode)) {
            return new ToneDecision(TONE_POETIC, "诗意松弛风", "自动匹配：长期陪伴与高阶私享画像");
        }
        return new ToneDecision(TONE_POETIC, "诗意松弛风", "自动匹配：默认温润美学语调");
    }

    public String sanitizeCustomNote(String rawNote) {
        if (rawNote == null || rawNote.isBlank()) {
            return null;
        }
        String cleaned = masker.required(rawNote, "管家寄语", 200);
        assertSafePublicText(cleaned);
        return cleaned;
    }

    public void assertSafePublicText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_CLAIMS) {
            if (normalized.contains(forbidden.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "公开文案不能包含绝对化或疗效承诺词：" + forbidden);
            }
        }
    }

    public PublicAnnualReportResponse.Copywriting generate(CopyInput input) {
        boolean isMale = "MALE".equals(input.genderHint());
        String salutationName = isMale && !input.displayName().endsWith("先生")
                ? input.displayName() + " 先生"
                : input.displayName();
        String fav = input.favoriteProjectName();
        String customSign = input.customButlerNote() != null
                ? input.customButlerNote()
                : null;

        PublicAnnualReportResponse.Copywriting copy = switch (input.toneCode()) {
            case TONE_CLINICAL -> new PublicAnnualReportResponse.Copywriting(
                    "致 " + salutationName,
                    input.reportYear() + " 精准维养年度数据档案",
                    "解锁你的 " + input.reportYear() + " 科学变美轨迹",
                    "基于鲸选美学真实交易快照与院线核销记录生成 · 仅本人可见",
                    input.firstMeetDate() + " 建立首份美学档案，" + input.daysTogether()
                            + " 天里累计 " + input.orderCount() + " 笔有效下单、" + input.redeemCount()
                            + " 次规律到院护理，每一次投入都有清晰周期逻辑。",
                    "查看全年订单数据",
                    "全年下单记录，每一笔都有清晰功课",
                    "你的活跃决策时刻，藏在专注安排里",
                    buildMidnightStoryClinical(input),
                    "全年高频复购，锁定你的核心维养公式",
                    "全年围绕「" + fav + "」建立稳定维养节律，兼顾肌底修护与长期状态稳定。",
                    "从下单到到院核销，闭环见证真实履约",
                    input.remainingCount() > 0 ? "✦ 权益账本提醒 · 尚有可用余次" : "✦ 年度权益均已圆满履约",
                    "通知管家 " + input.butlerName() + " 安排下一周期预约",
                    isMale ? "理性状态管理专家" : "清醒成分美学派",
                    customSign != null ? customSign
                            : (isMale
                            ? "拒绝盲目跟风，用清晰周期与专业院线维持稳定状态。" + input.reportYear()
                                    + " 年，你把克制与效率写进了每一次护理安排。"
                            : "每次决策前都认真做足功课，懂得按季节与肤质节律精准投入。" + input.reportYear()
                                    + " 年，理性与好状态在你身上自然重合。"),
                    "专属美学顾问 " + input.butlerName() + " · " + input.reportYear() + " 数据核验寄语",
                    salutationName + " 的 " + input.reportYear() + " 精准美学画像"
            );
            case TONE_MINIMAL -> new PublicAnnualReportResponse.Copywriting(
                    salutationName + "，见字如晤",
                    input.reportYear() + " 个人状态管理年度简报",
                    "开启 " + input.reportYear() + " 状态复盘",
                    "交易快照与院线履约同步汇总 · 私密只读视图",
                    "自 " + input.firstMeetDate() + " 结缘起已同行 " + input.daysTogether()
                            + " 天。这一年 " + input.orderCount() + " 笔下单、" + input.redeemCount()
                            + " 次高效到院，把繁琐留给团队，只留从容状态。",
                    "浏览年度记录",
                    input.reportYear() + " 全年投入与项目清单",
                    "高效决策，往往发生在忙碌落幕后",
                    buildMidnightStoryMinimal(input),
                    "最常选择的王牌方案，省心且稳妥",
                    "核心聚焦「" + fav + "」，单次节奏利落、便于融入高强度日程管理。",
                    "履约进度清晰可控，余次一键衔接",
                    input.remainingCount() > 0 ? "✦ 账户尚有未核销权益待安排" : "✦ 本年度权益已全部履约完成",
                    "让管家 " + input.butlerName() + " 协调近期到院时段",
                    isMale ? "秩序感状态管理家" : "高效率质感主理人",
                    customSign != null ? customSign
                            : (isMale
                            ? "不堆砌冗余仪式，只在关键节点选择专业方案。" + input.reportYear()
                                    + " 年，你用极简自律保持着松弛而利落的精气神。"
                            : "清晰知道自己要什么，从不在选择上内耗。" + input.reportYear()
                                    + " 年，你把高效与好质感同时握在了手里。"),
                    "专属管家 " + input.butlerName() + " · " + input.reportYear() + " 简报",
                    salutationName + " · " + input.reportYear() + " 状态管理年度档案"
            );
            default -> new PublicAnnualReportResponse.Copywriting(
                    "致 " + salutationName,
                    input.reportYear() + " 年度私享纸雕灯箱",
                    "轻触点亮你的 " + input.reportYear() + " 光采灯箱",
                    "来自鲸选美学专属档案 · 仅你本人可见",
                    input.firstMeetDate() + " 那天，我们开启了第一场光采之约；" + input.daysTogether()
                            + " 个日夜流转，时间把每一次用心都酿成了柔和光泽。",
                    "推开四季光影",
                    "一整年，你把光留在了这些日子",
                    "整座城睡着时，有一扇窗还亮着",
                    buildMidnightStoryPoetic(input),
                    "你的年度本命，藏在一次次偏爱里",
                    "全年最常与「" + fav + "」相伴，是你最安心的四季光采底牌。",
                    "不止于下单，更见证每一次赴约蜕变",
                    input.remainingCount() > 0 ? "✦ 你的美学宝库仍有待开启权益" : "✦ 这一年的每一份美意都已如约赴会",
                    "一键通知专属管家 " + input.butlerName() + " 预约",
                    isMale ? "静谧时光鉴赏家" : "午夜发光收藏家",
                    customSign != null ? customSign
                            : (isMale
                            ? "在快节奏的城市里，仍愿为自己留出一盏从容的灯。" + input.reportYear()
                                    + " 年，你在克制与温柔之间找到了最舒适的松弛感。"
                            : "不追逐喧嚣的潮流，只在安静时光里挑选最合拍的光与水。" + input.reportYear()
                                    + " 年，你把松弛感写进了每一次肌肤焕亮。"),
                    "专属管家 " + input.butlerName() + " · 敬寄",
                    salutationName + " 的 " + input.reportYear() + " 年度美学人格"
            );
        };

        assertSafePublicText(copy.personaQuote());
        assertSafePublicText(copy.midnightStory());
        return copy;
    }

    private String buildMidnightStoryPoetic(CopyInput input) {
        if (!input.hasMidnightOrder()) {
            return input.midnightDate() + " " + input.midnightTime()
                    + "，这一年里最晚的一次交流定格在这个时刻——把对自己的温柔，静静安放在时光深处。";
        }
        return input.midnightDate() + " " + input.midnightTime()
                + "，夜色慢慢安静下来，你为自己定格了一份《" + input.midnightOrderTitle()
                + "》——把一整天最后的温柔，留给了发光的自己。";
    }

    private String buildMidnightStoryClinical(CopyInput input) {
        if (!input.hasMidnightOrder()) {
            return input.midnightDate() + " " + input.midnightTime()
                    + "，这是你全年最晚一次与顾问确认维养安排的时刻——清醒规划，从来不靠随性盲选。";
        }
        return input.midnightDate() + " " + input.midnightTime()
                + "，你在结束一天日程后依然认真核对护理周期，锁定了《" + input.midnightOrderTitle()
                + "》——看懂项目与节律，本身就是最好的底气。";
    }

    private String buildMidnightStoryMinimal(CopyInput input) {
        if (!input.hasMidnightOrder()) {
            return input.midnightDate() + " " + input.midnightTime()
                    + "，你在忙碌间隙高效同步了年度维养安排——省去繁琐沟通，把时间留给更重要的事。";
        }
        return input.midnightDate() + " " + input.midnightTime()
                + "，在结束日程后的片刻空档，你干脆完成了《" + input.midnightOrderTitle()
                + "》的安排——不拖泥带水，状态管理同样讲究效率。";
    }

    public record ToneDecision(String toneCode, String toneLabel, String reason) {
    }

    public record CopyInput(
            int reportYear,
            String toneCode,
            String genderHint,
            String displayName,
            String butlerName,
            String firstMeetDate,
            long daysTogether,
            int orderCount,
            int redeemCount,
            int remainingCount,
            String favoriteProjectName,
            boolean hasMidnightOrder,
            String midnightDate,
            String midnightTime,
            String midnightOrderTitle,
            String customButlerNote
    ) {
    }
}
