package com.whale.crm.integration.youzan.customer.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.adapter.YouzanApiException;
import com.whale.crm.integration.youzan.adapter.YouzanRemoteClient;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialYouzanCustomerPullClientTest {

    private static final long TENANT_ID = 1L;
    private static final String SHOP_ID = "SHOP-UNIT-001";
    private static final String TOKEN = "test-access-token";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private YouzanTokenService tokenService;
    private YouzanRemoteClient remoteClient;
    private OfficialYouzanCustomerPullClient client;

    @BeforeEach
    void setUp() {
        tokenService = mock(YouzanTokenService.class);
        remoteClient = mock(YouzanRemoteClient.class);
        when(tokenService.accessToken(TENANT_ID, SHOP_ID)).thenReturn(TOKEN);
        client = new OfficialYouzanCustomerPullClient(tokenService, remoteClient, "1.0.0", "4.0.2", 0L);
    }

    @Test
    void pullScrmPage_callsCustomerList100WithPageNoStripsCiphertextAndSkipsMobileShowName() throws Exception {
        JsonNode okResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "total": 25,
                    "page_no": 1,
                    "page_size": 20,
                    "record_list": [
                      {
                        "yz_open_id": "YZ-OPEN-001",
                        "name": "$abcDEF123456$1$",
                        "show_name": "林女士",
                        "nick_name": "$cipherNick99$1$",
                        "mobile": "$encryptedMobile88$1$",
                        "gender": 2,
                        "birthday": "1994-05-12 00:00:00",
                        "created_at": 1780280400,
                        "fans_info": {"fans_type": 1, "fans_id": 88001},
                        "remark": "测试备注"
                      },
                      {
                        "yz_open_id": "YZ-OPEN-002",
                        "name": "陈先生",
                        "nick_name": "小陈",
                        "mobile": "139-8888-0002",
                        "gender": 1,
                        "birthday": "$cipherBday$1$",
                        "created_at": "2026-06-05 10:30:00",
                        "fans_type": 1,
                        "fans_id": 88002,
                        "remark": "$cipherRemark$1$"
                      },
                      {
                        "yz_open_id": "YZ-OPEN-003",
                        "name": "",
                        "show_name": "13800138003",
                        "mobile": "13800138003",
                        "gender": 0,
                        "created_at": 1780280500
                      }
                    ]
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.list"), eq("1.0.0"), anyMap()))
                .thenReturn(okResp);

        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-30T23:59:59Z");
        YouzanCustomerPullClient.YouzanCustomerPage page = client.pullScrmPage(TENANT_ID, SHOP_ID, start, end, 1, 20);

        assertThat(page.totalResults()).isEqualTo(25L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).hasSize(3);

        YouzanCustomerPullClient.YouzanPulledCustomer first = page.items().get(0);
        assertThat(first.yzOpenId()).isEqualTo("YZ-OPEN-001");
        assertThat(first.name()).isEqualTo("林女士");
        assertThat(first.nickname()).isEqualTo("林女士");
        assertThat(first.mobile()).isNull();
        assertThat(first.genderHint()).isEqualTo("FEMALE");
        assertThat(first.birthday()).isEqualTo(LocalDate.of(1994, 5, 12));
        assertThat(first.createdAt()).isEqualTo(Instant.ofEpochSecond(1780280400L));
        assertThat(first.fansType()).isEqualTo(1);
        assertThat(first.fansId()).isEqualTo(88001L);
        assertThat(first.remark()).isEqualTo("测试备注");

        YouzanCustomerPullClient.YouzanPulledCustomer second = page.items().get(1);
        assertThat(second.yzOpenId()).isEqualTo("YZ-OPEN-002");
        assertThat(second.name()).isEqualTo("陈先生");
        assertThat(second.nickname()).isEqualTo("小陈");
        assertThat(second.mobile()).isEqualTo("13988880002");
        assertThat(second.genderHint()).isEqualTo("MALE");
        assertThat(second.birthday()).isNull();
        assertThat(second.remark()).isNull();

        YouzanCustomerPullClient.YouzanPulledCustomer third = page.items().get(2);
        assertThat(third.yzOpenId()).isEqualTo("YZ-OPEN-003");
        assertThat(third.name()).isNull();
        assertThat(third.nickname()).isNull();
        assertThat(third.mobile()).isEqualTo("13800138003");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(remoteClient).callApi(eq(TOKEN), eq("youzan.scrm.customer.list"), eq("1.0.0"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("page_no", 1)
                .containsEntry("page_size", 20)
                .containsEntry("created_at_start", start.getEpochSecond())
                .containsEntry("created_at_end", end.getEpochSecond())
                .doesNotContainKey("page");
    }

    @Test
    void pullScrmPage_throwsWindowTooLargeWhenTotalHitsCapOrApiReturns142710032() throws Exception {
        JsonNode capped = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "total": 10000,
                    "record_list": []
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.list"), eq("1.0.0"), anyMap()))
                .thenReturn(capped);

        assertThatThrownBy(() -> client.pullScrmPage(TENANT_ID, SHOP_ID, null, Instant.parse("2026-06-30T00:00:00Z"), 1, 20))
                .isInstanceOf(YouzanCustomerWindowTooLargeException.class)
                .satisfies(ex -> assertThat(((YouzanCustomerWindowTooLargeException) ex).totalResults()).isEqualTo(10000L));

        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.list"), eq("1.0.0"), anyMap()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞接口返回业务失败: 142710032"));

        assertThatThrownBy(() -> client.pullScrmPage(TENANT_ID, SHOP_ID, null, Instant.parse("2026-06-30T00:00:00Z"), 500, 20))
                .isInstanceOf(YouzanCustomerWindowTooLargeException.class);
    }

    @Test
    void pullScrmPage_throwsFriendlyPendingBusinessRuleOn4204PermissionError() {
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.list"), eq("1.0.0"), anyMap()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "有赞网关返回错误: 4204"));

        assertThatThrownBy(() -> client.pullScrmPage(TENANT_ID, SHOP_ID, null, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);
                    assertThat(be.getMessage())
                            .contains("youzan.scrm.customer.list/1.0.0")
                            .contains("店铺客户信息同步")
                            .contains("4204");
                });
    }

    @Test
    void pullOrderBuyersPage_parsesOrdersFetchesDetailWhenOpenIdMissingAndDetectsOversizedWindow() throws Exception {
        JsonNode tradesResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "total_results": 2,
                    "full_order_info_list": [
                      {
                        "full_order_info": {
                          "order_info": {"tid": "E202606010001", "created": "2026-06-02 12:00:00", "order_extra": {"buyer_name": "王小美"}},
                          "buyer_info": {"yz_open_id": "YZ-BUYER-001", "fans_nickname": "美美", "buyer_phone": "13900001001", "fans_type": 1, "fans_id": 5001},
                          "address_info": {"receiver_name": "收件人A", "receiver_tel": "$cipherTel$1$"}
                        }
                      },
                      {
                        "full_order_info": {
                          "order_info": {"tid": "E202606010002", "created": "2026-06-03 14:00:00"},
                          "buyer_info": {},
                          "address_info": {"receiver_name": "$cipherRecv$1$"}
                        }
                      }
                    ]
                  }
                }
                """);
        JsonNode detailResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "full_order_info": {
                      "order_info": {"tid": "E202606010002", "created": "2026-06-03 14:00:00"},
                      "buyer_info": {"yz_open_id": "YZ-BUYER-002", "fans_nickname": "补全昵称", "fans_type": 1, "fans_id": 5002},
                      "address_info": {"receiver_name": "李静", "receiver_tel": "13900001002"}
                    }
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.trades.sold.get"), eq("4.0.2"), anyMap()))
                .thenReturn(tradesResp);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.trade.get"), eq("4.0.2"), eq(Map.of("tid", "E202606010002"))))
                .thenReturn(detailResp);

        YouzanCustomerPullClient.YouzanCustomerPage page = client.pullOrderBuyersPage(
                TENANT_ID, SHOP_ID, Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"), 1, 50);

        assertThat(page.totalResults()).isEqualTo(2L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).yzOpenId()).isEqualTo("YZ-BUYER-001");
        assertThat(page.items().get(0).name()).isEqualTo("王小美");
        assertThat(page.items().get(0).mobile()).isEqualTo("13900001001");
        assertThat(page.items().get(1).yzOpenId()).isEqualTo("YZ-BUYER-002");
        assertThat(page.items().get(1).name()).isEqualTo("李静");
        assertThat(page.items().get(1).mobile()).isEqualTo("13900001002");

        JsonNode tooMany = objectMapper.readTree("""
                {"success": true, "code": 200, "data": {"total_results": 5001, "full_order_info_list": []}}
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.trades.sold.get"), eq("4.0.2"), anyMap()))
                .thenReturn(tooMany);
        assertThatThrownBy(() -> client.pullOrderBuyersPage(
                TENANT_ID, SHOP_ID, Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"), 1, 50))
                .isInstanceOf(YouzanCustomerWindowTooLargeException.class);
    }

    @Test
    void enrichCustomer_combinesUsersInfoQueryAndScrmCustomerGetAndRefreshesExpiredToken() throws Exception {
        when(tokenService.refreshAccessToken(TENANT_ID, SHOP_ID)).thenReturn("refreshed-token");
        JsonNode usersResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "latest_info": {"nick_name": "$cipherLatest$1$"},
                    "user_list": [
                      {
                        "mobile_info": {"mobile": "13977770001"},
                        "primitive_info": {"nick_name": "微信昵称小星", "gender": 2}
                      }
                    ]
                  }
                }
                """);
        JsonNode scrmResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "name": "赵星怡",
                    "mobile": "13977770001",
                    "gender": 2,
                    "birthday": "1995-08-20"
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), anyMap()))
                .thenThrow(new YouzanApiException(ErrorCode.UNAUTHORIZED, "token expired", 4201, true, false, null));
        when(remoteClient.callApi(eq("refreshed-token"), eq("youzan.users.info.query"), eq("1.0.0"), anyMap()))
                .thenReturn(usersResp);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), anyMap()))
                .thenReturn(scrmResp);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.tag.relation.get"), eq("4.0.0"), anyMap()))
                .thenReturn(objectMapper.readTree("{\"success\":true,\"code\":200,\"data\":{\"tags\":[]}}"));

        YouzanCustomerPullClient.YouzanCustomerEnrichment enriched = client.enrichCustomer(
                TENANT_ID, SHOP_ID, "YZ-OPEN-ENRICH", 1, 9001L, null);

        assertThat(enriched.name()).isEqualTo("赵星怡");
        assertThat(enriched.nickname()).isEqualTo("微信昵称小星");
        assertThat(enriched.mobile()).isEqualTo("13977770001");
        assertThat(enriched.genderHint()).isEqualTo("FEMALE");
        assertThat(enriched.birthday()).isEqualTo(LocalDate.of(1995, 8, 20));
        assertThat(enriched.warning()).isNull();
        verify(tokenService, times(1)).refreshAccessToken(TENANT_ID, SHOP_ID);
    }

    @Test
    void enrichCustomer_returnsNonFatalWarningWhenApisUnavailable() {
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), anyMap()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "4005"));
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), anyMap()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "4005"));
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.tag.relation.get"), eq("4.0.0"), anyMap()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "4005"));

        YouzanCustomerPullClient.YouzanCustomerEnrichment enriched = client.enrichCustomer(
                TENANT_ID, SHOP_ID, "YZ-OPEN-WARN", 1, 9002L, "13977770002");

        assertThat(enriched.mobile()).isEqualTo("13977770002");
        assertThat(enriched.warning())
                .contains("youzan.users.info.query")
                .contains("youzan.scrm.customer.get")
                .contains("youzan.scrm.tag.relation.get");
        assertThat(YouzanCustomerPullClient.stripCiphertext("$abc12345$1$")).isNull();
        assertThat(YouzanCustomerPullClient.stripCiphertext("普通姓名")).isEqualTo("普通姓名");
    }

    @Test
    void pullScrmAndOrderBuyersExtractCityTagsRemarkAndIgnoreGenericMunicipalityOrCiphertext() throws Exception {
        JsonNode scrmResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "total": 2,
                    "record_list": [
                      {
                        "yz_open_id": "YZ-DETAIL-001",
                        "name": "周清雅",
                        "mobile": "13800138099",
                        "remark": "偏好光电抗衰项目",
                        "contact_address": {
                          "province": "浙江省",
                          "city": "杭州市",
                          "district": "西湖区",
                          "address_detail": "绝不应采集的详细街道门牌123号"
                        },
                        "tags": [
                          {"tag_id": 1, "tag_name": "贵宾客户"},
                          {"tag_id": 2, "tag_name": "光电高复购"},
                          {"tag_id": 3, "tag_name": "$cipherTag$1$"}
                        ]
                      },
                      {
                        "yz_open_id": "YZ-DETAIL-002",
                        "name": "孙女士",
                        "area": {"province": "上海市", "city": "市辖区", "county": "浦东新区"},
                        "tag_list": ["皮肤管理", "皮肤管理"]
                      }
                    ]
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.list"), eq("1.0.0"), anyMap()))
                .thenReturn(scrmResp);

        YouzanCustomerPullClient.YouzanCustomerPage scrmPage = client.pullScrmPage(
                TENANT_ID, SHOP_ID, null, null, 1, 20);
        assertThat(scrmPage.items()).hasSize(2);
        assertThat(scrmPage.items().get(0).remark()).isEqualTo("偏好光电抗衰项目");
        assertThat(scrmPage.items().get(0).provinceName()).isEqualTo("浙江省");
        assertThat(scrmPage.items().get(0).cityName()).isEqualTo("杭州市");
        assertThat(scrmPage.items().get(0).districtName()).isEqualTo("西湖区");
        assertThat(scrmPage.items().get(0).tagNames()).containsExactly("贵宾客户", "光电高复购");
        assertThat(scrmPage.items().get(1).provinceName()).isEqualTo("上海市");
        assertThat(scrmPage.items().get(1).cityName()).isEqualTo("上海市");
        assertThat(scrmPage.items().get(1).districtName()).isEqualTo("浦东新区");
        assertThat(scrmPage.items().get(1).tagNames()).containsExactly("皮肤管理");

        JsonNode tradesResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "total_results": 1,
                    "full_order_info_list": [
                      {
                        "full_order_info": {
                          "order_info": {"tid": "E202607010001", "created": "2026-07-01 10:00:00"},
                          "buyer_info": {"yz_open_id": "YZ-ORDER-CITY-1", "fans_nickname": "林小鹿"},
                          "address_info": {
                            "delivery_province": "广东省",
                            "delivery_city": "深圳市",
                            "delivery_district": "南山区",
                            "delivery_address": "某保密小区8栋808"
                          }
                        }
                      }
                    ]
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.trades.sold.get"), eq("4.0.2"), anyMap()))
                .thenReturn(tradesResp);

        YouzanCustomerPullClient.YouzanCustomerPage orderPage = client.pullOrderBuyersPage(
                TENANT_ID, SHOP_ID, Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-10T00:00:00Z"), 1, 50);
        assertThat(orderPage.items().get(0).provinceName()).isEqualTo("广东省");
        assertThat(orderPage.items().get(0).cityName()).isEqualTo("深圳市");
        assertThat(orderPage.items().get(0).districtName()).isEqualTo("南山区");
    }

    @Test
    void enrichCustomerExtractsRemarkCityTagsAndFallsBackToTagRelationApiWhenTagsEmpty() throws Exception {
        JsonNode usersResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "user_list": [
                      {
                        "mobile_info": {"mobile": "13966668888"},
                        "primitive_info": {"nick_name": "云朵", "gender": 2, "province": "北京市", "city": "市辖区"}
                      }
                    ]
                  }
                }
                """);
        JsonNode scrmResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "name": "许云朵",
                    "mobile": "13966668888",
                    "gender": 2,
                    "birthday": "1992-03-18 00:00:00",
                    "remark": "有赞黑金会员，周末预约偏好",
                    "contact_address": {"province": "浙江省", "city": "杭州市"}
                  }
                }
                """);
        JsonNode tagRelResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "tags": [
                      {"tag_id": 101, "tag_name": "黑金会员"},
                      {"tag_id": 102, "tag_name": "高意向"}
                    ]
                  }
                }
                """);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), anyMap()))
                .thenReturn(usersResp);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), anyMap()))
                .thenReturn(scrmResp);
        when(remoteClient.callApi(eq(TOKEN), eq("youzan.scrm.tag.relation.get"), eq("4.0.0"), anyMap()))
                .thenReturn(tagRelResp);

        YouzanCustomerPullClient.YouzanCustomerEnrichment enriched = client.enrichCustomer(
                TENANT_ID, SHOP_ID, "YZ-TAG-REL-01", 1, 8001L, null);

        assertThat(enriched.name()).isEqualTo("许云朵");
        assertThat(enriched.remark()).isEqualTo("有赞黑金会员，周末预约偏好");
        assertThat(enriched.cityName()).isEqualTo("杭州市");
        assertThat(enriched.tagNames()).containsExactly("黑金会员", "高意向");
        assertThat(enriched.warning()).isNull();
        verify(remoteClient, times(1)).callApi(eq(TOKEN), eq("youzan.scrm.tag.relation.get"), eq("4.0.0"), anyMap());
    }
}
