package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V67 客户省/市/区行政区划与详细地址端到端集成测试：
 * 覆盖 /api/v1/regions/tree 权限与数据、新建/编辑省市区与详细地址、双向 city_code 兼容、
 * 列表按省/市/区筛选与跨管家数据隔离、基础档案 CITY 格式化、清空地区与地址，以及 V67 迁移与受控回滚。
 */
class CustomerRegionApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void regionsTreeRequiresAuthAndReturns34ProvincialDivisions() throws Exception {
        mockMvc.perform(get("/api/v1/regions/tree"))
                .andExpect(status().isUnauthorized());

        AuthCookies manager1 = login("manager1");
        String body = mockMvc.perform(get("/api/v1/regions/tree").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(34))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"330000\"").contains("浙江省").contains("西湖区").contains("香港特别行政区");
    }

    @Test
    void createSearchUpdateClearRegionAndDetailAddressEnforcesValidationAndDataScope() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        String prefix = "ITREG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 1. 新建客户：完整省/市/区 + 详细地址（自动兼容回写 cityCode=HZ）
        String createBody = objectMapper.writeValueAsString(Map.of(
                "displayName", prefix + "-西湖客户",
                "provinceCode", "330000",
                "regionCityCode", "330100",
                "districtCode", "330106",
                "detailAddress", "文三路示例100号A座"));
        String createdJson = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cityCode").value("HZ"))
                .andExpect(jsonPath("$.data.provinceCode").value("330000"))
                .andExpect(jsonPath("$.data.provinceName").value("浙江省"))
                .andExpect(jsonPath("$.data.regionCityCode").value("330100"))
                .andExpect(jsonPath("$.data.regionCityName").value("杭州市"))
                .andExpect(jsonPath("$.data.districtCode").value("330106"))
                .andExpect(jsonPath("$.data.districtName").value("西湖区"))
                .andExpect(jsonPath("$.data.regionText").value("浙江省 / 杭州市 / 西湖区"))
                .andExpect(jsonPath("$.data.detailAddress").value("文三路示例100号A座"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createdJson).path("data");
        long id = created.path("id").asLong();
        long version = created.path("version").asLong();

        // 审计快照记录结构化地区且不落详细地址明文
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT after_json FROM crm_audit_log WHERE action_code='CUSTOMER_CREATE' AND target_id=?",
                Long.toString(id));
        String afterJson = String.valueOf(audit.get("after_json"));
        JsonNode afterNode = objectMapper.readTree(afterJson);
        assertThat(afterNode.path("provinceCode").asText()).isEqualTo("330000");
        assertThat(afterNode.path("regionCityCode").asText()).isEqualTo("330100");
        assertThat(afterNode.path("districtCode").asText()).isEqualTo("330106");
        assertThat(afterNode.path("hasDetailAddress").asBoolean()).isTrue();
        assertThat(afterJson).doesNotContain("文三路示例100号A座");

        // 2. 新建客户：仅选到省且不填详细地址（均选填）；非 49 城市清单地级市 cityCode 保持 NULL
        String lishuiBody = objectMapper.writeValueAsString(Map.of(
                "nickname", prefix + "-丽水客户",
                "provinceCode", "330000",
                "regionCityCode", "331100",
                "districtCode", "331102"));
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lishuiBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cityCode").doesNotExist())
                .andExpect(jsonPath("$.data.regionText").value("浙江省 / 丽水市 / 莲都区"))
                .andExpect(jsonPath("$.data.detailAddress").doesNotExist());

        // 3. 跨省市错配被后端拒绝（400 VALIDATION_FAILED）
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"错配测试\",\"provinceCode\":\"330000\",\"regionCityCode\":\"440300\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 4. 列表按省/市/区筛选（且 manager2 无法看到 manager1 客户）
        mockMvc.perform(get("/api/v1/customers")
                        .param("keyword", prefix)
                        .param("provinceCode", "330000")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/api/v1/customers")
                        .param("keyword", prefix)
                        .param("provinceCode", "330000")
                        .param("regionCityCode", "330100")
                        .param("districtCode", "330106")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].regionText").value("浙江省 / 杭州市 / 西湖区"))
                .andExpect(jsonPath("$.data.items[0].detailAddress").value("文三路示例100号A座"));

        mockMvc.perform(get("/api/v1/customers")
                        .param("keyword", prefix)
                        .param("provinceCode", "330000")
                        .cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 5. 基础档案 CITY 返回省市区格式化文本
        String legacyBody = mockMvc.perform(get("/api/v1/customers/" + id + "/legacy-profile")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(legacyBody).contains("浙江省 / 杭州市 / 西湖区");

        // 6. PATCH 更新为直辖市并更新详细地址
        String patchMunicipality = objectMapper.writeValueAsString(Map.of(
                "provinceCode", "110000",
                "regionCityCode", "110100",
                "districtCode", "110105",
                "detailAddress", "建国路示例88号",
                "version", version));
        String updatedJson = mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchMunicipality))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cityCode").value("BJ"))
                .andExpect(jsonPath("$.data.regionText").value("北京市 / 朝阳区"))
                .andExpect(jsonPath("$.data.detailAddress").value("建国路示例88号"))
                .andReturn().getResponse().getContentAsString();
        long version2 = objectMapper.readTree(updatedJson).at("/data/version").asLong();

        // 7. PATCH 显式清空省市区与详细地址
        String patchClear = objectMapper.writeValueAsString(Map.of(
                "provinceCode", "",
                "regionCityCode", "",
                "districtCode", "",
                "detailAddress", "",
                "version", version2));
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchClear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cityCode").doesNotExist())
                .andExpect(jsonPath("$.data.provinceCode").doesNotExist())
                .andExpect(jsonPath("$.data.regionCityCode").doesNotExist())
                .andExpect(jsonPath("$.data.districtCode").doesNotExist())
                .andExpect(jsonPath("$.data.regionText").doesNotExist())
                .andExpect(jsonPath("$.data.detailAddress").doesNotExist());
    }

    @Test
    void v67MigrationAndRollbackSucceedCleanlyOnIsolatedDatabase() throws Exception {
        String dbName = "it_v67_region_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection root = dataSourceFor("mysql").getConnection(); Statement stmt = root.createStatement()) {
            stmt.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            MysqlDataSource isolated = dataSourceFor(dbName);
            Flyway.configure()
                    .dataSource(isolated)
                    .locations("classpath:db/migration")
                    .target("67")
                    .load()
                    .migrate();

            JdbcTemplate isolatedJdbc = new JdbcTemplate(isolated);
            Integer colCountBefore = isolatedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='crm_customer' "
                            + "AND column_name IN ('province_code','province_name','region_city_code',"
                            + "'region_city_name','district_code','district_name','detail_address')",
                    Integer.class, dbName);
            assertThat(colCountBefore).isEqualTo(7);

            try (Connection conn = isolated.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V67__customer_region_and_address_rollback.sql"));
            }

            Integer colCountAfter = isolatedJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='crm_customer' "
                            + "AND column_name IN ('province_code','province_name','region_city_code',"
                            + "'region_city_name','district_code','district_name','detail_address')",
                    Integer.class, dbName);
            assertThat(colCountAfter).isZero();
        } finally {
            try (Connection root = dataSourceFor("mysql").getConnection(); Statement stmt = root.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
            }
        }
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
