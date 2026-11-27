package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.MobileSupport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析有赞订单详情（youzan.trade.get/4.0.2 与 youzan.trades.sold.get/4.0.2）中的：
 * 1. 订单状态、支付方式、下单/发货时间；
 * 2. orders[].buyer_messages 中的下单人姓名与手机号（全部脱敏输出，不保留明文 PII）；
 * 3. remark_info.trade_memo 中的结构化业务字段（业绩/渠道/类型/来源/账号/到院/补充说明），
 *    并对其中可能误填的手机号做自动脱敏。
 */
@Component
public class YouzanTradeMemoParser {

    private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PROVIDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern CN_MOBILE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(业绩归属|业绩人|业绩|渠道|客户类型|客型|类型|来源|管家账号|账号|到院机构|到院|机构|备注|说明)\\s*[:：]");
    private static final Pattern TRAILING_DELIMITER_PATTERN = Pattern.compile("[\\s,，;；、|]+$");
    private static final Pattern VALUE_SPLIT_PATTERN = Pattern.compile("(?:[,，;；|\\r\\n]+|\\s{2,})");

    private final ObjectMapper objectMapper;

    public YouzanTradeMemoParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedOrderDetails parseProviderPayload(String rawProviderPayload) {
        if (rawProviderPayload == null || rawProviderPayload.isBlank()) {
            return ParsedOrderDetails.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(rawProviderPayload);
            return parseProviderTree(root);
        } catch (Exception ex) {
            return ParsedOrderDetails.empty();
        }
    }

    public ParsedOrderDetails parseProviderTree(JsonNode root) {
        if (root == null || !root.isObject()) {
            return ParsedOrderDetails.empty();
        }
        JsonNode full = resolveFullOrderInfo(root);
        if (full == null || !full.isObject()) {
            return ParsedOrderDetails.empty();
        }

        JsonNode orderInfo = full.path("order_info");
        JsonNode payInfo = full.path("pay_info");
        JsonNode remarkInfo = full.path("remark_info");

        String providerOrderStatus = truncate(text(orderInfo, "status"), 64);
        String providerStatusText = truncate(text(orderInfo, "status_str"), 64);
        String payWayDesc = truncate(firstNonBlank(text(payInfo, "pay_way_desc"), text(orderInfo, "pay_type_desc")), 64);
        Instant orderedAt = parseProviderTime(text(orderInfo, "created"));
        Instant consignedAt = parseProviderTime(text(orderInfo, "consign_time"));

        BuyerFormMasked buyerForm = extractBuyerForm(full.path("orders"));

        boolean hasRemarkNode = remarkInfo.isObject() && remarkInfo.has("trade_memo");
        String rawTradeMemo = hasRemarkNode ? text(remarkInfo, "trade_memo") : null;
        ParsedMemo memo = parseTradeMemo(rawTradeMemo);

        return new ParsedOrderDetails(
                true,
                hasRemarkNode,
                providerOrderStatus,
                providerStatusText,
                payWayDesc,
                orderedAt,
                consignedAt,
                buyerForm.nameMasked(),
                buyerForm.mobileMasked(),
                memo);
    }

