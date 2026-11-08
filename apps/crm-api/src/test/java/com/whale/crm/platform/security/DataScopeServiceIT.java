package com.whale.crm.platform.security;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据范围模型（基于开发种子验证）：ALL/TEAM/SELF/INSTITUTION/NONE。
 * 领域消费方（客户/机构列表）在后续阶段接入，这里固定语义，供其直接调用。
 */
class DataScopeServiceIT extends AbstractMysqlIT {

    @Autowired
    private DataScopeService dataScopeService;

    @Autowired
    private CurrentUserService currentUserService;

    private CurrentUser load(String username) {
        try {
            String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/auth/login")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(loginPayload(username, DEV_PASSWORD)))
                    .andReturn().getResponse().getContentAsString();
            long userId = objectMapper.readTree(body).at("/data/userId").asLong();
            return currentUserService.loadById(userId).orElseThrow();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void adminAndOwner_haveAllScope() {
        assertThat(dataScopeService.scopeFor(load("admin"), DataScopeService.RESOURCE_CUSTOMER).type())
                .isEqualTo(DataScope.ScopeType.ALL);
        assertThat(dataScopeService.scopeFor(load("owner1"), DataScopeService.RESOURCE_INSTITUTION).type())
                .isEqualTo(DataScope.ScopeType.ALL);
    }

    @Test
    void lead1_hasTeamScopeWithOwnTeam() {
        DataScope scope = dataScopeService.scopeFor(load("lead1"), DataScopeService.RESOURCE_CUSTOMER);
        assertThat(scope.type()).isEqualTo(DataScope.ScopeType.TEAM);
        assertThat(scope.teamIds()).containsExactly(1L);
    }

    @Test
    void managers_haveSelfScope() {
        assertThat(dataScopeService.scopeFor(load("manager1"), DataScopeService.RESOURCE_CUSTOMER).type())
                .isEqualTo(DataScope.ScopeType.SELF);
        assertThat(dataScopeService.scopeFor(load("manager2"), DataScopeService.RESOURCE_CUSTOMER).type())
                .isEqualTo(DataScope.ScopeType.SELF);
    }

    @Test
    void institutionUser_seesOnlyMappedInstitutions() {
        DataScope scope = dataScopeService.scopeFor(load("inst1"), DataScopeService.RESOURCE_INSTITUTION);
        assertThat(scope.type()).isEqualTo(DataScope.ScopeType.INSTITUTION);
        assertThat(scope.institutionIds()).containsExactly(1L);
        // 机构用户对 customer 资源同样收敛为本机构语义（由 crm_institution_user 决定）
        assertThat(dataScopeService.scopeFor(load("inst1"), DataScopeService.RESOURCE_CUSTOMER).type())
                .isEqualTo(DataScope.ScopeType.INSTITUTION);
    }

    @Test
    void userWithoutGrant_hasNoneScope() {
        // business1 无 sys_data_scope 授权 → NONE（调用方必须按不可见处理）
        assertThat(dataScopeService.scopeFor(load("business1"), DataScopeService.RESOURCE_CUSTOMER).type())
                .isEqualTo(DataScope.ScopeType.NONE);
    }
}
