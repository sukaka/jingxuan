package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户唯一性多管家排查（只读，无需合并）：
 * - 管家主管（MANAGER_LEAD）可查看同一顾客添加了哪几位管家（同团队与跨团队汇总、手机号/外部身份/企微多管家）；
 * - 跨团队管家仅暴露管家姓名、所属团队与总数（inMyScope=false），跨组客户 ID/编号/称呼/敏感信息严格隔离，且无法用关键词探测；
 * - 完全不在本组范围的其他团队重复客户不可见；
 * - 普通管家（MANAGER）调用多管家排查接口返回 403，且客户列表/详情不返回 addedSteward* 字段。
 */
class CustomerUniquenessApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void managerLeadSeesSameTeamAndCrossTeamStewardSummaryWithoutLeakingCrossTeamDetails() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies lead1 = login("lead1");
        AuthCookies manager1 = login("manager1");

        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String mobileSameTeam = "13711" + String.format("%06d", Math.abs(tag.hashCode()) % 1000000);
        String mobileCrossTeam = "13722" + String.format("%06d", Math.abs((tag + "x").hashCode()) % 1000000);
        String mobileTeam2Only = "13733" + String.format("%06d", Math.abs((tag + "y").hashCode()) % 1000000);

        // 1. 同团队重复：manager1(id=2, team=1) 与 lead1(id=4, team=1)
        long sameTeamC1 = createCustomerForOwner(admin, "同组顾客A-" + tag, mobileSameTeam, 2L);
        long sameTeamC2 = createCustomerForOwner(admin, "同组顾客B-" + tag, mobileSameTeam, 4L);

        // 2. 跨团队重复：manager1(id=2, team=1) 与 manager2(id=3, team=2)
        long crossTeamC1 = createCustomerForOwner(admin, "一组客户-" + tag, mobileCrossTeam, 2L);
        String secretCrossName = "二组私密称呼-" + tag;
        long crossTeamC2 = createCustomerForOwner(admin, secretCrossName, mobileCrossTeam, 3L);

        // 3. 纯二组重复（与一组无交集）：manager2(id=3, team=2) 与 lead2(id=9, team=2)
        long team2OnlyC1 = createCustomerForOwner(admin, "纯二组甲-" + tag, mobileTeam2Only, 3L);
        createCustomerForOwner(admin, "纯二组乙-" + tag, mobileTeam2Only, 9L);

        // 4. 企微多管家添加：单 CRM 客户（owner=manager1）+ 3 条活跃企微跟进（王丽/张敏/李强）+ 1 条已删除跟进（应排除）
        long wecomCustomerId = createCustomerForOwner(admin, "企微多管家客-" + tag, null, 2L);
        String wecomExtId = "WECOM-MULTI-" + tag;
        jdbcTemplate.update(
                "INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                        + "follow_status,consent_status) VALUES "
                        + "(1,'MOCK-CORP-001',?,2,?,'ACTIVE','GRANTED'),"
                        + "(1,'MOCK-CORP-001',?,4,?,'ACTIVE','GRANTED'),"
                        + "(1,'MOCK-CORP-001',?,3,NULL,'ACTIVE','GRANTED'),"
                        + "(1,'MOCK-CORP-001',?,9,?,'DELETED_BY_STAFF','GRANTED')",
                wecomExtId, wecomCustomerId,
                wecomExtId, wecomCustomerId,
                wecomExtId,
                wecomExtId, wecomCustomerId);

        // 5. 普通管家（MANAGER）无权限访问多管家排查接口（403），且列表/详情不返回多管家字段
        mockMvc.perform(get("/api/v1/customer-uniqueness/summary").cookie(manager1.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customer-uniqueness/multi-stewards").cookie(manager1.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers/" + crossTeamC1 + "/steward-relations").cookie(manager1.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers/" + crossTeamC1).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedStewardCount").doesNotExist())
                .andExpect(jsonPath("$.data.addedStewards").doesNotExist());

        // 6. 管家主管（lead1，TEAM=1）查看跨组客户：看到 2 位管家汇总，但二组客户明细全部脱敏置空
        String crossRelBody = mockMvc.perform(
                        get("/api/v1/customers/" + crossTeamC1 + "/steward-relations").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stewardCount").value(2))
                .andExpect(jsonPath("$.data.inScopeStewardCount").value(1))
                .andExpect(jsonPath("$.data.crossTeamStewardCount").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(crossRelBody).doesNotContain(secretCrossName);
        assertThat(crossRelBody).doesNotContain(mobileCrossTeam);
        JsonNode crossStewards = objectMapper.readTree(crossRelBody).at("/data/stewards");
        assertThat(crossStewards).hasSize(2);
        for (JsonNode s : crossStewards) {
            if (s.get("staffUserId").asLong() == 2L) {
                assertThat(s.get("inMyScope").asBoolean()).isTrue();
                assertThat(s.get("customerId").asLong()).isEqualTo(crossTeamC1);
                assertThat(s.get("teamName").asText()).isEqualTo("管家一组");
            } else {
                assertThat(s.get("staffUserId").asLong()).isEqualTo(3L);
                assertThat(s.get("inMyScope").asBoolean()).isFalse();
                assertThat(s.get("staffName").asText()).isEqualTo("李强");
                assertThat(s.get("teamName").asText()).isEqualTo("管家二组");
                assertThat(s.get("customerId").isNull()).isTrue();
                assertThat(s.get("customerNo").isNull()).isTrue();
                assertThat(s.get("customerName").isNull()).isTrue();
            }
        }

        // 7. lead1 越权直接查二组客户的多管家详情 -> 404
        mockMvc.perform(get("/api/v1/customers/" + crossTeamC2 + "/steward-relations").cookie(lead1.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/" + team2OnlyC1 + "/steward-relations").cookie(lead1.cookies()))
                .andExpect(status().isNotFound());

        // 8. lead1 多管家排查列表：能看到同组、跨组和企微多管家（3 位，排除已删跟进），看不到纯二组重复；
        //    用二组私密称呼搜索必须返回 0 条（防跨组探测）
        mockMvc.perform(get("/api/v1/customer-uniqueness/multi-stewards")
                        .param("keyword", secretCrossName)
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        String leadListBody = mockMvc.perform(get("/api/v1/customer-uniqueness/multi-stewards")
                        .param("keyword", tag)
                        .param("size", "50")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(leadListBody).doesNotContain(secretCrossName);
        assertThat(leadListBody).doesNotContain("纯二组甲-" + tag);
        JsonNode leadItems = objectMapper.readTree(leadListBody).at("/data/items");
        assertThat(leadItems).hasSize(3);

        boolean foundSameTeam = false;
        boolean foundCrossTeam = false;
        boolean foundWecomMulti = false;
        for (JsonNode item : leadItems) {
            long pid = item.get("primaryCustomerId").asLong();
            if (pid == sameTeamC1 || pid == sameTeamC2) {
                foundSameTeam = true;
                assertThat(item.get("stewardCount").asInt()).isEqualTo(2);
                assertThat(item.get("crossTeam").asBoolean()).isFalse();
            } else if (pid == crossTeamC1) {
                foundCrossTeam = true;
                assertThat(item.get("stewardCount").asInt()).isEqualTo(2);
                assertThat(item.get("crossTeam").asBoolean()).isTrue();
                assertThat(item.get("crossTeamStewardCount").asInt()).isEqualTo(1);
            } else if (pid == wecomCustomerId) {
                foundWecomMulti = true;
                assertThat(item.get("stewardCount").asInt()).isEqualTo(3);
                assertThat(item.get("crossTeam").asBoolean()).isTrue();
                assertThat(item.get("matchReasons").toString()).contains("WECOM_CONTACT");
            }
        }
        assertThat(foundSameTeam).isTrue();
        assertThat(foundCrossTeam).isTrue();
        assertThat(foundWecomMulti).isTrue();

        // 9. 客户列表与详情接口对主管自动携带 addedStewardCount / addedStewards（不触发合并）
        mockMvc.perform(get("/api/v1/customers")
                        .param("keyword", "企微多管家客-" + tag)
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].addedStewardCount").value(3))
                .andExpect(jsonPath("$.data.items[0].addedStewardNames.length()").value(3));

        mockMvc.perform(get("/api/v1/customers/" + wecomCustomerId).cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedStewardCount").value(3))
                .andExpect(jsonPath("$.data.addedStewards.length()").value(3));

        // 10. 汇总接口与管理员 ALL 范围（可看到纯二组重复及 crossTeamOnly 筛选）
        mockMvc.perform(get("/api/v1/customer-uniqueness/summary").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.multiStewardCustomerCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.data.crossTeamCustomerCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.maxStewardCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        String adminList = mockMvc.perform(get("/api/v1/customer-uniqueness/multi-stewards")
                        .param("keyword", tag)
                        .param("size", "50")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(adminList).at("/data/items")).hasSize(4);

        String adminCrossOnly = mockMvc.perform(get("/api/v1/customer-uniqueness/multi-stewards")
                        .param("keyword", tag)
                        .param("crossTeamOnly", "true")
                        .param("size", "50")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(adminCrossOnly).at("/data/items")).hasSize(2);
    }

    private long createCustomerForOwner(AuthCookies actor, String displayName, String mobile, long ownerStaffId)
            throws Exception {
        String mobilePart = mobile == null ? "" : ",\"mobile\":\"" + mobile + "\"";
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\",\"ownerStaffId\":" + ownerStaffId
                                + mobilePart + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }
}
