package com.whale.crm.file;

import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.outbox.relay.enabled=false")
class FileServiceDisabledApiIT extends AbstractMysqlIT {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void unconfiguredStorageFailsClosedAndAutomaticRelayStaysDisabled() throws Exception {
        assertThat(environment.getProperty("app.files.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.outbox.relay.enabled", Boolean.class)).isFalse();
        assertThat(applicationContext.getBeanNamesForType(PrivateObjectStorage.class)).isEmpty();

        AuthCookies business = login("business1");
        String originalName = "disabled-" + UUID.randomUUID() + ".pdf";
        byte[] pdf = SyntheticFileFixtures.cleanPdf();
        String sha256 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(pdf));
        mockMvc.perform(business.withAuth(post("/api/v1/files/upload-init"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "originalName", originalName,
                                "mimeType", "application/pdf",
                                "sizeBytes", pdf.length,
                                "sha256", sha256,
                                "classification", "CONTRACT",
                                "institutionId", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_file_object WHERE tenant_id=1 AND original_name=?",
                Long.class, originalName);
        assertThat(rows).isZero();
    }
}