    public ParsedMemo parseTradeMemo(String rawMemo) {
        String cleaned = cleanRawText(rawMemo);
        if (cleaned == null) {
            return ParsedMemo.empty();
        }
        String sanitizedFull = truncate(maskMobiles(cleaned), 1000);

        List<KeyMatch> matches = new ArrayList<>();
        Matcher matcher = KEY_PATTERN.matcher(cleaned);
        while (matcher.find()) {
            matches.add(new KeyMatch(matcher.group(1), matcher.start(), matcher.end()));
        }

        if (matches.isEmpty()) {
            return new ParsedMemo(sanitizedFull, null, null, null, null, null, null, null,
                    truncate(sanitizedFull, 500));
        }

        String performanceOwner = null;
        String channel = null;
        String customerType = null;
        String source = null;
        String accountName = null;
        String institutionName = null;
        List<String> extraNotes = new ArrayList<>();

        String prefix = trimDelimiters(cleaned.substring(0, matches.get(0).start()));
        if (prefix != null) {
            extraNotes.add(maskMobiles(prefix));
        }

        for (int i = 0; i < matches.size(); i++) {
            KeyMatch current = matches.get(i);
            int valueEnd = (i + 1 < matches.size()) ? matches.get(i + 1).start() : cleaned.length();
            String rawSegment = trimDelimiters(cleaned.substring(current.end(), valueEnd));
            if (rawSegment == null) {
                continue;
            }
            FieldType fieldType = mapKey(current.key());
            if (fieldType == FieldType.EXTRA_NOTE) {
                extraNotes.add(maskMobiles(rawSegment));
                continue;
            }

            SplitResult split = splitPrimaryAndTrailing(rawSegment);
            String primary = split.primary() == null ? null : maskMobiles(split.primary());
            if (split.trailing() != null) {
                extraNotes.add(maskMobiles(split.trailing()));
            }
            if (primary == null) {
                continue;
            }
            switch (fieldType) {
                case PERFORMANCE_OWNER -> {
                    if (performanceOwner == null) {
                        performanceOwner = truncate(primary, 64);
                    }
                }
                case CHANNEL -> {
                    if (channel == null) {
                        channel = truncate(primary, 64);
                    }
                }
                case CUSTOMER_TYPE -> {
                    if (customerType == null) {
                        customerType = truncate(primary, 64);
                    }
                }
                case SOURCE -> {
                    if (source == null) {
                        source = truncate(primary, 128);
                    }
                }
                case ACCOUNT -> {
                    if (accountName == null) {
                        accountName = truncate(primary, 64);
                    }
                }
                case INSTITUTION -> {
                    if (institutionName == null) {
                        institutionName = truncate(primary, 128);
                    }
                }
                default -> extraNotes.add(primary);
            }
        }

        String accountKey = normalizeAccountKey(accountName);
        String extraNote = extraNotes.isEmpty() ? null : truncate(String.join("；", extraNotes), 500);
        return new ParsedMemo(
                sanitizedFull,
                performanceOwner,
                channel,
                customerType,
                source,
                accountName,
                accountKey,
                institutionName,
                extraNote);
    }

    public static String normalizeAccountKey(String raw) {
        String cleaned = cleanRawText(raw);
        if (cleaned == null) {
            return null;
        }
        String collapsed = cleaned.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        return collapsed.isEmpty() ? null : truncate(collapsed, 64);
    }

    public static String maskMobiles(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return CN_MOBILE_PATTERN.matcher(text).replaceAll("$1****$3");
    }

    public static String maskPersonName(String raw) {
        String cleaned = cleanRawText(raw);
        if (cleaned == null || isProviderCiphertext(cleaned)) {
            return null;
        }
        String safe = maskMobiles(cleaned);
        if (safe.length() <= 1) {
            return "*";
        }
        if (safe.length() == 2) {
            return safe.charAt(0) + "*";
        }
        return safe.charAt(0) + "*".repeat(Math.min(3, safe.length() - 2)) + safe.charAt(safe.length() - 1);
    }

