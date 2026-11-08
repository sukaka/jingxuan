package com.whale.crm.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfileTest {
    @Test
    void productionRequiresInjectedSecretsAndExcludesDevelopmentAccounts() throws IOException {
        String profile = resource("application-prod.yml");
        assertThat(profile).contains("${JWT_SECRET}", "${APP_AES_KEY_BASE64}", "${APP_BLIND_INDEX_PEPPER}");
        assertThat(profile).contains("cookie-secure: ${COOKIE_SECURE:true}");
        assertThat(profile).contains("forward-headers-strategy: native");
        assertThat(profile).contains("classpath:db/migration,classpath:db/prod");
        assertThat(profile).doesNotContain("db/dev", "Dev@12345");

        String seed = resource("db/prod/R__seed_rbac_prod.sql");
        assertThat(seed).contains("'ADMIN'", "'MANAGER'", "'FINANCE'");
        assertThat(seed).doesNotContain("Dev@12345", "INSERT INTO sys_user", "password_hash");
    }

    @Test
    void productionIncludesRequiredReferenceDataWithoutDevelopmentFixtures() throws IOException {
        String seed = resource("db/prod/R__seed_reference_prod.sql");
        assertThat(seed).contains("'orderTypes'", "'REGULAR'", "'FLASH'", "'PROMOTION'",
                "'institutionBusinessTypes'", "'MED_AESTHETIC'", "'institutionOwnerships'", "'PRIVATE'",
                "'merchantGrades'", "'settlementTypes'", "'MONTHLY'", "'PER_REDEMPTION'");
        assertThat(seed).doesNotContain("Dev@12345", "INSERT INTO sys_user", "password_hash");
    }

    private String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
