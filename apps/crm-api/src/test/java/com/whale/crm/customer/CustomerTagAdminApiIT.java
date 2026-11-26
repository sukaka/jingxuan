package com.whale.crm.customer;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户标签库管理权限与生命周期（V61）：
 * 管家主管（MANAGER_LEAD）与管理员可新增/修改/停用/启用客户标签，普通管家（MANAGER）403 仅可选；
 * 管家主管不能借客户标签接口越权修改其他字典类型，也不能访问通用 dictionary-items 写接口。
 */
class CustomerTagAdminApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void managerLeadCanManageCustomerTags_andManagerIsForbidden() throws Exception {
        AuthCookies lead = login("lead1");
        AuthCookies manager = login("manager1");
        AuthCookies admin = login("admin");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String label1 = "敏感肌-" + suffix;
        String label2 = "极敏感肌-" + suffix;

        // 1. 管家主管可查看并新增客户标签（不传 optionCode 时由服务端自动生成）
        mockMvc.perform(get("/api/v1/customer-tags").cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem("VIP")));

        String createdBody = mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"" + label1 + "\",\"sortNo\":25}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.optionLabel").value(label1))
                .andExpect(jsonPath("$.data.sortNo").value(25))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long tagId = objectMapper.readTree(createdBody).at("/data/id").asLong();
        String tagCode = objectMapper.readTree(createdBody).at("/data/optionCode").asText();
        assertThat(tagCode).matches("TAG_[A-Z0-9]{12}");

        // 2. 重名标签内容拒绝（409）
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\" " + label1 + " \",\"sortNo\":26}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 3. 普通管家通过只读字典选项接口立即可见该标签，但所有标签管理写/读管理接口均 403
        mockMvc.perform(get("/api/v1/dictionaries/customerTags/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem(tagCode)))
                .andExpect(jsonPath("$.data[*].optionLabel", hasItem(label1)));

        mockMvc.perform(get("/api/v1/customer-tags").cookie(manager.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(manager.withAuth(post("/api/v1/customer-tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"管家越权标签\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(patch("/api/v1/customer-tags/" + tagId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"管家越权改名\",\"sortNo\":25,\"version\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/customer-tags/" + tagId + "/disable")))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/customer-tags/" + tagId + "/enable")))
                .andExpect(status().isForbidden());

        // 4. 管家主管可修改标签内容（带乐观锁 version）
        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-tags/" + tagId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"" + label2 + "\",\"sortNo\":28,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optionLabel").value(label2))
                .andExpect(jsonPath("$.data.sortNo").value(28))
                .andExpect(jsonPath("$.data.version").value(1));

        // 旧 version → 409
        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-tags/" + tagId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"并发冲突\",\"sortNo\":29,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 5. 管家主管可停用并重新启用标签
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + tagId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/dictionaries/customerTags/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", not(hasItem(tagCode))));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + tagId + "/disable")))
                .andExpect(status().isConflict());

        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + tagId + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/dictionaries/customerTags/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem(tagCode)));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + tagId + "/enable")))
                .andExpect(status().isConflict());

        // 6. 管家主管不能修改非 customerTags 字典项（404），也不能调通用 dictionary-items（403）
        Long cityItemId = jdbcTemplate.queryForObject(
                "SELECT i.id FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                        + "WHERE i.tenant_id=1 AND t.dict_code='cities' AND i.option_code='BJ'", Long.class);
        assertThat(cityItemId).isNotNull();
        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-tags/" + cityItemId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"越权改城市\",\"sortNo\":1,\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + cityItemId + "/disable")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + cityItemId + "/enable")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(lead.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"LEAD_NO\",\"optionLabel\":\"禁止\"}"))
                .andExpect(status().isForbidden());

        // 7. 管理员具备全量权限，同样可使用 /api/v1/customer-tags
        mockMvc.perform(get("/api/v1/customer-tags").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem(tagCode)));

        // 8. 审计日志覆盖新增/修改/停用/启用四类动作
        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action_code FROM crm_audit_log WHERE target_type='DICTIONARY_ITEM' AND target_id=?",
                String.class, Long.toString(tagId));
        assertThat(actions).contains("DICTIONARY_ITEM_CREATE", "DICTIONARY_ITEM_UPDATE",
                "DICTIONARY_ITEM_DISABLE", "DICTIONARY_ITEM_ENABLE");
    }

    @Test
    void v61MigrationAndRollback_cleanlyGrantsAndRevertsPermission() throws Exception {
        String schema = "v61_tag_perm_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration", "classpath:db/prod").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            List<String> grantedRoles = jdbc.queryForList(
                    "SELECT r.role_code FROM sys_role_permission rp "
                            + "JOIN sys_role r ON r.tenant_id=rp.tenant_id AND r.id=rp.role_id "
                            + "JOIN sys_permission p ON p.tenant_id=rp.tenant_id AND p.id=rp.permission_id "
                            + "WHERE rp.tenant_id=1 AND p.permission_code='customer:tag:manage'",
                    String.class);
            assertThat(grantedRoles).containsExactlyInAnyOrder("ADMIN", "MANAGER_LEAD");

            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V61__customer_tag_manage_permission_rollback.sql"));
            }

            Long remainingPerm = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_permission WHERE permission_code='customer:tag:manage'", Long.class);
            assertThat(remainingPerm).isZero();
            Long customerTagsType = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='customerTags'",
                    Long.class);
            assertThat(customerTagsType).isEqualTo(1L);
        } finally {
            try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
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
