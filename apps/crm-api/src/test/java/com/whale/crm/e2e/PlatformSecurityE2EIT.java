package com.whale.crm.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F1 & F2 端到端安全闭环：
 * - H-SEC-07：验证码无缓存无明文答案、跨租户同名账号缺省 tenantCode 拒登且不污染租户1审计、tenantCode 精准路由与隔离
 * - H-SEC-08：Authorization Bearer + CSRF 登出吊销 sys_login_session 并在后续 GET /auth/me 返回 401
 * - H-BUG-02 & M-BE-02：3段式权限自定义角色分配后实时生效、子集委托拦截与纯空白密码拦截
 * - H-SEC-05 & H-BUG-03 & H-BUG-04 & M-BE-05：HTTP 文件上传下载、EXPORT 直下拦截、ExportScope.NONE 拒绝与审计留痕
 */
@SpringBootTest(properties = {
        "app.files.enabled=true",
        "app.files.endpoint=http://127.0.0.1:9",
        "app.files.access-key=synthetic-test-only",
        "app.files.secret-key=synthetic-test-only",
        "app.files.max-file-size-bytes=65536",
        "app.exports.worker.enabled=false",
        "app.outbox.relay.enabled=false"
})
class PlatformSecurityE2EIT extends AbstractE2EIT {

    @MockitoBean
    private PrivateObjectStorage storage;

    @MockitoBean
    private ClamAvScanner scanner;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeEach
    void initStorageMocks() throws Exception {
        configureMockStorage(storage, scanner, objectStore);
    }

