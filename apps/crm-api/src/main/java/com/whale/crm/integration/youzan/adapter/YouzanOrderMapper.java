package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class YouzanOrderMapper {
    private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PROVIDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PAID_STATUSES = Set.of("TRADE_PAID", "WAIT_CONFIRM",
            "WAIT_SELLER_SEND_GOODS", "WAIT_BUYER_CONFIRM_GOODS", "TRADE_SUCCESS");

    public YouzanOrderEvent toOrderEvent(String shopId, String eventId, JsonNode root) {
        JsonNode full = root == null ? null : root.path("data").path("full_order_info");
        return toOrderEventFromFullOrderInfo(shopId, eventId, full);
    }

    public YouzanOrderEvent toOrderEventFromFullOrderInfo(String shopId, String eventId, JsonNode full) {
        if (full == null || !full.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞订单响应缺少full_order_info");
        }
        JsonNode info = full.path("order_info");
        JsonNode pay = full.path("pay_info");
        JsonNode buyer = full.path("buyer_info");
        String tid = requiredText(info, "tid");
        String status = requiredText(info, "status");
        Instant updatedAt = requiredTime(info, "update_time");
        String actualEventId = eventId == null || eventId.isBlank()
                ? "PULL:" + tid + ":" + updatedAt.toEpochMilli() : eventId;
        String eventType = PAID_STATUSES.contains(status) ? "ORDER_PAID" : "ORDER_PENDING";
        Long amountFen = moneyToFen(text(pay, "payment"));
        Instant paidAt = optionalTime(pay, info, "pay_time");
        String tradeNo = singleTradeNo(pay);
        String customerId = text(buyer, "yz_open_id");
        List<YouzanOrderLine> lines = new ArrayList<>();
        for (JsonNode item : full.path("orders")) {
            String itemId = requiredText(item, "item_id");
            String skuId = text(item, "sku_id");
            lines.add(new YouzanOrderLine(itemId + ":" + (skuId == null ? "0" : skuId),
                    item.path("num").asInt()));
        }
        return new YouzanOrderEvent(actualEventId, eventType, shopId, tid, customerId, tradeNo, status,
                amountFen, paidAt, updatedAt, "REGULAR", null, null, "PLATFORM", null, lines);
    }

    public boolean hasCompleteDetail(JsonNode full) {
        if (full == null || !full.isObject()) {
            return false;
        }
        JsonNode info = full.path("order_info");
        JsonNode pay = full.path("pay_info");
        JsonNode buyer = full.path("buyer_info");
        String tid = text(info, "tid");
        String status = text(info, "status");
        String updateTime = text(info, "update_time");
        if (tid == null || status == null || !isValidTime(updateTime)) {
            return false;
        }
        if (text(buyer, "yz_open_id") == null || !isValidMoney(text(pay, "payment"))) {
            return false;
        }
        JsonNode orders = full.path("orders");
        if (!orders.isArray() || orders.isEmpty()) {
            return false;
        }
        for (JsonNode item : orders) {
            if (text(item, "item_id") == null || item.path("num").asInt(0) <= 0) {
                return false;
            }
        }
        String payTime = text(info, "pay_time");
        if (payTime == null) {
            payTime = text(pay, "pay_time");
        }
        if (payTime != null && !isValidTime(payTime)) {
            return false;
        }
        if (PAID_STATUSES.contains(status)) {
            if (payTime == null) {
                return false;
            }
            boolean hasTransaction = hasNonBlankElement(pay.path("transaction"));
            boolean hasPhasePayments = pay.path("phase_payments").isArray() && !pay.path("phase_payments").isEmpty();
            if (!hasTransaction && !hasPhasePayments) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidMoney(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            moneyToFen(value);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    private static boolean hasNonBlankElement(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return false;
        }
        for (JsonNode element : array) {
            if (element != null && !element.isNull() && !element.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String singleTradeNo(JsonNode pay) {
        JsonNode transactions = pay.path("transaction");
        if (!pay.path("phase_payments").isEmpty() || !transactions.isArray() || transactions.size() != 1) {
            return null;
        }
        String value = transactions.get(0).asText();
        return value == null || value.isBlank() ? null : value;
    }

    static Long moneyToFen(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞订单金额格式无效");
        }
    }

    private static Instant requiredTime(JsonNode node, String field) {
        String value = requiredText(node, field);
        return parseTime(value, field);
    }

    private static Instant optionalTime(JsonNode pay, JsonNode info, String field) {
        String value = text(info, field);
        if (value == null) {
            value = text(pay, field);
        }
        return value == null ? null : parseTime(value, field);
    }

    private static Instant parseTime(String value, String field) {
        try {
            return LocalDateTime.parse(value, PROVIDER_TIME).atZone(PROVIDER_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞订单时间格式无效: " + field);
        }
    }

    private static boolean isValidTime(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            LocalDateTime.parse(value, PROVIDER_TIME);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞订单响应缺少必填字段: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
