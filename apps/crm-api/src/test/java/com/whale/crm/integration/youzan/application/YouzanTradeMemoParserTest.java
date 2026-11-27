package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class YouzanTradeMemoParserTest {

    private final YouzanTradeMemoParser parser = new YouzanTradeMemoParser(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesStandardMemoWithMultiSpaceExtraNoteAndBuyerMessages() {
        String payload = """
                {
                  "code": 200,
                  "success": true,
                  "data": {
                    "full_order_info": {
                      "remark_info": {
                        "buyer_message": "",
                        "star": 0,
                        "trade_memo": "业绩：陈顾问，渠道：koc，类型：新客，来源：koc小星，账号：紫紫模拟，到院：杭州示范院  可以延期核销申请半个月"
                      },
                      "pay_info": {
                        "pay_way_desc": "微信支付",
                        "payment": "4999.00"
                      },
                      "orders": [
                        {
                          "item_id": 4898748343,
                          "sku_id": 15042235417,
                          "title": "2026示范卡",
                          "buyer_messages": "{\\"姓名\\":\\"林小美\\",\\"手机号\\":\\"13900007633\\"}"
                        }
                      ],
                      "order_info": {
                        "tid": "E2026111100000001",
                        "status": "WAIT_BUYER_CONFIRM_GOODS",
                        "status_str": "已发货",
                        "pay_type_desc": "微信支付",
                        "created": "2026-11-11 15:40:31",
                        "consign_time": "2026-11-11 15:40:49"
                      }
                    }
                  }
                }
                """;

        YouzanTradeMemoParser.ParsedOrderDetails details = parser.parseProviderPayload(payload);

        assertThat(details.hasProviderOrder()).isTrue();
        assertThat(details.hasRemarkNode()).isTrue();
        assertThat(details.providerOrderStatus()).isEqualTo("WAIT_BUYER_CONFIRM_GOODS");
        assertThat(details.providerStatusText()).isEqualTo("已发货");
        assertThat(details.payWayDesc()).isEqualTo("微信支付");
        assertThat(details.orderedAt()).isEqualTo(Instant.parse("2026-11-11T07:40:31Z"));
        assertThat(details.consignedAt()).isEqualTo(Instant.parse("2026-11-11T07:40:49Z"));
        assertThat(details.buyerFormNameMasked()).isEqualTo("林*美");
        assertThat(details.buyerFormMobileMasked()).isEqualTo("139****7633");

        YouzanTradeMemoParser.ParsedMemo memo = details.memo();
        assertThat(memo.performanceOwner()).isEqualTo("陈顾问");
        assertThat(memo.channel()).isEqualTo("koc");
        assertThat(memo.customerType()).isEqualTo("新客");
        assertThat(memo.source()).isEqualTo("koc小星");
        assertThat(memo.accountName()).isEqualTo("紫紫模拟");
        assertThat(memo.accountKey()).isEqualTo("紫紫模拟");
        assertThat(memo.institutionName()).isEqualTo("杭州示范院");
        assertThat(memo.extraNote()).isEqualTo("可以延期核销申请半个月");
        assertThat(memo.tradeMemoMasked()).contains("账号：紫紫模拟");
    }

    @Test
    void handlesAsciiDelimitersZeroWidthCharsAndMasksEmbeddedMobile() {
        String raw = "紧急单  业绩:李顾问, 渠道:小红书; 账号:\u200BAlice  ，到院:上海示范院  联系13812345678确认";
        YouzanTradeMemoParser.ParsedMemo memo = parser.parseTradeMemo(raw);

        assertThat(memo.performanceOwner()).isEqualTo("李顾问");
        assertThat(memo.channel()).isEqualTo("小红书");
        assertThat(memo.accountName()).isEqualTo("Alice");
        assertThat(memo.accountKey()).isEqualTo("alice");
        assertThat(memo.institutionName()).isEqualTo("上海示范院");
        assertThat(memo.extraNote()).isEqualTo("紧急单；联系138****5678确认");
        assertThat(memo.tradeMemoMasked()).doesNotContain("13812345678").contains("138****5678");
    }

    @Test
    void handlesMemoWithoutAccountAndIgnoresProviderCiphertextInBuyerMessages() {
        String payload = """
                {
                  "full_order_info": {
                    "remark_info": {
                      "trade_memo": "客户要求延后一周到院"
                    },
                    "orders": [
                      {
                        "buyer_messages": [{"k":"姓名","v":"$CIPHER_NAME$1$"},{"k":"手机号","v":"$CIPHER_PHONE$1$"}]
                      }
                    ],
                    "order_info": {
                      "tid": "E2026111100000002",
                      "status": "TRADE_SUCCESS"
                    }
                  }
                }
                """;

        YouzanTradeMemoParser.ParsedOrderDetails details = parser.parseProviderPayload(payload);

        assertThat(details.hasProviderOrder()).isTrue();
        assertThat(details.hasRemarkNode()).isTrue();
        assertThat(details.buyerFormNameMasked()).isNull();
        assertThat(details.buyerFormMobileMasked()).isNull();
        assertThat(details.memo().accountName()).isNull();
        assertThat(details.memo().accountKey()).isNull();
        assertThat(details.memo().extraNote()).isEqualTo("客户要求延后一周到院");
    }

    @Test
    void detectsMissingRemarkInfoNode() {
        String payload = """
                {
                  "full_order_info": {
                    "order_info": {"tid": "E-NO-REMARK", "status": "TRADE_PAID"}
                  }
                }
                """;
        YouzanTradeMemoParser.ParsedOrderDetails details = parser.parseProviderPayload(payload);

        assertThat(details.hasProviderOrder()).isTrue();
        assertThat(details.hasRemarkNode()).isFalse();
        assertThat(details.memo().accountKey()).isNull();
    }
}
