package com.whale.crm.annualreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.adapter.RealYouzanAnnualPullClient;
import com.whale.crm.annualreport.adapter.YouzanAnnualPullClient;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSyncRequest;
import com.whale.crm.annualreport.application.AnnualReportCopyEngine;
import com.whale.crm.annualreport.application.YouzanAnnualSnapshotService;
import com.whale.crm.annualreport.config.AnnualH5Properties;
import com.whale.crm.annualreport.domain.YouzanAnnualProfile;
import com.whale.crm.annualreport.domain.YouzanAnnualSnapshotRepository;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.YouzanRemoteClient;
import com.whale.crm.integration.youzan.application.YouzanTokenService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealYouzanAnnualPullClientTest {

    private static final long TENANT_ID = 1L;
    private static final String SHOP_ID = "shop_synth_01";
    private static final String TOKEN = "tok_synth_01";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void usersInfoQuerySendsValidResultTypesAndParsesPlatformWechatAndScrm300ByMobile() throws Exception {
        Fixture f = newFixture();
        JsonNode usersResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "latest_info": {
                      "nick_name": "星河最新昵称"
                    },
                    "user_list": [
                      {
                        "primitive_info": {
                          "gender": 2,
                          "nick_name": "星河原始昵称",
                          "yz_open_id": "yz_open_synth_01",
                          "platform_type": 0
                        },
                        "mobile_info": {
                          "mobile": "13800001001"
                        }
                      },
                      {
                        "platform_info": {
                          "weixin_open_id": "wx_openid_synth_01"
                        },
                        "wechat_info": {
                          "follow_time": 1700000000,
                          "is_fans": 1,
                          "wechat_type": 1,
                          "union_id": "wx_union_synth_01"
                        }
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
                    "name": "星河贵宾甲",
                    "mobile": "13800001001",
                    "gender": 2,
                    "trade_count": 3
                  }
                }
                """);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), any())).thenReturn(usersResp);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), any())).thenReturn(scrmResp);

        YouzanAnnualPullClient.BuyerProfileEnrichment result = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_synth_01", 9, 9001001L, null);

        assertThat(result.name()).isEqualTo("星河贵宾甲");
        assertThat(result.nickname()).isEqualTo("星河最新昵称");
        assertThat(result.genderHint()).isEqualTo("FEMALE");
        assertThat(result.weixinOpenId()).isEqualTo("wx_openid_synth_01");
        assertThat(result.weixinUnionId()).isEqualTo("wx_union_synth_01");
        assertThat(result.mobile()).isEqualTo("13800001001");
        assertThat(result.warning()).isNull();

        ArgumentCaptor<Map<String, Object>> userQueryCaptor = ArgumentCaptor.captor();
        verify(f.remote).callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), userQueryCaptor.capture());
        assertThat(userQueryCaptor.getValue())
                .containsEntry("yz_open_id", "yz_open_synth_01")
                .containsEntry("result_type_list", List.of(0, 1, 2, 9));

        ArgumentCaptor<Map<String, Object>> scrmQueryCaptor = ArgumentCaptor.captor();
        verify(f.remote).callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), scrmQueryCaptor.capture());
        assertThat(scrmQueryCaptor.getValue())
                .containsEntry("mobile", "13800001001")
                .containsEntry("fans_type", 0)
                .containsEntry("fans_id", 0);
        verify(f.remote, never()).callApi(any(), eq("youzan.scrm.customer.get"), eq("3.1.2"), any());
    }

    @Test
    void usersInfoQueryKeepsLegacyWeixinInfoAndTopLevelWechatInfoCompatibility() throws Exception {
        Fixture f = newFixture();
        JsonNode legacyResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "user_list": [
                      {
                        "weixin_info": {
                          "weixin_open_id": "wx_openid_legacy_01",
                          "union_id": "wx_union_legacy_01"
                        },
                        "primitive_info": {
                          "gender": 1,
                          "nick_name": "旧结构昵称"
                        }
                      }
                    ]
                  }
                }
                """);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), any())).thenReturn(legacyResp);

        YouzanAnnualPullClient.BuyerProfileEnrichment legacy = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_legacy_01");

        assertThat(legacy.nickname()).isEqualTo("旧结构昵称");
        assertThat(legacy.genderHint()).isEqualTo("MALE");
        assertThat(legacy.weixinOpenId()).isEqualTo("wx_openid_legacy_01");
        assertThat(legacy.weixinUnionId()).isEqualTo("wx_union_legacy_01");
        assertThat(legacy.warning()).isNull();

        JsonNode topLevelResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "wechat_info": {
                      "union_id": "wx_union_top_02"
                    },
                    "user_list": [
                      {
                        "platform_info": {
                          "weixin_open_id": "wx_openid_plat_02"
                        }
                      }
                    ]
                  }
                }
                """);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), any())).thenReturn(topLevelResp);

        YouzanAnnualPullClient.BuyerProfileEnrichment topLevel = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_top_02");

        assertThat(topLevel.weixinOpenId()).isEqualTo("wx_openid_plat_02");
        assertThat(topLevel.weixinUnionId()).isEqualTo("wx_union_top_02");
        assertThat(topLevel.warning()).isNull();
        verify(f.remote, never()).callApi(any(), eq("youzan.scrm.customer.get"), any(), any());
    }

    @Test
    void scrm300UsesFansTypeAndFansIdWhenMobileAbsentAndSkipsWhenNeitherExists() throws Exception {
        Fixture f = newFixture();
        JsonNode noMobileUsersResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "user_list": [
                      {
                        "platform_info": {
                          "weixin_open_id": "wx_openid_synth_02",
                          "union_id": "wx_union_synth_02"
                        },
                        "primitive_info": {
                          "gender": 0,
                          "nick_name": "测试粉丝乙"
                        }
                      }
                    ]
                  }
                }
                """);
        JsonNode scrmByFansResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "name": "粉丝实名乙",
                    "mobile": "13900002002",
                    "gender": 1
                  }
                }
                """);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), any()))
                .thenReturn(noMobileUsersResp);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"),
                eq(Map.of("fans_type", 9, "fans_id", 9001002L))))
                .thenReturn(scrmByFansResp);

        // 传入加密手机号密文应被忽略，自动降级为 fans_type + fans_id 查询
        YouzanAnnualPullClient.BuyerProfileEnrichment withFans = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_synth_02", 9, 9001002L, "$c3ludGhtb2JpbGU=$1$");

        assertThat(withFans.name()).isEqualTo("粉丝实名乙");
        assertThat(withFans.mobile()).isEqualTo("13900002002");
        assertThat(withFans.genderHint()).isEqualTo("MALE");
        assertThat(withFans.weixinOpenId()).isEqualTo("wx_openid_synth_02");
        assertThat(withFans.weixinUnionId()).isEqualTo("wx_union_synth_02");
        assertThat(withFans.warning()).isNull();

        // 当既无明文手机号又无有效 fans_type/fans_id 时，跳过 SCRM 调用且不产生误报告警
        YouzanAnnualPullClient.BuyerProfileEnrichment withoutKeys = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_synth_03", 0, 0L, null);

        assertThat(withoutKeys.name()).isNull();
        assertThat(withoutKeys.mobile()).isNull();
        assertThat(withoutKeys.warning()).isNull();
        verify(f.remote, times(1)).callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), any());
        verify(f.remote, never()).callApi(any(), eq("youzan.scrm.customer.get"), eq("3.1.2"), any());
    }

    @Test
    void scrm300FallsBackFromMobileToFansWhenFirstCallFailsOrReturnsEmpty() throws Exception {
        Fixture f = newFixture();
        JsonNode usersResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "user_list": [
                      {
                        "mobile_info": {
                          "mobile": "13700003003"
                        }
                      }
                    ]
                  }
                }
                """);
        JsonNode fansScrmResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "name": "回退命中客户",
                    "mobile": "13700003003",
                    "gender": 2
                  }
                }
                """);
        Map<String, Object> mobileQuery = Map.of("mobile", "13700003003", "fans_type", 0, "fans_id", 0);
        Map<String, Object> fansQuery = Map.of("fans_type", 9, "fans_id", 9001003L);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), any())).thenReturn(usersResp);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), eq(mobileQuery)))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "mobile lookup failed"))
                .thenReturn(objectMapper.readTree("{\"success\":true,\"code\":200,\"data\":{}}"));
        when(f.remote.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), eq(fansQuery)))
                .thenReturn(fansScrmResp);

        YouzanAnnualPullClient.BuyerProfileEnrichment afterException = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_synth_04", 9, 9001003L, null);
        assertThat(afterException.name()).isEqualTo("回退命中客户");
        assertThat(afterException.genderHint()).isEqualTo("FEMALE");
        assertThat(afterException.warning()).isNull();

        YouzanAnnualPullClient.BuyerProfileEnrichment afterEmpty = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_synth_04", 9, 9001003L, null);
        assertThat(afterEmpty.name()).isEqualTo("回退命中客户");
        assertThat(afterEmpty.warning()).isNull();
    }

    @Test
    void pullOrderPageExtractsFansKeysAndStripsYouzanEncryptedCiphertext() throws Exception {
        Fixture f = newFixture();
        JsonNode ordersResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "total_results": 2,
                    "full_order_info_list": [
                      {
                        "full_order_info": {
                          "order_info": {
                            "tid": "E202601010001",
                            "status": "TRADE_SUCCESS",
                            "created": "2026-05-01 21:00:00",
                            "pay_time": "2026-05-01 21:01:00",
                            "order_extra": {
                              "buyer_name": "$ZmFrZWJ1eWVybmFtZQ==$1$"
                            }
                          },
                          "pay_info": {
                            "payment": "199.00"
                          },
                          "buyer_info": {
                            "yz_open_id": "yz_open_cipher_01",
                            "fans_nickname": "$ZmFrZW5pY2tuYW1l==$1$",
                            "buyer_phone": "$ZmFrZXBob25l==$1$",
                            "fans_type": 9,
                            "fans_id": 9001099
                          },
                          "address_info": {
                            "receiver_name": "$ZmFrZXJlY2VpdmVy==$1$"
                          },
                          "orders": [
                            {
                              "item_id": "501",
                              "sku_id": "601",
                              "title": "星河测试护理项目",
                              "num": 1,
                              "payment": "199.00"
                            }
                          ]
                        }
                      },
                      {
                        "full_order_info": {
                          "order_info": {
                            "tid": "E202601010002",
                            "status": "TRADE_SUCCESS",
                            "created": "2026-05-02 10:00:00",
                            "pay_time": "2026-05-02 10:02:00"
                          },
                          "pay_info": {
                            "payment": "299.00"
                          },
                          "buyer_info": {
                            "yz_open_id": "yz_open_plain_02",
                            "fans_nickname": "明文测试昵称",
                            "buyer_phone": "13600004004",
                            "fans_type": 0,
                            "fans_id": 0
                          },
                          "address_info": {
                            "receiver_name": "明文收件人"
                          },
                          "orders": [
                            {
                              "item_id": "502",
                              "sku_id": "602",
                              "title": "星河明文测试项目",
                              "num": 1,
                              "payment": "299.00"
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
                """);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.trades.sold.get"), eq("4.0.2"), any())).thenReturn(ordersResp);

        YouzanAnnualPullClient.PulledOrderPage page = f.client.pullOrderPage(
                TENANT_ID, SHOP_ID, Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-03T00:00:00Z"), 1, 50);

        assertThat(page.orders()).hasSize(2);
        YouzanAnnualPullClient.PulledOrder encrypted = page.orders().get(0);
        assertThat(encrypted.buyerNickname()).isNull();
        assertThat(encrypted.buyerMobile()).isNull();
        assertThat(encrypted.receiverName()).isNull();
        assertThat(encrypted.buyerFansType()).isEqualTo(9);
        assertThat(encrypted.buyerFansId()).isEqualTo(9001099L);

        YouzanAnnualPullClient.PulledOrder plain = page.orders().get(1);
        assertThat(plain.buyerNickname()).isEqualTo("明文测试昵称");
        assertThat(plain.buyerMobile()).isEqualTo("13600004004");
        assertThat(plain.receiverName()).isEqualTo("明文收件人");
        assertThat(plain.buyerFansType()).isNull();
        assertThat(plain.buyerFansId()).isNull();
    }

    @Test
    void snapshotServiceFiltersEncryptedCiphertextAndForwardsFansKeysToPullClient() {
        YouzanAnnualPullClient pullClient = mock(YouzanAnnualPullClient.class);
        YouzanAnnualSnapshotRepository repository = mock(YouzanAnnualSnapshotRepository.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        AuditService auditService = mock(AuditService.class);
        String testAesKey = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        CryptoProperties cryptoProps = new CryptoProperties(testAesKey, "synth_test_pepper_2026");
        BlindIndex blindIndex = new BlindIndex(cryptoProps);
        MobileSupport mobileSupport = new MobileSupport(new AesGcmCipher(cryptoProps), blindIndex);
        AnnualH5Properties h5Props = new AnnualH5Properties(
                TENANT_ID, "http://localhost:5173", null, null, null, 3600L, null, null);

        QuickReplyTextMasker masker = new QuickReplyTextMasker();
        YouzanAnnualSnapshotService service = new YouzanAnnualSnapshotService(
                pullClient,
                repository,
                new AnnualReportCopyEngine(masker),
                mobileSupport,
                blindIndex,
                masker,
                idempotencyService,
                auditService,
                h5Props,
                objectMapper
        );

        when(idempotencyService.lookup(eq(TENANT_ID), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findLinkedCustomerIdByYzOpenId(eq(TENANT_ID), eq(SHOP_ID), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findCustomerIdsByMobileHash(eq(TENANT_ID), anyString())).thenReturn(List.of());

        Instant paidAt = Instant.parse("2026-05-10T12:00:00Z");
        YouzanAnnualPullClient.PulledOrder cipherOnlyOrder = new YouzanAnnualPullClient.PulledOrder(
                "E202605100001",
                "yz_open_cipher_svc_01",
                "TRADE_SUCCESS",
                true,
                paidAt.minusSeconds(60),
                paidAt,
                88000L,
                0L,
                "$Y2lwaGVybmljaw==$1$",
                "$Y2lwaGVycGhvbmU=$1$",
                "$Y2lwaGVycmVj==$1$",
                "星河密文防护测试项目",
                List.of(new YouzanAnnualPullClient.PulledOrderItem("701", "801", "星河密文防护测试项目", null, 1, 88000L)),
                9,
                9001088L
        );
        YouzanAnnualPullClient.PulledOrder cipherThenEnrichedOrder = new YouzanAnnualPullClient.PulledOrder(
                "E202605100002",
                "yz_open_cipher_svc_02",
                "TRADE_SUCCESS",
                true,
                paidAt.minusSeconds(60),
                paidAt,
                128000L,
                0L,
                "$Y2lwaGVybmljazI=$1$",
                "$Y2lwaGVycGhvbmUy$1$",
                "$Y2lwaGVycmVjMg==$1$",
                "星河补全测试项目",
                List.of(new YouzanAnnualPullClient.PulledOrderItem("702", "802", "星河补全测试项目", null, 1, 128000L)),
                9,
                9001089L
        );
        when(pullClient.pullOrderPage(eq(TENANT_ID), eq(SHOP_ID), any(), any(), eq(1), anyInt()))
                .thenReturn(new YouzanAnnualPullClient.PulledOrderPage(
                        List.of(cipherOnlyOrder, cipherThenEnrichedOrder), 2L, false));
        // 防御性测试：即便适配器异常返回密文，服务层也不得落为 displayName 或 mobile
        when(pullClient.fetchBuyerProfile(eq(TENANT_ID), eq(SHOP_ID), eq("yz_open_cipher_svc_01"),
                eq(9), eq(9001088L), isNull()))
                .thenReturn(new YouzanAnnualPullClient.BuyerProfileEnrichment(
                        "$Y2lwaGVybmFtZQ==$1$", "$Y2lwaGVybmljaw==$1$", "UNKNOWN",
                        null, null, "$Y2lwaGVybW9iaWxl$1$", null));
        when(pullClient.fetchBuyerProfile(eq(TENANT_ID), eq(SHOP_ID), eq("yz_open_cipher_svc_02"),
                eq(9), eq(9001089L), isNull()))
                .thenReturn(new YouzanAnnualPullClient.BuyerProfileEnrichment(
                        "星河真实姓名", "星河昵称", "FEMALE",
                        "wx_openid_synth_89", "wx_union_synth_89", "13500005005", null));

        CurrentUser employee = new CurrentUser(
                10L, TENANT_ID, "tester", "测试员工", "EMPLOYEE", null, Set.of("ADMIN"), Set.of(), Set.of());
        service.syncSnapshots(
                employee,
                new YouzanAnnualSnapshotSyncRequest(SHOP_ID, "2026-05-01", "2026-05-31", true, 10),
                "idem-synth-cipher-01"
        );

        ArgumentCaptor<YouzanAnnualProfile> profileCaptor = ArgumentCaptor.captor();
        verify(repository, times(2)).upsertProfile(profileCaptor.capture());
        List<YouzanAnnualProfile> saved = profileCaptor.getAllValues();

        YouzanAnnualProfile cipherOnlyProfile = saved.stream()
                .filter(p -> "yz_open_cipher_svc_01".equals(p.yzOpenId()))
                .findFirst()
                .orElseThrow();
        assertThat(cipherOnlyProfile.displayName()).isEqualTo("鲸选贵宾");
        assertThat(cipherOnlyProfile.displayName()).doesNotContain("$");
        assertThat(cipherOnlyProfile.mobileHash()).isNull();
        assertThat(cipherOnlyProfile.mobileMasked()).isNull();

        YouzanAnnualProfile enrichedProfile = saved.stream()
                .filter(p -> "yz_open_cipher_svc_02".equals(p.yzOpenId()))
                .findFirst()
                .orElseThrow();
        assertThat(enrichedProfile.displayName()).isEqualTo("星河真实姓名");
        assertThat(enrichedProfile.mobileMasked()).isEqualTo("135****5005");
        assertThat(enrichedProfile.mobileHash()).isNotBlank();
    }

    @Test
    void profileStripsEncryptedFieldsUsesPlainOrderMobileAndWarnsWhenAllScrmAttemptsFail() throws Exception {
        Fixture f = newFixture();
        JsonNode encryptedProfileResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "latest_info": {
                      "nick_name": "$ZW5jcnlwdGVkTmljaw==$1$"
                    },
                    "user_list": [
                      {
                        "mobile_info": {
                          "mobile": "$ZW5jcnlwdGVkTW9iaWxl$1$"
                        },
                        "primitive_info": {
                          "nick_name": "$ZW5jcnlwdGVkUHJpbQ==$2$",
                          "gender": 0
                        }
                      }
                    ]
                  }
                }
                """);
        JsonNode encryptedScrmResp = objectMapper.readTree("""
                {
                  "success": true,
                  "code": 200,
                  "data": {
                    "name": "$ZW5jcnlwdGVkTmFtZQ==$1$",
                    "mobile": "$ZW5jcnlwdGVkU2NybU1vYg==$1$",
                    "gender": 0
                  }
                }
                """);
        Map<String, Object> orderMobileQuery = Map.of("mobile", "13400006006", "fans_type", 0, "fans_id", 0);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.users.info.query"), eq("1.0.0"), any()))
                .thenReturn(encryptedProfileResp);
        when(f.remote.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), eq(orderMobileQuery)))
                .thenReturn(encryptedScrmResp);

        YouzanAnnualPullClient.BuyerProfileEnrichment stripped = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_cipher_prof_01", null, null, "13400006006");

        assertThat(stripped.name()).isNull();
        assertThat(stripped.nickname()).isNull();
        assertThat(stripped.mobile()).isNull();
        assertThat(stripped.warning()).isNull();

        // 当 mobile 与 fans 两次 SCRM 调用均失败时，仅输出一条告警
        when(f.remote.callApi(eq(TOKEN), eq("youzan.scrm.customer.get"), eq("3.0.0"), any()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "scrm unavailable"));
        YouzanAnnualPullClient.BuyerProfileEnrichment failed = f.client.fetchBuyerProfile(
                TENANT_ID, SHOP_ID, "yz_open_cipher_prof_02", 9, 9001055L, "13400006006");
        assertThat(failed.warning()).isEqualTo("youzan.scrm.customer.get 未开通或暂不可用");
    }

    @Test
    void isYouzanCiphertextDistinguishesCiphertextFromPlaintext() {
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("$abc123==$1$")).isTrue();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("$138$abc+/==$2$")).isTrue();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("$a$1$")).isTrue();

        assertThat(YouzanAnnualPullClient.isYouzanCiphertext(null)).isFalse();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("")).isFalse();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("$VIP$")).isFalse();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("$abc$$")).isFalse();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("$$1$")).isFalse();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("星河昵称")).isFalse();
        assertThat(YouzanAnnualPullClient.isYouzanCiphertext("13800001001")).isFalse();
    }

    private Fixture newFixture() {
        IntegrationRuntimeConfigService runtimeConfig = mock(IntegrationRuntimeConfigService.class);
        YouzanTokenService tokenService = mock(YouzanTokenService.class);
        YouzanRemoteClient remote = mock(YouzanRemoteClient.class);
        when(runtimeConfig.youzanRealEnabled(TENANT_ID)).thenReturn(true);
        when(tokenService.accessToken(TENANT_ID, SHOP_ID)).thenReturn(TOKEN);
        RealYouzanAnnualPullClient client = new RealYouzanAnnualPullClient(runtimeConfig, tokenService, remote);
        return new Fixture(client, remote);
    }

    private record Fixture(RealYouzanAnnualPullClient client, YouzanRemoteClient remote) {
    }
}