    private BuyerFormMasked extractBuyerForm(JsonNode ordersNode) {
        if (ordersNode == null || !ordersNode.isArray()) {
            return new BuyerFormMasked(null, null);
        }
        String nameMasked = null;
        String mobileMasked = null;
        for (JsonNode orderLine : ordersNode) {
            JsonNode messagesNode = orderLine.path("buyer_messages");
            if (messagesNode.isMissingNode() || messagesNode.isNull()) {
                continue;
            }
            JsonNode parsedNode = messagesNode;
            if (messagesNode.isTextual()) {
                String rawText = messagesNode.asText().strip();
                if (rawText.isEmpty() || "{}".equals(rawText) || "[]".equals(rawText)) {
                    continue;
                }
                try {
                    parsedNode = objectMapper.readTree(rawText);
                } catch (Exception ex) {
                    continue;
                }
            }
            if (parsedNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = parsedNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = entry.getKey() == null ? "" : entry.getKey().strip().toLowerCase(Locale.ROOT);
                    String val = entry.getValue() == null || !entry.getValue().isValueNode()
                            ? null : cleanRawText(entry.getValue().asText());
                    if (val == null || isProviderCiphertext(val)) {
                        continue;
                    }
                    if (nameMasked == null && isNameKey(key)) {
                        nameMasked = truncate(maskPersonName(val), 64);
                    } else if (mobileMasked == null && isMobileKey(key)) {
                        mobileMasked = truncate(maskBuyerMobile(val), 32);
                    }
                }
            } else if (parsedNode.isArray()) {
                for (JsonNode entry : parsedNode) {
                    if (entry == null || !entry.isObject()) {
                        continue;
                    }
                    String key = firstNonBlank(text(entry, "k"), text(entry, "key"),
                            text(entry, "title"), text(entry, "name"));
                    String val = firstNonBlank(text(entry, "v"), text(entry, "value"), text(entry, "content"));
                    if (key == null || val == null || isProviderCiphertext(val)) {
                        continue;
                    }
                    String lowerKey = key.toLowerCase(Locale.ROOT);
                    if (nameMasked == null && isNameKey(lowerKey)) {
                        nameMasked = truncate(maskPersonName(val), 64);
                    } else if (mobileMasked == null && isMobileKey(lowerKey)) {
                        mobileMasked = truncate(maskBuyerMobile(val), 32);
                    }
                }
            }
            if (nameMasked != null && mobileMasked != null) {
                break;
            }
        }
        return new BuyerFormMasked(nameMasked, mobileMasked);
    }

    private static boolean isNameKey(String lowerKey) {
        return lowerKey.contains("姓名") || lowerKey.contains("名字") || lowerKey.contains("称呼")
                || lowerKey.contains("联系人") || "name".equals(lowerKey);
    }

    private static boolean isMobileKey(String lowerKey) {
        return lowerKey.contains("手机") || lowerKey.contains("电话") || lowerKey.contains("联系")
                || lowerKey.contains("mobile") || lowerKey.contains("phone") || lowerKey.contains("tel");
    }

    private static String maskBuyerMobile(String raw) {
        String compact = raw.replaceAll("[\\s-]+", "");
        if (compact.matches("^\\+?\\d{5,15}$")) {
            return MobileSupport.mask(compact);
        }
        return maskMobiles(raw);
    }

    private static boolean isProviderCiphertext(String value) {
        return value != null && value.length() >= 8 && value.startsWith("$") && value.endsWith("$");
    }

    private static JsonNode resolveFullOrderInfo(JsonNode root) {
        JsonNode candidate = root.path("data").path("full_order_info");
        if (candidate.isObject()) {
            return candidate;
        }
        candidate = root.path("full_order_info");
        if (candidate.isObject()) {
            return candidate;
        }
        if (root.path("order_info").isObject()) {
            return root;
        }
        return null;
    }

    private static FieldType mapKey(String key) {
        return switch (key) {
            case "业绩", "业绩归属", "业绩人" -> FieldType.PERFORMANCE_OWNER;
            case "渠道" -> FieldType.CHANNEL;
            case "类型", "客型", "客户类型" -> FieldType.CUSTOMER_TYPE;
            case "来源" -> FieldType.SOURCE;
            case "账号", "管家账号" -> FieldType.ACCOUNT;
            case "到院", "到院机构", "机构" -> FieldType.INSTITUTION;
            case "备注", "说明" -> FieldType.EXTRA_NOTE;
            default -> FieldType.EXTRA_NOTE;
        };
    }

    private static SplitResult splitPrimaryAndTrailing(String segment) {
        Matcher splitMatcher = VALUE_SPLIT_PATTERN.matcher(segment);
        if (!splitMatcher.find()) {
            return new SplitResult(segment, null);
        }
        String primary = trimDelimiters(segment.substring(0, splitMatcher.start()));
        String trailing = trimDelimiters(segment.substring(splitMatcher.end()));
        return new SplitResult(primary, trailing);
    }

    private static String trimDelimiters(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = cleanRawText(text);
        if (cleaned == null) {
            return null;
        }
        String stripped = TRAILING_DELIMITER_PATTERN.matcher(cleaned).replaceAll("").strip();
        stripped = stripped.replaceAll("^[\\s,，;；、|]+", "").strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String cleanRawText(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = ZERO_WIDTH_PATTERN.matcher(raw).replaceAll("").strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Instant parseProviderTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip(), PROVIDER_TIME).atZone(PROVIDER_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String cleaned = cleanRawText(value.asText());
        return cleaned == null || cleaned.isBlank() ? null : cleaned;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private enum FieldType {
        PERFORMANCE_OWNER,
        CHANNEL,
        CUSTOMER_TYPE,
        SOURCE,
        ACCOUNT,
        INSTITUTION,
        EXTRA_NOTE
    }

    private record KeyMatch(String key, int start, int end) {
    }

    private record SplitResult(String primary, String trailing) {
    }

    private record BuyerFormMasked(String nameMasked, String mobileMasked) {
    }

    public record ParsedMemo(
            String tradeMemoMasked,
            String performanceOwner,
            String channel,
            String customerType,
            String source,
            String accountName,
            String accountKey,
            String institutionName,
            String extraNote) {

        public static ParsedMemo empty() {
            return new ParsedMemo(null, null, null, null, null, null, null, null, null);
        }
    }

    public record ParsedOrderDetails(
            boolean hasProviderOrder,
            boolean hasRemarkNode,
            String providerOrderStatus,
            String providerStatusText,
            String payWayDesc,
            Instant orderedAt,
            Instant consignedAt,
            String buyerFormNameMasked,
            String buyerFormMobileMasked,
            ParsedMemo memo) {

        public static ParsedOrderDetails empty() {
            return new ParsedOrderDetails(false, false, null, null, null, null, null, null, null, ParsedMemo.empty());
        }
    }
}
