package com.whale.crm.platform.user;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色/权限目录端到端：目录完整性、角色创建、权限覆盖更新，以及权限变更实时生效（不缓存）。
 */
class RolePermissionApiIT extends AbstractMysqlIT {

    @Test
    void permissionCatalog_isCompleteAndSeeded() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/permissions").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(63))
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'customer:merge_reverse')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'ai:knowledge:config')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'ai:knowledge:publish')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'integration:runtime:manage')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'customer:read')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'redemption:post')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'redemption:reverse')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.permissionCode == 'redemption:approve')]").isNotEmpty());
    }

    @Test
    void roles_areSeededPerMatrix() throws Exception {
        AuthCookies admin = login("admin");
        String body = mockMvc.perform(get("/api/v1/roles").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var roles = objectMapper.readTree(body).at("/data");
        org.assertj.core.api.Assertions.assertThat(roles).hasSize(8);
        for (var role : roles) {
            switch (role.get("roleCode").asText()) {
                case "ADMIN" -> {
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(63);
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions"))
                            .extracting(node -> node.asText())
                            .contains("redemption:approve", "customer:merge_reverse", "integration:runtime:manage");
                }
                case "OWNER" -> {
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(23);
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions"))
                            .extracting(node -> node.asText())
                            .contains("ai:knowledge:read")
                            .doesNotContain("ai:knowledge:publish");
                }
                case "MANAGER_LEAD" -> {
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(31);
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions"))
                            .extracting(node -> node.asText())
                            .contains("redemption:read", "redemption:submit", "redemption:reverse", "ai:write", "ai:confirm")
                            .doesNotContain("redemption:approve");
                }
                case "MANAGER" -> {
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(25);
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions"))
                            .extracting(node -> node.asText())
                            .contains("redemption:read", "redemption:submit", "redemption:reverse", "ai:write")
                            .doesNotContain("redemption:approve");
                }
                case "BUSINESS" -> org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(16);
                case "FINANCE" -> {
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(15);
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions"))
                            .extracting(node -> node.asText())
                            .contains("redemption:approve", "redemption:read")
                            .doesNotContain("redemption:reverse");
                }
                case "INSTITUTION_STAFF" -> {
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(9);
                    org.assertj.core.api.Assertions.assertThat(role.get("permissions"))
                            .extracting(node -> node.asText())
                            .contains("redemption:post", "redemption:reverse", "institution:change_request")
                            .doesNotContain("redemption:approve");
                }
                case "INSTITUTION_FINANCE" ->
                        org.assertj.core.api.Assertions.assertThat(role.get("permissions")).hasSize(4);
                default -> throw new AssertionError("未知角色: " + role.get("roleCode").asText());
            }
        }
    }

    @Test
    void institutionUsersSeeOnlyTheirCurrentInstitutionRoles() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").cookie(login("inst1").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institutionRoles['1']").value("FRONTDESK"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(login("operator1").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institutionRoles['1']").value("OPERATOR"));
        mockMvc.perform(get("/api/v1/auth/me").cookie(login("operator2").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institutionRoles['2']").value("OPERATOR"))
                .andExpect(jsonPath("$.data.institutionRoles['1']").doesNotExist());
    }

    @Test
    void createRole_duplicateCodeIs409() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"IT_DICT_EDITOR\",\"roleName\":\"字典编辑\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roleCode").value("IT_DICT_EDITOR"))
                .andExpect(jsonPath("$.data.permissions").isEmpty());

        mockMvc.perform(admin.withAuth(post("/api/v1/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"IT_DICT_EDITOR\",\"roleName\":\"重复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void permissionChange_takesEffectImmediatelyWithoutRelogin() throws Exception {
        AuthCookies admin = login("admin");

        // 1. 建角色并授予 dictionary:read/write
        String roleBody = mockMvc.perform(admin.withAuth(post("/api/v1/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"IT_TEMP_DICT\",\"roleName\":\"临时字典角色\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long roleId = objectMapper.readTree(roleBody).at("/data/id").asLong();

        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + roleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCodes\":[\"dictionary:read\",\"dictionary:write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(2));

        // 2. 建用户挂该角色并登录
        String userBody = mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_rt_dict\",\"displayName\":\"实时权限\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\",\"roleIds\":["
                                + roleId + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long userId = objectMapper.readTree(userBody).at("/data/id").asLong();
        AuthCookies user = mockLogin("it_rt_dict", "ItPass@12345");

        // 3. 有权限时可写字典
        mockMvc.perform(user.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"ITRT\",\"optionLabel\":\"实时权限城\","
                                + "\"sortNo\":100}"))
                .andExpect(status().isCreated());

        // 4. 管理员摘除角色权限（不吊销会话、用户不重新登录）
        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + roleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCodes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions").isEmpty());

        // 5. 同一令牌立刻失去权限 → 403
        mockMvc.perform(user.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"ITRT2\",\"optionLabel\":\"应被拒\","
                                + "\"sortNo\":101}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 6. 恢复 dictionary:write 后立即恢复可用
        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + roleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCodes\":[\"dictionary:read\",\"dictionary:write\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(user.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"ITRT2\",\"optionLabel\":\"恢复可用\","
                                + "\"sortNo\":101}"))
                .andExpect(status().isCreated());

        // 7. 摘除用户角色 → 立即 403
        mockMvc.perform(admin.withAuth(put("/api/v1/users/" + userId + "/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/dictionary-types").cookie(user.cookies()))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRolePermissions_unknownCodeIs400_unknownRoleIs404() throws Exception {
        AuthCookies admin = login("admin");
        long roleId = 4L; // MANAGER
        mockMvc.perform(admin.withAuth(put("/api/v1/roles/" + roleId + "/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCodes\":[\"customer:read\",\"customer:nonexist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("permissionCodes"));

        mockMvc.perform(admin.withAuth(put("/api/v1/roles/999999/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCodes\":[]}"))
                .andExpect(status().isNotFound());
    }

    private AuthCookies mockLogin(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return new AuthCookies(cookieValue(result, "CRM_TOKEN"), cookieValue(result, "CRM_CSRF"));
    }
}
