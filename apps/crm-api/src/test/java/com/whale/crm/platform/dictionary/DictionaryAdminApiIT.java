package com.whale.crm.platform.dictionary;

import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 字典管理写链路：CRUD、乐观锁、停用后 options 不可见但管理端历史可查、权限边界。
 */
class DictionaryAdminApiIT extends AbstractMysqlIT {

    @Autowired
    private DictionaryApplicationService dictionaryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void listTypes_containsSeededCitiesWithAllStatuses() throws Exception {
        AuthCookies admin = login("admin");
        String body = mockMvc.perform(get("/api/v1/dictionary-types").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode cities = null;
        for (var type : objectMapper.readTree(body).at("/data")) {
            if ("cities".equals(type.get("dictCode").asText())) {
                cities = type;
            }
        }
        org.assertj.core.api.Assertions.assertThat(cities).isNotNull();
        org.assertj.core.api.Assertions.assertThat(cities.get("items").size()).isGreaterThanOrEqualTo(13);
    }

    @Test
    void createUpdateDisable_fullLifecycle() throws Exception {
        AuthCookies admin = login("admin");

        // 创建 → options 立即可见
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"ITC\",\"optionLabel\":\"测试城\","
                                + "\"sortNo\":90}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long itemId = objectMapper.readTree(created).at("/data/id").asLong();

        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem("ITC")));

        // 乐观锁更新
        mockMvc.perform(admin.withAuth(patch("/api/v1/dictionary-items/" + itemId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"测试城市\",\"sortNo\":91,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optionLabel").value("测试城市"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 旧 version → 409
        mockMvc.perform(admin.withAuth(patch("/api/v1/dictionary-items/" + itemId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"并发改\",\"sortNo\":92,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 停用 → options 不再返回；管理端仍可见（历史可查）
        mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items/" + itemId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", not(hasItem("ITC"))));

        String typesBody = mockMvc.perform(get("/api/v1/dictionary-types").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        boolean historyVisible = false;
        for (var type : objectMapper.readTree(typesBody).at("/data")) {
            if ("cities".equals(type.get("dictCode").asText())) {
                for (var item : type.get("items")) {
                    if ("ITC".equals(item.get("optionCode").asText())
                            && "INACTIVE".equals(item.get("status").asText())) {
                        historyVisible = true;
                    }
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(historyVisible).as("停用项管理端历史可查").isTrue();

        // 重复停用 → 409
        mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items/" + itemId + "/disable")))
                .andExpect(status().isConflict());
    }

    @Test
    void createItem_duplicateCodeIs409_unknownTypeIs404() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"BJ\",\"optionLabel\":\"重复北京\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"no_such_dict\",\"optionCode\":\"X1\",\"optionLabel\":\"无类型\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listTypeItems_allStatusesReadOnlyForManager() throws Exception {
        AuthCookies admin = login("admin");
        String typesBody = mockMvc.perform(get("/api/v1/dictionary-types").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long citiesId = -1;
        for (var type : objectMapper.readTree(typesBody).at("/data")) {
            if ("cities".equals(type.get("dictCode").asText())) {
                citiesId = type.get("id").asLong();
            }
        }
        org.assertj.core.api.Assertions.assertThat(citiesId).isPositive();

        mockMvc.perform(get("/api/v1/dictionary-types/" + citiesId + "/items").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem("BJ")))
                .andExpect(jsonPath("$.data[*].status", hasItem("ACTIVE")));

        mockMvc.perform(get("/api/v1/dictionary-types/999999/items").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/dictionary-types/" + citiesId + "/items").cookie(manager.cookies()))
                .andExpect(status().isOk());
    }

    @Test
    void d01_usedItemCannotBeDeletedOrNewlySelectedAndCreationSnapshotSurvives() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"D01CITY\","
                                + "\"optionLabel\":\"创建时城市\",\"sortNo\":190}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long itemId = objectMapper.readTree(created).at("/data/id").asLong();
        DictionaryOptionResponse creationSnapshot =
                dictionaryService.requireActiveOption(1L, "cities", "D01CITY");

        String customerBody = mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT-D01-历史客户\",\"cityCode\":\"D01CITY\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(customerBody).at("/data/id").asLong();
        String otherCustomerBody = mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT-D01-改选客户\",\"cityCode\":\"BJ\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long otherCustomerId = objectMapper.readTree(otherCustomerBody).at("/data/id").asLong();

        mockMvc.perform(admin.withAuth(delete("/api/v1/dictionary-items/" + itemId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dictionary_item WHERE tenant_id=1 AND id=?",
                Integer.class, itemId)).isEqualTo(1);

        mockMvc.perform(admin.withAuth(patch("/api/v1/dictionary-items/" + itemId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"改名后城市\",\"sortNo\":191,\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items/" + itemId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", not(hasItem("D01CITY"))));
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cityCode").value("D01CITY"));
        mockMvc.perform(manager.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"保留旧值，仅修改备注\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cityCode").value("D01CITY"));

        assertThat(creationSnapshot.optionCode()).isEqualTo("D01CITY");
        assertThat(creationSnapshot.optionLabel()).isEqualTo("创建时城市");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT option_label FROM sys_dictionary_item WHERE tenant_id=1 AND id=?",
                String.class, itemId)).isEqualTo("改名后城市");

        mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT-D01-停用后新建\",\"cityCode\":\"D01CITY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(manager.withAuth(patch("/api/v1/customers/" + otherCustomerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cityCode\":\"D01CITY\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void managerHasReadButNotWrite() throws Exception {
        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/dictionaries/cities/options").cookie(manager.cookies()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/dictionary-types").cookie(manager.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(manager.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"FORB\",\"optionLabel\":\"越权\"}"))
                .andExpect(status().isForbidden());
    }
}
