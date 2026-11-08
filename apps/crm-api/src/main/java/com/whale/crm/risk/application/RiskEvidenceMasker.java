package com.whale.crm.risk.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RiskEvidenceMasker {
    private static final int MAX_LENGTH = 16000;
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "mobile", "phone", "nickname", "name", "displayname", "contactname", "address", "idcard",
            "buyername", "buyer_phone", "fans_nickname", "receiver_name", "receiver_tel", "receiver_phone",
            "delivery_address", "buyer_name", "id_card_number", "id_card_name", "user_name", "user_tel",
            "address_detail", "salesman_mobile", "salesman_nick_name", "service_guide_name", "weixin_openid",
            "open_user_id", "outer_user_id", "bankaccount", "bank_account", "accountnumber", "account_number");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9A-Za-z])\\d{6}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![0-9A-Za-z])");
    private static final Pattern LONG_ACCOUNT = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    private final ObjectMapper objectMapper;

    public RiskEvidenceMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String mask(String value) {
        if (value == null) {
            return null;
        }
        String input = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
        String masked = input;
        String stripped = input.stripLeading();
        if (stripped.startsWith("{") || stripped.startsWith("[")) {
            try {
                JsonNode root = objectMapper.readTree(input);
                maskNode(root);
                masked = objectMapper.writeValueAsString(root);
            } catch (Exception ignored) {
                masked = input;
            }
        }
        masked = PHONE.matcher(masked).replaceAll("$1****$2");
        masked = EMAIL.matcher(masked).replaceAll("***@***");
        masked = ID_CARD.matcher(masked).replaceAll("******************");
        return LONG_ACCOUNT.matcher(masked).replaceAll("****");
    }

    private void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode child = object.get(name);
                if (SENSITIVE_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, "***");
                } else {
                    maskNode(child);
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::maskNode);
        }
    }
}
