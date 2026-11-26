package com.whale.crm.annualreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.annualreport.adapter.WechatOfficialOAuthClient;
import com.whale.crm.annualreport.adapter.YouzanAnnualPullClient;
import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.annual-h5.wechat-mp.enabled=true",
        "app.annual-h5.wechat-mp.app-id=wx_test_mp_appid",
        "app.annual-h5.wechat-mp.app-secret=wx_test_mp_secret",
        "app.annual-h5.wechat-mp.callback-url=http://localhost:8080/api/v1/public/annual-reports/auth/wechat/callback",
        "app.annual-h5.sms.mode=MOCK",
        "app.annual-h5.sms.cooldown-seconds=1"
})
class YouzanAnnualSnapshotAndAuthApiIT extends AbstractMysqlIT {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private YouzanAnnualPullClient pullClient;

    @MockitoBean
    private WechatOfficialOAuthClient wechatOAuthClient;

    @Test
    void youzanSnapshotSyncAndMultiChannelAutoLoginWorkEndToEnd() throws Exception {
        String shopId = "shop_v60_" + UUID.randomUUID().toString().substring(0, 6);
        int year = LocalDate.now(SHANGHAI).getYear();
        Instant paidAt = LocalDate.of(year, 5, 20).atTime(23, 18).atZone(SHANGHAI).toInstant();

        YouzanAnnualPullClient.PulledOrder order1 = new YouzanAnnualPullClient.PulledOrder(
                "E" + year + "05200001",
                "yz_open_lin_001",
                "TRADE_SUCCESS",
                true,
                paidAt.minusSeconds(120),
                paidAt,
                168000L,
                0L,
                "林清晚",
                "13800138991",
                "林女士",
                "超光子嫩肤全年卡（院线臻选）",
                List.of(new YouzanAnnualPullClient.PulledOrderItem(
                        "1001", "2001", "超光子嫩肤全年卡（院线臻选）", "全模式", 1, 168000L))
        );
        YouzanAnnualPullClient.PulledOrder order2 = new YouzanAnnualPullClient.PulledOrder(
                "E" + year + "06180002",
                "yz_open_chen_002",
                "TRADE_SUCCESS",
                true,
                paidAt.plusSeconds(3600),
                paidAt.plusSeconds(3700),
                298000L,
                0L,
                "陈砚洲",
                "13900139882",
                "陈先生",
                "热玛吉面部紧致体验装",
                List.of(new YouzanAnnualPullClient.PulledOrderItem(
                        "1002", "2002", "热玛吉面部紧致体验装", "面部900发", 1, 298000L)),
                9,
                9002002L
        );

        when(pullClient.pullOrderPage(anyLong(), eq(shopId), any(), any(), eq(1), anyInt()))
                .thenReturn(new YouzanAnnualPullClient.PulledOrderPage(List.of(order1, order2), 2, false));
        when(pullClient.fetchBuyerProfile(anyLong(), eq(shopId), eq("yz_open_lin_001"),
                isNull(), isNull(), eq("13800138991")))
                .thenReturn(new YouzanAnnualPullClient.BuyerProfileEnrichment(
                        "林清晚", "清晚", "FEMALE", "wx_mp_openid_lin", "wx_union_lin", "13800138991", null));
        when(pullClient.fetchBuyerProfile(anyLong(), eq(shopId), eq("yz_open_chen_002"),
                eq(9), eq(9002002L), eq("13900139882")))
                .thenReturn(new YouzanAnnualPullClient.BuyerProfileEnrichment(
                        "陈砚洲", "砚洲", "MALE", null, null, "13900139882", null));

        AuthCookies admin = login("admin");
        long crmOrdersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order", Long.class);
        long crmEntitlementsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement", Long.class);

        // 1. B 端拉取有赞历史订单只读快照
        mockMvc.perform(admin.withAuth(post("/api/v1/annual-reports/youzan-snapshots/sync"))
                        .header("Idempotency-Key", "v60-sync-" + shopId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "shopId", shopId,
                                "startDate", year + "-05-01",
                                "endDate", year + "-06-30",
                                "enrichBuyerProfile", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordersSynced").value(2))
                .andExpect(jsonPath("$.data.paidOrdersSynced").value(2))
                .andExpect(jsonPath("$.data.buyersSynced").value(2))
                .andExpect(jsonPath("$.data.openIdBoundCount").value(1))
                .andExpect(jsonPath("$.data.unionIdBoundCount").value(1));

        // 绝不改写 CRM 正式订单或发放权益
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order", Long.class)).isEqualTo(crmOrdersBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement", Long.class))
                .isEqualTo(crmEntitlementsBefore);

        // 2. B 端汇总与脱敏买家档案查询，机构用户应被拒绝
        mockMvc.perform(admin.withAuth(get("/api/v1/annual-reports/youzan-snapshots/summary"))
                        .param("shopId", shopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileCount").value(2))
                .andExpect(jsonPath("$.data.paidOrderCount").value(2))
                .andExpect(jsonPath("$.data.openIdBoundCount").value(1))
                .andExpect(jsonPath("$.data.wechatMpConfigured").value(true));

        MvcResult profilesRes = mockMvc.perform(admin.withAuth(get("/api/v1/annual-reports/youzan-snapshots/profiles"))
                        .param("shopId", shopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn();
        String profilesBody = profilesRes.getResponse().getContentAsString();
        assertThat(profilesBody).doesNotContain("13800138991").doesNotContain("wx_mp_openid_lin")
                .doesNotContain("yz_open_lin_001");

        AuthCookies instUser = login("inst1");
        mockMvc.perform(instUser.withAuth(get("/api/v1/annual-reports/youzan-snapshots/summary")))
                .andExpect(status().isForbidden());

        // 3. 微信服务号静默授权（snsapi_base）自动命中林清晚 -> 签发 C 端 Cookie -> 秒开本人 H5
        MvcResult authRedirect = mockMvc.perform(get("/api/v1/public/annual-reports/auth/wechat/authorize")
                        .param("year", Integer.toString(year))
                        .param("shopId", shopId))
                .andExpect(status().isFound())
                .andExpect(header().exists("Location"))
                .andReturn();
        String location = authRedirect.getResponse().getHeader("Location");
        assertThat(location).contains("scope=snsapi_base").contains("#wechat_redirect");
        String state = extractQueryParam(location, "state");
        Cookie stateCookie = extractResponseCookie(authRedirect, "WHALE_H5_OAUTH_STATE");
        assertThat(stateCookie).isNotNull();

        when(wechatOAuthClient.exchangeMpCode("wx_code_lin"))
                .thenReturn(new WechatOfficialOAuthClient.OAuthIdentity("wx_mp_openid_lin", "wx_union_lin"));

        MvcResult cbRes = mockMvc.perform(get("/api/v1/public/annual-reports/auth/wechat/callback")
                        .param("code", "wx_code_lin")
                        .param("state", state)
                        .cookie(stateCookie))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(cbRes.getResponse().getHeader("Location")).contains("auth=ok");
        Cookie h5SessionLin = extractResponseCookie(cbRes, "WHALE_H5_SESSION");
        assertThat(h5SessionLin).isNotNull();

        mockMvc.perform(get("/api/v1/public/annual-reports/me")
                        .param("year", Integer.toString(year))
                        .cookie(h5SessionLin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.dataSource").value("YOUZAN_READONLY_SNAPSHOT"))
                .andExpect(jsonPath("$.data.report.displayName").value("林清晚"))
                .andExpect(jsonPath("$.data.report.orderCount").value(1))
                .andExpect(jsonPath("$.data.report.netSpendFen").value(168000));

        // C 端 Cookie 上报浏览停留埋点
        mockMvc.perform(post("/api/v1/public/annual-reports/me/telemetry")
                        .param("year", Integer.toString(year))
                        .cookie(h5SessionLin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "sessionKey", "sess_v60_lin_01",
                                "eventType", "SCENE_CHANGE",
                                "sceneIndex", 3,
                                "deltaDurationMs", 9500,
                                "clientDevice", "WECHAT"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibleDurationMs").value(9500))
                .andExpect(jsonPath("$.data.maxSceneReached").value(3));

        // C 端 Cookie 绝不能访问 B 端 CRM 管理接口
        mockMvc.perform(get("/api/v1/annual-reports").cookie(h5SessionLin))
                .andExpect(status().isUnauthorized());

        // 4. OpenID 未命中老客（陈砚洲）-> 跳 needBind=1 -> 手机号验证码一次验证并永久绑定 -> 下次微信直接静默秒登
        MvcResult auth2 = mockMvc.perform(get("/api/v1/public/annual-reports/auth/wechat/authorize")
                        .param("year", Integer.toString(year))
                        .param("shopId", shopId))
                .andExpect(status().isFound())
                .andReturn();
        String state2 = extractQueryParam(auth2.getResponse().getHeader("Location"), "state");
        Cookie stateCookie2 = extractResponseCookie(auth2, "WHALE_H5_OAUTH_STATE");

        when(wechatOAuthClient.exchangeMpCode("wx_code_chen_first"))
                .thenReturn(new WechatOfficialOAuthClient.OAuthIdentity("wx_mp_openid_chen_new", null));
        MvcResult cbChen1 = mockMvc.perform(get("/api/v1/public/annual-reports/auth/wechat/callback")
                        .param("code", "wx_code_chen_first")
                        .param("state", state2)
                        .cookie(stateCookie2))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(cbChen1.getResponse().getHeader("Location")).contains("needBind=1");
        Cookie pendingCookie = extractResponseCookie(cbChen1, "WHALE_H5_WECHAT_PENDING");
        assertThat(pendingCookie).isNotNull();

        MvcResult smsSend = mockMvc.perform(post("/api/v1/public/annual-reports/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("mobile", "13900139882"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true))
                .andReturn();
        String devCode = readJson(smsSend).path("data").path("devPreviewCode").asText();
        assertThat(devCode).matches("^\\d{6}$");

        MvcResult smsVerify = mockMvc.perform(post("/api/v1/public/annual-reports/auth/sms/verify")
                        .cookie(pendingCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "mobile", "13900139882",
                                "code", devCode,
                                "reportYear", year,
                                "shopId", shopId
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.wechatBound").value(true))
                .andExpect(jsonPath("$.data.displayName").value("陈砚洲"))
                .andReturn();
        Cookie h5SessionChen = extractResponseCookie(smsVerify, "WHALE_H5_SESSION");
        assertThat(h5SessionChen).isNotNull();

        // 下次同一微信号打开，静默授权直接命中绑定，无需再输手机号！
        MvcResult auth3 = mockMvc.perform(get("/api/v1/public/annual-reports/auth/wechat/authorize")
                        .param("year", Integer.toString(year))
                        .param("shopId", shopId))
                .andExpect(status().isFound())
                .andReturn();
        String state3 = extractQueryParam(auth3.getResponse().getHeader("Location"), "state");
        Cookie stateCookie3 = extractResponseCookie(auth3, "WHALE_H5_OAUTH_STATE");
        when(wechatOAuthClient.exchangeMpCode("wx_code_chen_second"))
                .thenReturn(new WechatOfficialOAuthClient.OAuthIdentity("wx_mp_openid_chen_new", null));
        MvcResult cbChen2 = mockMvc.perform(get("/api/v1/public/annual-reports/auth/wechat/callback")
                        .param("code", "wx_code_chen_second")
                        .param("state", state3)
                        .cookie(stateCookie3))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(cbChen2.getResponse().getHeader("Location")).contains("auth=ok");
        Cookie h5SessionChen2 = extractResponseCookie(cbChen2, "WHALE_H5_SESSION");
        assertThat(h5SessionChen2).isNotNull();
        mockMvc.perform(get("/api/v1/public/annual-reports/me")
                        .param("year", Integer.toString(year))
                        .cookie(h5SessionChen2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.report.displayName").value("陈砚洲"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String extractQueryParam(String url, String key) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Cookie extractResponseCookie(MvcResult result, String cookieName) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(cookieName + "=")) {
                String value = header.substring(cookieName.length() + 1).split(";", 2)[0];
                if (!value.isBlank()) {
                    return new Cookie(cookieName, value);
                }
            }
        }
        return null;
    }
}
