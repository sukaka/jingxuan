package com.whale.crm.platform.user;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理端到端：分页/筛选、创建、乐观锁 409、角色分配、停用、越权 403、未认证 401。
 */
class UserAdminApiIT extends AbstractMysqlIT {

    @Test
    void listUsers_paginatesAndFilters() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/users?page=1&size=5").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(8)));

        mockMvc.perform(get("/api/v1/users?keyword=manager").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].roles[0]").value("MANAGER"))
                .andExpect(jsonPath("$.data.items[0].teamName").exists());

        mockMvc.perform(get("/api/v1/users?keyword=manager&status=DISABLED").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void listUsers_filtersByTypeTeamRoleAndStatusTogether() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/users?userType=EMPLOYEE&teamId=1&roleCode=MANAGER&status=ACTIVE")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("manager1"))
                .andExpect(jsonPath("$.data.items[0].teamName").value("管家一组"));

        mockMvc.perform(get("/api/v1/users?userType=EMPLOYEE&teamId=2&roleCode=MANAGER_LEAD")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("lead2"));

        mockMvc.perform(get("/api/v1/users?userType=INSTITUTION").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.data.items[0].userType").value("INSTITUTION"));
    }

    @Test
    void listTeams_returnsActiveTeamOptionsForCurrentTenant() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/users/teams").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data[?(@.id == 1)].teamName").value("管家一组"))
                .andExpect(jsonPath("$.data[?(@.id == 2)].teamName").value("管家二组"));
    }

    @Test
    void listUsers_rejectsInvalidFilterValues() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/users?userType=UNKNOWN").cookie(admin.cookies()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/users?teamId=0").cookie(admin.cookies()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listUsers_rejectsOversizedPage() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/users?size=101").cookie(admin.cookies()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createUser_thenDuplicateIs409() throws Exception {
        AuthCookies admin = login("admin");
        String body = "{\"username\":\"it_user_crud\",\"displayName\":\"增删改查\",\"password\":\"ItPass@12345\","
                + "\"userType\":\"EMPLOYEE\",\"teamId\":1}";
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("it_user_crud"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.roles").isEmpty());

        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void createUser_withInvalidTeamOrRole_is400() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_user_badteam\",\"displayName\":\"坏团队\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\",\"teamId\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("teamId"));

        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_user_badrole\",\"displayName\":\"坏角色\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\",\"roleIds\":[9999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("roleIds"));
    }

    @Test
    void updateUser_optimisticLockConflictIs409() throws Exception {
        AuthCookies admin = login("admin");
        long userId = createUser(admin, "it_user_lock");
        long version = currentVersion(admin, "it_user_lock");

        mockMvc.perform(admin.withAuth(patch("/api/v1/users/" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"第一次修改\",\"teamId\":2,\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("第一次修改"))
                .andExpect(jsonPath("$.data.version").value(version + 1));

        // 旧 version 重放 → 409，不静默覆盖
        mockMvc.perform(admin.withAuth(patch("/api/v1/users/" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"并发修改\",\"teamId\":2,\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 刷新后修改成功
        mockMvc.perform(admin.withAuth(patch("/api/v1/users/" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"并发修改\",\"teamId\":2,\"version\":" + (version + 1) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("并发修改"));
    }

    @Test
    void updateUser_missingVersion_is400_unknownUser_is404() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(patch("/api/v1/users/2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"缺版本\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(admin.withAuth(patch("/api/v1/users/999999"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"不存在\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void assignRoles_replacesRoleSet() throws Exception {
        AuthCookies admin = login("admin");
        long userId = createUser(admin, "it_user_roles");
        long managerRoleId = roleIdByCode(admin, "MANAGER");

        mockMvc.perform(admin.withAuth(put("/api/v1/users/" + userId + "/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + managerRoleId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("MANAGER"));

        mockMvc.perform(admin.withAuth(put("/api/v1/users/" + userId + "/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles").isEmpty());
    }

    @Test
    void disableUser_twiceIs409_selfDisableIs409() throws Exception {
        AuthCookies admin = login("admin");
        long userId = createUser(admin, "it_user_disable");

        mockMvc.perform(admin.withAuth(post("/api/v1/users/" + userId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(admin.withAuth(post("/api/v1/users/" + userId + "/disable")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        long adminId = 1L;
        mockMvc.perform(admin.withAuth(post("/api/v1/users/" + adminId + "/disable")))
                .andExpect(status().isConflict());
    }

    @Test
    void managerWithoutUserRead_gets403_anonymousGets401() throws Exception {
        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/users").cookie(manager.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(manager.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_user_forbidden\",\"displayName\":\"越权\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    private long createUser(AuthCookies admin, String username) throws Exception {
        String body = mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"测试用户\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long currentVersion(AuthCookies admin, String username) throws Exception {
        String body = mockMvc.perform(get("/api/v1/users?keyword=" + username).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var items = objectMapper.readTree(body).at("/data/items");
        for (var item : items) {
            if (username.equals(item.get("username").asText())) {
                return item.get("version").asLong();
            }
        }
        throw new AssertionError("列表中未找到用户 " + username);
    }

    private long roleIdByCode(AuthCookies admin, String roleCode) throws Exception {
        String body = mockMvc.perform(get("/api/v1/roles").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var role : objectMapper.readTree(body).at("/data")) {
            if (roleCode.equals(role.get("roleCode").asText())) {
                return role.get("id").asLong();
            }
        }
        throw new AssertionError("未找到角色 " + roleCode);
    }
}
