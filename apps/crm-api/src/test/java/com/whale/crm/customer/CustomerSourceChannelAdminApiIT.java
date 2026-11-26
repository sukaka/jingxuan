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
 * 客户来源与客户渠道统一管理（V62）：
 * 管家主管（MANAGER_LEAD）与系统管理员（ADMIN）可统一新增/修改/停用/启用来源与渠道；
 * 普通管家（MANAGER）无管理权限（403），仅可通过只读字典下拉选择已启用选项，且不可写入自由文本或已停用值。
 */
class CustomerSourceChannelAdminApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void managerLeadManagesSourcesAndChannels_whileManagerCanOnlySelectActiveDropdownOptions() throws Exception {
        AuthCookies lead = login("lead1");
        AuthCookies manager = login("manager1");
        AuthCookies admin = login("admin");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String srcLabel1 = "私域活动-" + suffix;
        String srcLabel2 = "私域沙龙-" + suffix;
        String chnLabel1 = "小红书矩阵-" + suffix;
        String chnLabel2 = "小红书直播-" + suffix;

        // 1. 管家主管可查看并新增客户来源与客户渠道（留空 optionCode 自动生成）
        mockMvc.perform(get("/api/v1/customer-sources").cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem("EXTERNAL_CONTENT")));
        mockMvc.perform(get("/api/v1/customer-channels").cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem("XHS")));

        String srcCreated = mockMvc.perform(lead.withAuth(post("/api/v1/customer-sources"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"" + srcLabel1 + "\",\"sortNo\":15}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.optionLabel").value(srcLabel1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long srcId = objectMapper.readTree(srcCreated).at("/data/id").asLong();
        String srcCode = objectMapper.readTree(srcCreated).at("/data/optionCode").asText();
        assertThat(srcCode).matches("SRC_[A-Z0-9]{12}");

        String chnCreated = mockMvc.perform(lead.withAuth(post("/api/v1/customer-channels"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"" + chnLabel1 + "\",\"sortNo\":18}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.optionLabel").value(chnLabel1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long chnId = objectMapper.readTree(chnCreated).at("/data/id").asLong();
        String chnCode = objectMapper.readTree(chnCreated).at("/data/optionCode").asText();
        assertThat(chnCode).matches("CHN_[A-Z0-9]{12}");

        // 2. 重名校验（409）
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-sources"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\" " + srcLabel1 + " \",\"sortNo\":16}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-channels"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\" " + chnLabel1 + " \",\"sortNo\":19}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 3. 普通管家仅能下拉读取并选择已启用项，所有管理接口均 403
        mockMvc.perform(get("/api/v1/dictionaries/customerSources/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem(srcCode)));
        mockMvc.perform(get("/api/v1/dictionaries/customerChannels/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem(chnCode)));

        mockMvc.perform(get("/api/v1/customer-sources").cookie(manager.cookies())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customer-channels").cookie(manager.cookies())).andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/customer-sources"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"管家越权来源\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/customer-channels"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"管家越权渠道\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(patch("/api/v1/customer-sources/" + srcId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"改名\",\"sortNo\":1,\"version\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/customer-channels/" + chnId + "/disable")))
                .andExpect(status().isForbidden());

        // 普通管家建档可选中主管新增的来源与渠道，但手填不存在代码将被服务端拒绝（409）
        String customerBody = mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"下拉建档-" + suffix + "\",\"sourceCode\":\"" + srcCode
                                + "\",\"channelCode\":\"" + chnCode + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceCode").value(srcCode))
                .andExpect(jsonPath("$.data.channelCode").value(chnCode))
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(customerBody).at("/data/id").asLong();

        mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"手填无效来源-" + suffix + "\",\"sourceCode\":\"FREE_TEXT_SOURCE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"手填无效渠道-" + suffix + "\",\"channelCode\":\"FREE_TEXT_CHANNEL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 4. 管家主管可修改（含乐观锁）与停用/重新启用；停用后历史客户不受影响，但不可新选
        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-sources/" + srcId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"" + srcLabel2 + "\",\"sortNo\":20,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optionLabel").value(srcLabel2))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-sources/" + srcId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"旧版本冲突\",\"sortNo\":21,\"version\":0}"))
                .andExpect(status().isConflict());

        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-channels/" + chnId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"" + chnLabel2 + "\",\"sortNo\":22,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optionLabel").value(chnLabel2));

        mockMvc.perform(lead.withAuth(post("/api/v1/customer-sources/" + srcId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-channels/" + chnId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/dictionaries/customerSources/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", not(hasItem(srcCode))));
        mockMvc.perform(get("/api/v1/dictionaries/customerChannels/options").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", not(hasItem(chnCode))));

        // 历史客户仅改备注可保留原停用来源/渠道，但新建不可再选停用值
        mockMvc.perform(manager.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"历史保留\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceCode").value(srcCode))
                .andExpect(jsonPath("$.data.channelCode").value(chnCode));
        mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"停用后不可选\",\"sourceCode\":\"" + srcCode + "\"}"))
                .andExpect(status().isConflict());

        // 重新启用后恢复可选
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-sources/" + srcId + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-channels/" + chnId + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 5. 跨字典类型防越权：不能用来源接口改渠道或城市，也不能调通用 /dictionary-items
        mockMvc.perform(lead.withAuth(patch("/api/v1/customer-sources/" + chnId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"跨类越权\",\"sortNo\":1,\"version\":1}"))
                .andExpect(status().isNotFound());
        Long cityItemId = jdbcTemplate.queryForObject(
                "SELECT i.id FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                        + "WHERE i.tenant_id=1 AND t.dict_code='cities' AND i.option_code='BJ'", Long.class);
        assertThat(cityItemId).isNotNull();
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-channels/" + cityItemId + "/disable")))
                .andExpect(status().isNotFound());
        mockMvc.perform(lead.withAuth(post("/api/v1/dictionary-items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dictCode\":\"cities\",\"optionCode\":\"NOPE\",\"optionLabel\":\"禁止\"}"))
                .andExpect(status().isForbidden());

        // 6. 管理员同样可用，且审计日志完整记录四类操作
        mockMvc.perform(get("/api/v1/customer-sources").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].optionCode", hasItem(srcCode)));
        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action_code FROM crm_audit_log WHERE target_type='DICTIONARY_ITEM' AND target_id=?",
                String.class, Long.toString(srcId));
        assertThat(actions).contains("DICTIONARY_ITEM_CREATE", "DICTIONARY_ITEM_UPDATE",
                "DICTIONARY_ITEM_DISABLE", "DICTIONARY_ITEM_ENABLE");
    }

    @Test
    void v62MigrationAndRollback_cleanlyGrantsAndRevertsPermission() throws Exception {
        String schema = "v62_src_chn_perm_verify";
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
                            + "WHERE rp.tenant_id=1 AND p.permission_code='customer:source_channel:manage'",
                    String.class);
            assertThat(grantedRoles).containsExactlyInAnyOrder("ADMIN", "MANAGER_LEAD");

            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V62__customer_source_channel_manage_permission_rollback.sql"));
            }

            Long remainingPerm = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_permission WHERE permission_code='customer:source_channel:manage'",
                    Long.class);
            assertThat(remainingPerm).isZero();
            Long dictTypes = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_dictionary_type WHERE tenant_id=1 "
                            + "AND dict_code IN ('customerSources','customerChannels')",
                    Long.class);
            assertThat(dictTypes).isEqualTo(2L);
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
