package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarClient;
import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.wecom.official.corp-id=MOCK-CORP-001",
        "app.wecom.official.secret=sidebar-test-secret",
        "app.wecom.sidebar.enabled=true",
        "app.wecom.sidebar.agent-id=10001",
        "app.wecom.sidebar.oauth-redirect-uri=https://crm.example.com/oauth/callback",
        "app.wecom.sidebar.sidebar-url=https://crm.example.com/wecom/sidebar",
        "app.wecom.sidebar.trusted-origins=https://crm.example.com"
})
class WecomSidebarApiIT extends AbstractMysqlIT {
    @MockitoBean
    private WecomSidebarClient client;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        reset(client);
        redis.delete(List.of("wecom:jsapi_ticket:MOCK-CORP-001:corp",
                "wecom:jsapi_ticket:MOCK-CORP-001:agent"));
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,follow_status) VALUES (1,'MOCK-CORP-001','SIDEBAR-CURRENT-1010',2,1010,"
                + "'GRANTED','ACTIVE') ON DUPLICATE KEY UPDATE customer_id=1010,staff_user_id=2,"
                + "consent_status='GRANTED',follow_status='ACTIVE'");
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,follow_status) VALUES (1,'MOCK-CORP-001','SIDEBAR-UNMATCHED-1010',2,NULL,"
                + "'GRANTED','ACTIVE') ON DUPLICATE KEY UPDATE customer_id=NULL,staff_user_id=2,"
                + "consent_status='GRANTED',follow_status='ACTIVE'");
    }

    @Test
    void contextReturnsCurrentCustomerSummaryAndSupportsUnlinkedState() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/wecom/sidebar/context")
                        .param("externalUserid", "SIDEBAR-CURRENT-1010").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contextStatus").value("READY"))
                .andExpect(jsonPath("$.data.employee.userId").value(1))
                .andExpect(jsonPath("$.data.customer.id").value(1010))
                .andExpect(jsonPath("$.data.summary.totalOrders").isNumber())
                .andExpect(jsonPath("$.data.summary.remainingPackages").isNumber())
                .andExpect(jsonPath("$.data.summary.remainingCount").isNumber());
        mockMvc.perform(get("/api/v1/wecom/sidebar/context")
                        .param("externalUserid", "SIDEBAR-UNMATCHED-1010").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contextStatus").value("CUSTOMER_UNLINKED"))
                .andExpect(jsonPath("$.data.customer").doesNotExist());
        mockMvc.perform(get("/api/v1/wecom/sidebar/context").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contextStatus").value("WAITING_CONTEXT"));

        AuthCookies otherTeam = login("manager2");
        mockMvc.perform(get("/api/v1/wecom/sidebar/context")
                        .param("externalUserid", "SIDEBAR-CURRENT-1010").cookie(otherTeam.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/wecom/sidebar/context").cookie(login("inst1").cookies()))
                .andExpect(status().isForbidden());
    }

    @Test
    void signaturesRequireCsrfTrustedUrlAndCacheBothTicketKinds() throws Exception {
        when(client.ticket(false)).thenReturn(new WecomSidebarClient.WecomTicket("corp-ticket", 7200));
        when(client.ticket(true)).thenReturn(new WecomSidebarClient.WecomTicket("agent-ticket", 7200));
        AuthCookies admin = login("admin");
        String url = "https://crm.example.com/wecom/sidebar?from=chat";
        String body = objectMapper.writeValueAsString(java.util.Map.of("url", url));

        mockMvc.perform(admin.withAuth(post("/api/v1/wecom/jssdk/signature"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpId").value("MOCK-CORP-001"))
                .andExpect(jsonPath("$.data.agentId").value(10001))
                .andExpect(jsonPath("$.data.config.signature").isNotEmpty())
                .andExpect(jsonPath("$.data.agentConfig.signature").isNotEmpty());
        mockMvc.perform(admin.withAuth(post("/api/v1/wecom/jssdk/signature"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(client, times(1)).ticket(false);
        verify(client, times(1)).ticket(true);

        mockMvc.perform(post("/api/v1/wecom/jssdk/signature").cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(admin.withAuth(post("/api/v1/wecom/jssdk/signature"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("url", "https://evil.example.com/sidebar"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.withAuth(post("/api/v1/wecom/jssdk/signature"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("url", url + "#fragment"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/wecom/jssdk/signature")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/wecom/sidebar/context"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oauthCallbackCreatesExistingCrmSessionAndStateCannotReplay() throws Exception {
        when(client.oauthUserid("valid-code")).thenReturn("staff-2");
        String state = authorizeState();
        MvcResult callback = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                        .param("code", "valid-code").param("state", state))
                .andExpect(status().isFound()).andReturn();

        String token = cookieValue(callback, "CRM_TOKEN");
        assertThat(callback.getResponse().getHeaders("Location"))
                .containsExactly("https://crm.example.com/wecom/sidebar");
        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("CRM_TOKEN", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(2));
        mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                        .param("code", "valid-code").param("state", state))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oauthRejectsUnmappedAndAmbiguousStaffWithoutIssuingSession() throws Exception {
        when(client.oauthUserid("unknown-code")).thenReturn("unknown-staff");
        mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                        .param("code", "unknown-code").param("state", authorizeState()))
                .andExpect(status().isUnauthorized());

        when(client.oauthUserid("ambiguous-code")).thenReturn("staff-2");
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (2,'MOCK-CORP-001','staff-2',2,1)");
        try {
            mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                            .param("code", "ambiguous-code").param("state", authorizeState()))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbcTemplate.update("DELETE FROM crm_wecom_staff_account WHERE tenant_id=2 AND corp_id='MOCK-CORP-001' "
                    + "AND wecom_userid='staff-2'");
        }
    }

    private String authorizeState() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/wecom/oauth/authorize"))
                .andExpect(status().isFound()).andReturn();
        String location = result.getResponse().getHeader("Location");
        HttpUrl url = HttpUrl.get(location);
        assertThat(url.host()).isEqualTo("open.weixin.qq.com");
        assertThat(url.queryParameter("agentid")).isEqualTo("10001");
        assertThat(url.queryParameter("redirect_uri")).isEqualTo("https://crm.example.com/oauth/callback");
        assertThat(location).endsWith("#wechat_redirect");
        return url.queryParameter("state");
    }
}
