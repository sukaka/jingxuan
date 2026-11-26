package com.whale.crm.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionProfileTest {
    @Test
    void productionRequiresInjectedSecretsAndExcludesDevelopmentAccounts() throws IOException {
        String baseConfig = resource("application.yml");
        assertThat(baseConfig).doesNotContain("SPRING_PROFILES_ACTIVE:local", "dev-only-",
                "dev_crm_CHANGE_ME", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

        String profile = resource("application-prod.yml");
        assertThat(profile).contains("${JWT_SECRET}", "${APP_AES_KEY_BASE64}", "${APP_BLIND_INDEX_PEPPER}");
        assertThat(profile).contains("cookie-secure: ${COOKIE_SECURE:true}");
        assertThat(profile).contains("forward-headers-strategy: native");
        assertThat(profile).contains("classpath:db/migration,classpath:db/prod");
        assertThat(profile).contains("baseline-on-migrate: false");
        assertThat(profile).doesNotContain("db/dev", "Dev@12345");

        String seed = resource("db/prod/R__seed_rbac_prod.sql");
        assertThat(seed).contains("'ADMIN'", "'MANAGER'", "'FINANCE'",
                "'wecom:mapping:manage'", "'followup:policy:manage'", "'channel:approve_finance'");
        assertThat(seed).doesNotContain("Dev@12345", "INSERT INTO sys_user", "password_hash");
    }

    @Test
    void productionIncludesRequiredReferenceDataWithoutDevelopmentFixtures() throws IOException {
        String seed = resource("db/prod/R__seed_reference_prod.sql");
        assertThat(seed).contains("'cities'", "'BJ'", "'SH'", "'GZ'", "'SZ'", "'HZ'", "'CD'", "'QD'",
                "'orderTypes'", "'REGULAR'", "'FLASH'", "'PROMOTION'",
                "'institutionBusinessTypes'", "'MED_AESTHETIC'", "'institutionOwnerships'", "'PRIVATE'",
                "'merchantGrades'", "'settlementTypes'", "'MONTHLY'", "'PER_REDEMPTION'",
                "'customerStatuses'", "'customerSources'", "'customerChannels'",
                "'communicationChannels'", "'taskTypes'", "'refundReasons'",
                "'materialTypes'", "'customerTiers'", "'customerTags'");
        assertThat(seed).doesNotContain("dict_code='customerStatuses'");
        assertThat(seed).doesNotContain("Dev@12345", "INSERT INTO sys_user", "password_hash");
    }

    @Test
    void startupSecretValidator_rejectsUnresolvedPlaceholdersAndBlankProdSecrets() {
        MockEnvironment validProd = prodEnv();
        assertThatCode(() -> StartupSecretValidator.validate(validProd)).doesNotThrowAnyException();

        for (String key : StartupSecretValidator.REQUIRED_PROD_SECRET_KEYS) {
            MockEnvironment unresolved = prodEnv();
            unresolved.setProperty(key, "${UNRESOLVED_SECRET}");
            assertThatThrownBy(() -> StartupSecretValidator.validate(unresolved))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key);

            MockEnvironment blank = prodEnv();
            blank.setProperty(key, "   ");
            assertThatThrownBy(() -> StartupSecretValidator.validate(blank))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key);
        }

        MockEnvironment localWithoutRedisPassword = new MockEnvironment();
        localWithoutRedisPassword.setActiveProfiles("local");
        localWithoutRedisPassword.setProperty("spring.datasource.password", "dev_crm_CHANGE_ME");
        localWithoutRedisPassword.setProperty("app.security.auth.jwt-secret", "dev-jwt-secret-0123456789abcdef");
        localWithoutRedisPassword.setProperty("app.security.crypto.aes-key-base64",
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        localWithoutRedisPassword.setProperty("app.security.crypto.blind-index-pepper",
                "dev-only-blind-index-pepper-change-me");
        localWithoutRedisPassword.setProperty("app.ai.worker.internal-token", "dev-ai-internal-token-change-me");
        assertThatCode(() -> StartupSecretValidator.validate(localWithoutRedisPassword)).doesNotThrowAnyException();

        MockEnvironment localWithUnresolvedPlaceholder = new MockEnvironment();
        localWithUnresolvedPlaceholder.setActiveProfiles("local");
        localWithUnresolvedPlaceholder.setProperty("app.security.crypto.blind-index-pepper",
                "${APP_BLIND_INDEX_PEPPER}");
        assertThatThrownBy(() -> StartupSecretValidator.validate(localWithUnresolvedPlaceholder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.crypto.blind-index-pepper");
    }

    private MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.password", "prod-mysql-pass");
        env.setProperty("spring.data.redis.password", "prod-redis-pass");
        env.setProperty("app.security.auth.jwt-secret", "prod-jwt-secret-0123456789abcdef0123456789");
        env.setProperty("app.security.crypto.aes-key-base64", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        env.setProperty("app.security.crypto.blind-index-pepper", "prod-blind-index-pepper");
        env.setProperty("app.ai.worker.internal-token", "prod-ai-internal-token");
        return env;
    }

    private String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
