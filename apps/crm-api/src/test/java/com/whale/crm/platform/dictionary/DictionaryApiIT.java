package com.whale.crm.platform.dictionary;

import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 字典只读链路：真实 MySQL 8.4（共享 Testcontainers）+ Flyway 迁移 + 开发种子 + 权限与默认拒绝。
 */
class DictionaryApiIT extends AbstractMysqlIT {

    @Autowired
    private DictionaryApplicationService dictionaryService;

    @Test
    void anonymousRequest_isRejected401WithUnifiedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/dictionaries/cities/options")
                        .header("X-Request-Id", "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").value("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                .andExpect(header().string("X-Request-Id", "01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    }

    @Test
    void citiesOptions_areSeededAndSorted() throws Exception {
        // 共享库中其他测试可能追加 ACTIVE 项（sortNo 均大于种子），故断言下限与首项
        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(13)))
                .andExpect(jsonPath("$.data[0].optionCode").value("BJ"))
                .andExpect(jsonPath("$.data[0].optionLabel").value("北京"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void loggedInUser_withoutDictionaryRead_isRejected403() throws Exception {
        // inst1 拥有 dictionary:read（所有角色都有）；改用无任何权限的新用户验证最小授权
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_dict_norole\",\"displayName\":\"无权限用户\","
                                + "\"password\":\"" + DEV_PASSWORD + "\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());
        AuthCookies noRole = login("it_dict_norole");
        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(noRole.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void seededManager_hasDictionaryRead() throws Exception {
        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(13)));
    }

    @Test
    void unknownDictionary_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/dictionaries/not-exist/options").cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void tenantIsolation_otherTenantSeesNothing() {
        assertThat(dictionaryService.listActiveOptions(1L, "cities")).hasSizeGreaterThanOrEqualTo(13);
        assertThat(dictionaryService.listActiveOptions(2L, "cities")).isEmpty();
    }
}
