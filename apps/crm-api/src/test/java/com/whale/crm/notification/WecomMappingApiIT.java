package com.whale.crm.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WecomMappingApiIT extends AbstractMysqlIT {
    @Autowired NotificationRepository repository;

    @Test
    void adminMaintainsNotificationMappingWithIdempotencyAuditAndIndependentEnablement() throws Exception {
        AuthCookies admin = login("admin");
        String corpId = "ww-mapping-" + UUID.randomUUID();
        String wecomUserid = "synthetic-lead-" + UUID.randomUUID();
        String createKey = "mapping-create-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", wecomUserid,
                "staffUserId", 4, "messageEnabled", true, "reason", "合成通知映射验收"));

        mockMvc.perform(post("/api/v1/notification-settings/wecom-mappings").cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        var createRequest = admin.withAuth(post("/api/v1/notification-settings/wecom-mappings")
                .header("Idempotency-Key", createKey).contentType(MediaType.APPLICATION_JSON).content(body));
        JsonNode created = objectMapper.readTree(mockMvc.perform(createRequest)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString());
        long id = created.at("/data/id").asLong();
        JsonNode replay = objectMapper.readTree(mockMvc.perform(createRequest)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.at("/data/id").asLong()).isEqualTo(id);
        assertThat(repository.findWecomUserIds(1, 4, corpId)).containsExactly(wecomUserid);

        String updateBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", wecomUserid,
                "staffUserId", 4, "messageEnabled", false, "version", 0, "reason", "验证独立停用通知"));
        mockMvc.perform(admin.withAuth(put("/api/v1/notification-settings/wecom-mappings/" + id))
                        .header("Idempotency-Key", "mapping-update-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messageEnabled").value(false))
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(repository.findWecomUserIds(1, 4, corpId)).isEmpty();

        mockMvc.perform(admin.withAuth(get("/api/v1/notification-settings/wecom-mappings")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == " + id + ")].corpId")
                        .value(corpId));
        String duplicateBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid",
                "another-" + UUID.randomUUID(), "staffUserId", 4, "messageEnabled", true,
                "reason", "验证员工映射唯一"));
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-settings/wecom-mappings"))
                        .header("Idempotency-Key", "mapping-duplicate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicateBody))
                .andExpect(status().isConflict());

        AuthCookies manager = login("manager1");
        mockMvc.perform(manager.withAuth(post("/api/v1/notification-settings/wecom-mappings"))
                        .header("Idempotency-Key", "mapping-denied-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