    @Test
    void multiTenantCaptchaCollisionLoginBearerCsrfLogoutAndCrossTenantIsolation() throws Exception {
        // Tier 1: GET /api/v1/auth/captcha returns no-store and hides answer
        mockMvc.perform(get("/api/v1/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.captchaId").isNotEmpty())
                .andExpect(jsonPath("$.data.imageBase64", containsString("iVBORw0KGgo")))
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andExpect(jsonPath("$.data.captchaAnswer").doesNotExist());

        long secondTenantId = 9102L;
        String secondOrgCode = shortUuid("E2E-ORG2").toUpperCase();
        String collidingUser = shortUuid("e2e_dup");
        String hash = passwordEncoder.encode(DEV_PASSWORD);

        jdbcTemplate.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (?,?,?,?,'ACTIVE') "
                        + "ON DUPLICATE KEY UPDATE org_code=VALUES(org_code), status='ACTIVE'",
                secondTenantId, secondTenantId, secondOrgCode, "E2E二号租户");
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (1,1,?,'租户1同名测试员',?,'EMPLOYEE','ACTIVE')",
                collidingUser, hash);
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (?,?,?,'租户2同名测试员',?,'EMPLOYEE','ACTIVE')",
                secondTenantId, secondTenantId, collidingUser, hash);
        long t2UserId = queryLong("SELECT id FROM sys_user WHERE tenant_id=? AND username=?",
                secondTenantId, collidingUser);
        String t2RoleCode = "MANAGER";
        jdbcTemplate.update("INSERT IGNORE INTO sys_role (tenant_id,role_code,role_name,status) VALUES (?,?,?,'ACTIVE')",
                secondTenantId, t2RoleCode, "二租户客户管家");
        long t2RoleId = queryLong("SELECT id FROM sys_role WHERE tenant_id=? AND role_code=?",
                secondTenantId, t2RoleCode);
        jdbcTemplate.update("INSERT INTO sys_permission (tenant_id,permission_code,description) "
                        + "VALUES (?,'customer:read','客户查看'),(?,'customer:write','客户编辑') "
                        + "ON DUPLICATE KEY UPDATE description=VALUES(description)",
                secondTenantId, secondTenantId);
        jdbcTemplate.update("INSERT INTO sys_role_permission (tenant_id,role_id,permission_id) "
                        + "SELECT ?,?,id FROM sys_permission WHERE tenant_id=? AND permission_code IN ('customer:read','customer:write')",
                secondTenantId, t2RoleId, secondTenantId);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (?,?,?)",
                secondTenantId, t2UserId, t2RoleId);
        jdbcTemplate.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type) VALUES (?,?,'customer','ALL')",
                secondTenantId, t2UserId);

        resetLoginAttempts("tenant:1", collidingUser, "127.0.0.1");
        resetLoginAttempts("tenant:" + secondTenantId, collidingUser, "127.0.0.1");
        resetLoginAttempts("ambiguous", collidingUser, "127.0.0.1");
        try {
            long t1AuditBefore = queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                    + "AND action_code='LOGIN_FAILED' AND JSON_EXTRACT(after_json, '$.username')=?", collidingUser);

            // H-SEC-07 Tier 2: Missing tenantCode on colliding username -> 400 VALIDATION_FAILED field=tenantCode
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginPayloadWithTenant(collidingUser, DEV_PASSWORD, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details[0].field").value("tenantCode"));

            long t1AuditAfter = queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                    + "AND action_code='LOGIN_FAILED' AND JSON_EXTRACT(after_json, '$.username')=?", collidingUser);
            assertThat(t1AuditAfter).isEqualTo(t1AuditBefore);

            // H-SEC-07 Tier 1/3: Login with Tenant 2 tenantCode -> Bearer + CSRF lifecycle
            AuthCookies t2Cookies = loginWithTenantCode(collidingUser, DEV_PASSWORD, secondOrgCode, secondTenantId);
            BearerSession t2Bearer = bearerSession(t2Cookies);

            mockMvc.perform(t2Bearer.withBearerGet(get("/api/v1/auth/me")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value(collidingUser))
                    .andExpect(jsonPath("$.data.displayName").value("租户2同名测试员"));

            // Bearer without CSRF on state-changing POST must be rejected (403)
            mockMvc.perform(post("/api/v1/customers")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + t2Bearer.token())
                            .header("Idempotency-Key", uniqueKey("e2e-t2-nocsrf"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("nickname", "无CSRF拦截"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

            // Bearer + CSRF creates Tenant 2 customer, invisible to Tenant 1 admin (404)
            JsonNode t2Cust = data(t2Bearer.withBearer(post("/api/v1/customers"))
                    .header("Idempotency-Key", uniqueKey("e2e-t2-cust"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("nickname", shortUuid("T2Cust")))));
            long t2CustomerId = t2Cust.at("/id").asLong();
            assertThat(queryLong("SELECT tenant_id FROM crm_customer WHERE id=?", t2CustomerId))
                    .isEqualTo(secondTenantId);

            AuthCookies t1Admin = login("admin");
            mockMvc.perform(get("/api/v1/customers/" + t2CustomerId).cookie(t1Admin.cookies()))
                    .andExpect(status().isNotFound());

            // H-SEC-08: Bearer + CSRF logout revokes sys_login_session -> subsequent GET /auth/me 401
            mockMvc.perform(t2Bearer.withBearer(post("/api/v1/auth/logout")))
                    .andExpect(status().isOk());

            assertThat(queryInt("SELECT COUNT(*) FROM sys_login_session WHERE tenant_id=? AND user_id=? "
                            + "AND revoked_at IS NOT NULL", secondTenantId, t2UserId))
                    .isGreaterThanOrEqualTo(1);
            mockMvc.perform(t2Bearer.withBearerGet(get("/api/v1/auth/me")))
                    .andExpect(status().isUnauthorized());
        } finally {
            resetLoginAttempts("tenant:1", collidingUser, "127.0.0.1");
            resetLoginAttempts("tenant:" + secondTenantId, collidingUser, "127.0.0.1");
            resetLoginAttempts("ambiguous", collidingUser, "127.0.0.1");
            jdbcTemplate.update("DELETE FROM crm_customer WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_login_session WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_data_scope WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_role_permission WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_permission WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_role WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE username=?", collidingUser);
            jdbcTemplate.update("DELETE FROM sys_org WHERE id=?", secondTenantId);
        }
    }

    @Test
    void threeSegmentCustomRoleTakesEffectImmediatelyAndSubsetAndBlankPasswordAreRejected() throws Exception {
        AuthCookies admin = login("admin");

        // H-BUG-02: Create custom role and assign 3-segment permissions
        String customRoleCode = shortCode("E2E_3SEG");
        long customRoleId = data(admin.withAuth(post("/api/v1/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("roleCode", customRoleCode, "roleName", "三段式权限测试角色"))))
                .at("/id").asLong();

        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + customRoleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of(
                                "followup:read",
                                "followup:policy:manage",
                                "channel:read",
                                "channel:approve_finance")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(4));

        // Create actor without policy permission, verify 403, then assign role -> immediate 200 without relogin
        String actorUsername = shortUuid("e2e_perm_user");
        long actorUserId = data(admin.withAuth(post("/api/v1/users"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "username", actorUsername,
                        "displayName", "实时权限验证账号",
                        "password", DEV_PASSWORD,
                        "userType", "EMPLOYEE",
                        "teamId", 1))))
                .at("/id").asLong();

        AuthCookies actor = loginWithTenantCode(actorUsername, DEV_PASSWORD, "WHALE", 1L);
        long currentPolicyVersion = data(get("/api/v1/followup-automation/policy").cookie(admin.cookies()))
                .at("/version").asLong();

        mockMvc.perform(actor.withAuth(put("/api/v1/followup-automation/policy"))
                        .header("Idempotency-Key", uniqueKey("e2e-before-role"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultIntervalDays", 14, "intentOverrides", List.of(),
                                "version", currentPolicyVersion))))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.withAuth(put("/api/v1/users/" + actorUserId + "/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roleIds", List.of(customRoleId), "version", 0))))
                .andExpect(status().isOk());

        // Same session immediately accesses guarded 3-segment endpoints
        mockMvc.perform(actor.withAuth(put("/api/v1/followup-automation/policy"))
                        .header("Idempotency-Key", uniqueKey("e2e-after-role"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultIntervalDays", 15, "intentOverrides", List.of(),
                                "version", currentPolicyVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultIntervalDays").value(15));
        // Restore default 14 days
        long nextPolicyVersion = data(get("/api/v1/followup-automation/policy").cookie(admin.cookies()))
                .at("/version").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/followup-automation/policy"))
                        .header("Idempotency-Key", uniqueKey("e2e-restore-policy"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultIntervalDays", 14, "intentOverrides", List.of(),
                                "version", nextPolicyVersion))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/channel-change-requests").cookie(actor.cookies()))
                .andExpect(status().isOk());

        // M-BE-02: Blank whitespace password rejected (400)
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", shortUuid("e2e_blank_pw"),
                                "displayName", "空白密码用户",
                                "password", "        ",
                                "userType", "EMPLOYEE",
                                "teamId", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        long actorVersion = data(get("/api/v1/users?keyword=" + actorUsername).cookie(admin.cookies()))
                .at("/items/0/version").asLong();
        mockMvc.perform(admin.withAuth(patch("/api/v1/users/" + actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("displayName", "空白密码修改", "password", "        ",
                                "version", actorVersion))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // M-BE-02: Non-admin with user:read/user:write cannot escalate privileges beyond own permissions
        String subAdminRoleCode = shortCode("E2E_SUB");
        long subAdminRoleId = data(admin.withAuth(post("/api/v1/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("roleCode", subAdminRoleCode, "roleName", "受限用户管理员"))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + subAdminRoleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("user:read", "user:write")))))
                .andExpect(status().isOk());

        String subAdminUsername = shortUuid("e2e_subadmin");
        data(admin.withAuth(post("/api/v1/users"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "username", subAdminUsername,
                        "displayName", "受限子管理员",
                        "password", DEV_PASSWORD,
                        "userType", "EMPLOYEE",
                        "teamId", 1,
                        "roleIds", List.of(subAdminRoleId)))));
        AuthCookies subAdmin = loginWithTenantCode(subAdminUsername, DEV_PASSWORD, "WHALE", 1L);

        // Cannot grant out-of-subset permission (channel:approve_finance) to a role
        mockMvc.perform(subAdmin.withAuth(put("/api/v1/roles/" + customRoleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("user:read", "channel:approve_finance")))))
                .andExpect(status().isForbidden());

        // Cannot assign role containing out-of-subset permissions to a user
        mockMvc.perform(subAdmin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", shortUuid("e2e_escalated"),
                                "displayName", "越权提权用户",
                                "password", DEV_PASSWORD,
                                "userType", "EMPLOYEE",
                                "teamId", 1,
                                "roleIds", List.of(customRoleId)))))
                .andExpect(status().isForbidden());

        // Cannot modify an existing user who holds permissions outside subAdmin's subset
        mockMvc.perform(subAdmin.withAuth(patch("/api/v1/users/" + actorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("displayName", "越权修改高权用户", "version", actorVersion))))
                .andExpect(status().isForbidden());
    }

    @Test
    void fileUploadDownloadExportBlockingAndExportScopeNoneDenial() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies operation = login("operation1");
        AuthCookies operator1 = login("operator1");
        AuthCookies lead1 = login("lead1");
        AuthCookies admin = login("admin");

        // H-BUG-03 + H-SEC-05: MERCHANT_RESOURCE PNG via HTTP upload-init -> upload-complete -> attach -> download 200
        byte[] png = cleanPngBytes();
        long merchantFileId = uploadCleanFileViaHttp(business, objectStore, shortUuid("merchant") + ".png",
                "image/png", png, "MERCHANT_RESOURCE", 1L);
        JsonNode merchantResource = data(business.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                .header("Idempotency-Key", uniqueKey("e2e-mr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("resourceType", "DOCTOR", "title", "E2E医生风采",
                        "description", "E2E HTTP 上传校验", "fileId", merchantFileId))));
        long merchantResourceId = merchantResource.at("/id").asLong();
        try {
            mockMvc.perform(get("/api/v1/files/{id}/download", merchantFileId).cookie(operator1.cookies()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.fileId").value(merchantFileId))
                    .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty());
        } finally {
            jdbcTemplate.update("DELETE FROM crm_institution_merchant_resource WHERE tenant_id=1 AND id=?",
                    merchantResourceId);
        }

        // H-BUG-04: MATERIAL text/plain with commas & quotes -> CLEAN -> attach Material -> revision download 200
        byte[] textPayload = "- 第一条，含中文逗号\n* 第二条, \"quoted\", \"unclosed\r\n#1 plain"
                .getBytes(StandardCharsets.UTF_8);
        long materialFileId = uploadCleanFileViaHttp(operation, objectStore, shortUuid("material") + ".txt",
                "text/plain", textPayload, "MATERIAL", null);
        JsonNode createdMaterial = data(operation.withAuth(post("/api/v1/materials"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "materialNo", shortUuid("E2E-MAT"),
                        "title", "E2E纯文本素材",
                        "categoryCode", "GUIDE",
                        "summary", "含逗号与引号的纯文本素材",
                        "tags", List.of("e2e"),
                        "fileId", materialFileId))));
        long materialId = createdMaterial.at("/id").asLong();
        long revisionId = createdMaterial.at("/revisions/0/id").asLong();
        mockMvc.perform(get("/api/v1/materials/{id}/revisions/{revId}/download", materialId, revisionId)
                        .cookie(operation.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty());
        // Direct generic download of MATERIAL is blocked (403)
        mockMvc.perform(get("/api/v1/files/{id}/download", materialFileId).cookie(operation.cookies()))
                .andExpect(status().isForbidden());

        // EXPORT direct download via /api/v1/files/{id}/download must be 403
        String exportObjectKey = "sealed/1/e2e-export-" + UUID.randomUUID() + ".csv";
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,original_name,mime_type,size_bytes,"
                        + "sha256,classification,institution_id,scan_status,uploaded_by) "
                        + "VALUES (1,?,'export.csv','text/csv',16,?,'EXPORT',NULL,'CLEAN',1)",
                exportObjectKey, "a".repeat(64));
        long exportFileId = queryLong("SELECT id FROM crm_file_object WHERE tenant_id=1 AND object_key=?",
                exportObjectKey);
        mockMvc.perform(get("/api/v1/files/{id}/download", exportFileId).cookie(admin.cookies()))
                .andExpect(status().isForbidden());

        // M-BE-05: ExportScope.NONE -> business1 (NONE scope) & lead1 (TEAM 1 requesting disjoint teamIds=[2]) -> 403 + DENIED log
        String businessDeniedReason = uniqueKey("e2e-export-biz-none");
        mockMvc.perform(business.withAuth(post("/api/v1/exports"))
                        .header("Idempotency-Key", uniqueKey("e2e-exp-biz"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("exportType", "CUSTOMER", "columns", List.of("customerNo"),
                                "reason", businessDeniedReason))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(queryString("SELECT result FROM crm_export_access_log WHERE tenant_id=1 AND reason=? "
                + "ORDER BY id DESC LIMIT 1", businessDeniedReason)).isEqualTo("DENIED");

        String disjointReason = uniqueKey("e2e-export-disjoint-none");
        mockMvc.perform(lead1.withAuth(post("/api/v1/exports"))
                        .header("Idempotency-Key", uniqueKey("e2e-exp-lead"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("exportType", "CUSTOMER", "columns", List.of("customerNo"),
                                "reason", disjointReason, "scope", Map.of("teamIds", List.of(2))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(queryString("SELECT result FROM crm_export_access_log WHERE tenant_id=1 AND reason=? "
                + "ORDER BY id DESC LIMIT 1", disjointReason)).isEqualTo("DENIED");
        verify(storage, never()).presignPut("EXPORT", 0);
    }
}
