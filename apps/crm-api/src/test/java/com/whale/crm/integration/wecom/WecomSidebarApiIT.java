package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarClient;
import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        "app.wecom.official.allowed-tenant-id=1",
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
    @Autowired
    private WecomOfficialProperties officialProperties;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private com.whale.crm.platform.auth.application.CaptchaService captchaService;

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

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM crm_wecom_room_participant WHERE tenant_id=1 AND corp_id='MOCK-CORP-001' "
                + "AND room_id='SIDEBAR-ROOM-1010'");
        jdbcTemplate.update("DELETE FROM crm_wecom_room WHERE tenant_id=1 AND corp_id='MOCK-CORP-001' "
                + "AND room_id='SIDEBAR-ROOM-1010'");
        jdbcTemplate.update("DELETE FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id='MOCK-CORP-001' "
                + "AND external_userid IN ('SIDEBAR-CURRENT-1010','SIDEBAR-UNMATCHED-1010','SIDEBAR-NEW-LINK-1010')");
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
        MvcResult replayed = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                        .param("code", "valid-code").param("state", state))
                .andExpect(status().isFound()).andReturn();
        assertOAuthErrorRedirect(replayed, "UNAUTHORIZED", "valid-code", state);

        MvcResult missingState = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                        .param("code", "valid-code"))
                .andExpect(status().isFound()).andReturn();
        assertOAuthErrorRedirect(missingState, "VALIDATION_FAILED", "valid-code", null);
    }

    @Test
    void oauthIsolatesTenantsAndRejectsUnmappedInstitutionOrInactiveStaff() throws Exception {
        assertThat(officialProperties.allowedTenantId()).isEqualTo(1L);
        when(client.oauthUserid("unknown-code")).thenReturn("unknown-staff");
        String unknownState = authorizeState();
        MvcResult unknownRes = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                        .param("code", "unknown-code").param("state", unknownState))
                .andExpect(status().isFound()).andReturn();
        assertOAuthErrorRedirect(unknownRes, "UNAUTHORIZED", "unknown-code", unknownState);

        when(client.oauthUserid("cross-tenant-code")).thenReturn("staff-2");
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (2,'MOCK-CORP-001','staff-2',2,1)");
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,'MOCK-CORP-001','inst-wecom-8',8,1)");
        String inactiveUser = "wecom_inactive_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                + "VALUES (1,1,?,'停用员工',?,'EMPLOYEE','INACTIVE')",
                inactiveUser, passwordEncoder.encode(DEV_PASSWORD));
        Long inactiveUserId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?",
                Long.class, inactiveUser);
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,'MOCK-CORP-001','inactive-staff',?,1)", inactiveUserId);
        try {
            MvcResult ok = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                            .param("code", "cross-tenant-code").param("state", authorizeState()))
                    .andExpect(status().isFound()).andReturn();
            mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("CRM_TOKEN", cookieValue(ok, "CRM_TOKEN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(2));

            when(client.oauthUserid("inst-code")).thenReturn("inst-wecom-8");
            String instState = authorizeState();
            MvcResult instRes = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                            .param("code", "inst-code").param("state", instState))
                    .andExpect(status().isFound()).andReturn();
            assertOAuthErrorRedirect(instRes, "UNAUTHORIZED", "inst-code", instState);

            when(client.oauthUserid("inactive-code")).thenReturn("inactive-staff");
            String inactiveState = authorizeState();
            MvcResult inactiveRes = mockMvc.perform(get("/api/v1/wecom/oauth/callback")
                            .param("code", "inactive-code").param("state", inactiveState))
                    .andExpect(status().isFound()).andReturn();
            assertOAuthErrorRedirect(inactiveRes, "UNAUTHORIZED", "inactive-code", inactiveState);
        } finally {
            jdbcTemplate.update("DELETE FROM crm_wecom_staff_account WHERE tenant_id=2 AND corp_id='MOCK-CORP-001' "
                    + "AND wecom_userid='staff-2'");
            jdbcTemplate.update("DELETE FROM crm_wecom_staff_account WHERE tenant_id=1 AND corp_id='MOCK-CORP-001' "
                    + "AND wecom_userid IN ('inst-wecom-8','inactive-staff')");
            jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", inactiveUserId);
        }
    }

    @Test
    void groupContextEnforcesDataScopeAndReturnsLinkedCustomers() throws Exception {
        jdbcTemplate.update("INSERT INTO crm_wecom_room (tenant_id,corp_id,room_id,display_name,status,"
                + "source_evidence_ref,membership_confirmed_at,created_by) VALUES "
                + "(1,'MOCK-CORP-001','SIDEBAR-ROOM-1010','VIP 侧边栏群','ACTIVE','mock://room-evidence',NOW(3),1)");
        jdbcTemplate.update("INSERT INTO crm_wecom_room_participant (tenant_id,corp_id,room_id,wecom_userid,"
                + "participant_type,staff_user_id,external_userid,status,confirmed_at) VALUES "
                + "(1,'MOCK-CORP-001','SIDEBAR-ROOM-1010','staff-2','STAFF',2,NULL,'ACTIVE',NOW(3)),"
                + "(1,'MOCK-CORP-001','SIDEBAR-ROOM-1010','SIDEBAR-CURRENT-1010','EXTERNAL',NULL,'SIDEBAR-CURRENT-1010','ACTIVE',NOW(3)),"
                + "(1,'MOCK-CORP-001','SIDEBAR-ROOM-1010','SIDEBAR-UNMATCHED-1010','EXTERNAL',NULL,'SIDEBAR-UNMATCHED-1010','ACTIVE',NOW(3))");

        AuthCookies steward1 = login("manager1");
        mockMvc.perform(get("/api/v1/wecom/sidebar/group-context")
                        .param("chatId", "SIDEBAR-ROOM-1010").cookie(steward1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contextStatus").value("READY"))
                .andExpect(jsonPath("$.data.room.roomId").value("SIDEBAR-ROOM-1010"))
                .andExpect(jsonPath("$.data.room.displayName").value("VIP 侧边栏群"))
                .andExpect(jsonPath("$.data.room.staffCount").value(1))
                .andExpect(jsonPath("$.data.room.externalCount").value(2))
                .andExpect(jsonPath("$.data.room.linkedCustomerCount").value(1))
                .andExpect(jsonPath("$.data.customers[0].customerId").value(1010))
                .andExpect(jsonPath("$.data.customers[0].externalUserid").value("SIDEBAR-CURRENT-1010"));

        mockMvc.perform(get("/api/v1/wecom/sidebar/group-context")
                        .param("chatId", "UNREGISTERED-ROOM").cookie(steward1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contextStatus").value("ROOM_UNREGISTERED"))
                .andExpect(jsonPath("$.data.customers").isEmpty());

        AuthCookies steward2 = login("manager2");
        mockMvc.perform(get("/api/v1/wecom/sidebar/group-context")
                        .param("chatId", "SIDEBAR-ROOM-1010").cookie(steward2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/wecom/sidebar/group-context")
                        .param("chatId", "SIDEBAR-ROOM-1010").cookie(login("inst1").cookies()))
                .andExpect(status().isForbidden());
    }

    @Test
    void stewardCanLinkUnlinkedContactWithScopeAuditAndNoOverwrite() throws Exception {
        AuthCookies steward1 = login("manager1");
        String linkBody = objectMapper.writeValueAsString(Map.of(
                "externalUserid", "SIDEBAR-UNMATCHED-1010",
                "customerId", 1010,
                "reason", "侧边栏核验绑定"));
        mockMvc.perform(steward1.withAuth(post("/api/v1/wecom/sidebar/contacts/link"))
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(1010))
                .andExpect(jsonPath("$.data.alreadyLinked").value(false))
                .andExpect(jsonPath("$.data.linkType").value("MANUAL"));

        // Brand-new unsynced contact creates row with PENDING consent (never GRANTED)
        String newLinkBody = objectMapper.writeValueAsString(Map.of(
                "externalUserid", "SIDEBAR-NEW-LINK-1010",
                "customerId", 1010));
        mockMvc.perform(steward1.withAuth(post("/api/v1/wecom/sidebar/contacts/link"))
                        .contentType(MediaType.APPLICATION_JSON).content(newLinkBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(1010))
                .andExpect(jsonPath("$.data.alreadyLinked").value(false));
        String consentStatus = jdbcTemplate.queryForObject(
                "SELECT consent_status FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id='MOCK-CORP-001' "
                        + "AND external_userid='SIDEBAR-NEW-LINK-1010' AND staff_user_id=2",
                String.class);
        assertThat(consentStatus).isEqualTo("PENDING");

        // Cross-team steward cannot link customer 1010 (DataScope -> 404)
        AuthCookies steward2 = login("manager2");
        mockMvc.perform(steward2.withAuth(post("/api/v1/wecom/sidebar/contacts/link"))
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody))
                .andExpect(status().isNotFound());

        // Material media upload rejects cleanly with PENDING_BUSINESS_RULE when official WeCom/file storage disabled
        mockMvc.perform(steward1.withAuth(post("/api/v1/wecom/sidebar/materials/1/media")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"));
        mockMvc.perform(login("inst1").withAuth(post("/api/v1/wecom/sidebar/materials/1/media")))
                .andExpect(status().isForbidden());
    }

    @Test
    void disallowedTenantCannotAccessJsSdkSignatureOrSidebarContext() throws Exception {
        long t2 = 9502L;
        String orgCode = "IT_SIDEBAR_T2";
        String t2User = "sidebar_t2_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT IGNORE INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (9502,?,?,'侧边栏二号租户','ACTIVE')",
                t2, orgCode);
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                + "VALUES (?,9502,?,'租户2员工',?,'EMPLOYEE','ACTIVE')",
                t2, t2User, passwordEncoder.encode(DEV_PASSWORD));
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE tenant_id=? AND username=?",
                Long.class, t2, t2User);
        jdbcTemplate.update("INSERT INTO sys_role (tenant_id,role_code,role_name,status) VALUES (?,'T2_SIDEBAR','租户2侧边栏','ACTIVE')",
                t2);
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE tenant_id=? AND role_code='T2_SIDEBAR'",
                Long.class, t2);
        jdbcTemplate.update("INSERT IGNORE INTO sys_permission (tenant_id,permission_code,description) VALUES (?,'wecom:sidebar:view','侧边栏')",
                t2);
        Long permId = jdbcTemplate.queryForObject("SELECT id FROM sys_permission WHERE tenant_id=? AND permission_code='wecom:sidebar:view'",
                Long.class, t2);
        jdbcTemplate.update("INSERT INTO sys_role_permission (tenant_id,role_id,permission_id) VALUES (?,?,?)", t2, roleId, permId);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (?,?,?)", t2, userId, roleId);

        try {
            resetLoginAttempts("tenant:" + t2, t2User, "127.0.0.1");
            var captcha = captchaService.issue();
            MvcResult loginRes = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", t2User, "password", DEV_PASSWORD,
                                    "captchaId", captcha.captchaId(), "captchaAnswer", captcha.answer(),
                                    "tenantCode", orgCode))))
                    .andExpect(status().isOk())
                    .andReturn();
            AuthCookies t2Cookies = new AuthCookies(cookieValue(loginRes, "CRM_TOKEN"), cookieValue(loginRes, "CRM_CSRF"));

            mockMvc.perform(t2Cookies.withAuth(post("/api/v1/wecom/jssdk/signature"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("url", "https://crm.example.com/wecom/sidebar"))))
                    .andExpect(status().isUnprocessableEntity());
            mockMvc.perform(get("/api/v1/wecom/sidebar/context").cookie(t2Cookies.cookies()))
                    .andExpect(status().isUnprocessableEntity());
        } finally {
            jdbcTemplate.update("DELETE FROM sys_login_session WHERE tenant_id=? AND user_id=?", t2, userId);
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?", t2, userId);
            jdbcTemplate.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?", t2, roleId);
            jdbcTemplate.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?", t2, roleId);
            jdbcTemplate.update("DELETE FROM sys_permission WHERE tenant_id=? AND id=?", t2, permId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE tenant_id=? AND id=?", t2, userId);
            jdbcTemplate.update("DELETE FROM sys_org WHERE id=9502");
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

    private void assertOAuthErrorRedirect(MvcResult result, String expectedError, String secretCode, String secretState) {
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotBlank();
        if (secretCode != null) {
            assertThat(location).doesNotContain(secretCode);
        }
        if (secretState != null) {
            assertThat(location).doesNotContain(secretState);
        }
        HttpUrl url = HttpUrl.get(location);
        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.host()).isEqualTo("crm.example.com");
        assertThat(url.encodedPath()).isEqualTo("/wecom/sidebar");
        assertThat(url.queryParameter("oauth_error")).isEqualTo(expectedError);
        assertThat(url.queryParameter("oauth_message")).isNotBlank();
    }
}
