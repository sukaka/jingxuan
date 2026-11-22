package com.whale.crm.platform.bootstrap;

import com.whale.crm.CrmApiApplication;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.user.domain.RoleRepository;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BootstrapAdminServiceIT extends AbstractMysqlIT {
    @Autowired
    private BootstrapAdminService bootstrapAdminService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DataSource dataSource;

    @Test
    @Transactional
    void createsUsableAdministratorAndNeverResetsPasswordOnRetry() throws Exception {
        String username = "install_it_admin";
        String password = "Synthetic-install-IT-password-123";
        BootstrapAdminResult created = bootstrapAdminService.initialize(username, "安装测试管理员", password);

        assertThat(created.created()).isTrue();
        SysUser user = userRepository.findByUsernameForAuth(username).orElseThrow();
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(roleRepository.findActiveRoleCodes(1L, user.getId())).containsExactly("ADMIN");
        assertThat(roleRepository.findActivePermissionCodes(1L, user.getId()))
                .contains("customer:read", "user:write", "ai:knowledge:publish");

        BootstrapAdminResult repeated = bootstrapAdminService.initialize(username, "不应覆盖", "Another-install-password-123");
        assertThat(repeated.created()).isFalse();
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));
    }

    @Test
    void bootstrapAdminCommand_bootsRealCrmApiWithoutSchedulersRunnersOrKafkaBlocking() throws Exception {
        AtomicInteger acceptedKafkaConnections = new AtomicInteger();
        AtomicBoolean listenerRunning = new AtomicBoolean(true);
        try (ServerSocket kafkaProbe = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            kafkaProbe.setSoTimeout(50);
            Thread acceptThread = new Thread(() -> {
                while (listenerRunning.get() && !kafkaProbe.isClosed()) {
                    try (Socket ignored = kafkaProbe.accept()) {
                        acceptedKafkaConnections.incrementAndGet();
                    } catch (IOException ignored) {
                        // SoTimeout or listener closed
                    }
                }
            }, "it-kafka-probe-listener");
            acceptThread.setDaemon(true);
            acceptThread.start();

            HikariDataSource hikari = (HikariDataSource) dataSource;
            String username = "cmd_it_admin_" + UUID.randomUUID().toString().substring(0, 8);
            try (ConfigurableApplicationContext cliContext = BootstrapAdminCommand
                    .configureBuilder(new SpringApplicationBuilder(CrmApiApplication.class))
                    .properties(
                            "spring.profiles.active=prod",
                            "DB_JDBC_URL=" + hikari.getJdbcUrl(),
                            "DB_USER=" + hikari.getUsername(),
                            "MYSQL_PASSWORD=" + hikari.getPassword(),
                            "REDIS_PASSWORD=it-probe-redis",
                            "JWT_SECRET=it-probe-jwt-secret-0123456789abcdef0123456789",
                            "APP_AES_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                            "APP_BLIND_INDEX_PEPPER=it-probe-pepper",
                            "AI_INTERNAL_TOKEN=it-probe-ai-token",
                            "NOTIFICATION_KAFKA_ENABLED=true",
                            "NOTIFICATION_WORKER_ENABLED=true",
                            "KNOWLEDGE_SYNC_KAFKA_ENABLED=true",
                            "KNOWLEDGE_SYNC_WORKER_ENABLED=true",
                            "AI_WORKER_ENABLED=true",
                            "KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:" + kafkaProbe.getLocalPort())
                    .run("--spring.flyway.enabled=false")) {
                int scheduledTaskCount = cliContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                        .mapToInt(holder -> holder.getScheduledTasks().size())
                        .sum();
                assertThat(scheduledTaskCount).isZero();
                assertThat(cliContext.containsBeanDefinition(BootstrapAdminCommand.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                        .isFalse();
                assertThat(cliContext.getBeansOfType(ApplicationRunner.class)).isEmpty();
                assertThat(cliContext.getBeansOfType(CommandLineRunner.class)).isEmpty();

                ThreadPoolTaskScheduler youzanScheduler =
                        cliContext.getBean("youzanTaskScheduler", ThreadPoolTaskScheduler.class);
                assertThat(youzanScheduler.getScheduledThreadPoolExecutor().getTaskCount()).isZero();

                KafkaListenerEndpointRegistry registry = cliContext.getBean(KafkaListenerEndpointRegistry.class);
                assertThat(registry.getListenerContainers()).isEmpty();

                KafkaAdmin kafkaAdmin = cliContext.getBean(KafkaAdmin.class);
                assertThat(ReflectionTestUtils.getField(kafkaAdmin, "autoCreate")).isEqualTo(Boolean.FALSE);
                assertThat(ReflectionTestUtils.getField(kafkaAdmin, "fatalIfBrokerNotAvailable"))
                        .isEqualTo(Boolean.FALSE);

                BootstrapAdminResult result = cliContext.getBean(BootstrapAdminService.class)
                        .initialize(username, "CLI引导测试管理员", "Synthetic-cli-IT-password-123");
                assertThat(result.created()).isTrue();
                assertThat(acceptedKafkaConnections.get()).isZero();
            } finally {
                listenerRunning.set(false);
                acceptThread.join(1000L);
            }
        }
    }

    @Test
    void bootstrapAdminCommandAndCrmApi_failClosedWhenProdSecretsAreUnresolvedOrBlank() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        String[] withoutPepper = {
                "spring.profiles.active=prod",
                "DB_JDBC_URL=" + hikari.getJdbcUrl(),
                "DB_USER=" + hikari.getUsername(),
                "MYSQL_PASSWORD=" + hikari.getPassword(),
                "REDIS_PASSWORD=it-probe-redis",
                "JWT_SECRET=it-probe-jwt-secret-0123456789abcdef0123456789",
                "APP_AES_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "AI_INTERNAL_TOKEN=it-probe-ai-token"
        };
        assertThatThrownBy(() -> BootstrapAdminCommand
                .configureBuilder(new SpringApplicationBuilder(CrmApiApplication.class))
                .properties(withoutPepper)
                .run("--spring.flyway.enabled=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.crypto.blind-index-pepper");

        assertThatThrownBy(() -> new SpringApplicationBuilder(CrmApiApplication.class)
                .web(WebApplicationType.NONE)
                .properties(withoutPepper)
                .run("--spring.flyway.enabled=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.crypto.blind-index-pepper");

        String[] withoutRedisPassword = {
                "spring.profiles.active=prod",
                "DB_JDBC_URL=" + hikari.getJdbcUrl(),
                "DB_USER=" + hikari.getUsername(),
                "MYSQL_PASSWORD=" + hikari.getPassword(),
                "JWT_SECRET=it-probe-jwt-secret-0123456789abcdef0123456789",
                "APP_AES_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "APP_BLIND_INDEX_PEPPER=it-probe-pepper",
                "AI_INTERNAL_TOKEN=it-probe-ai-token"
        };
        assertThatThrownBy(() -> BootstrapAdminCommand
                .configureBuilder(new SpringApplicationBuilder(CrmApiApplication.class))
                .properties(withoutRedisPassword)
                .run("--spring.flyway.enabled=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.password");

        assertThatThrownBy(() -> new SpringApplicationBuilder(CrmApiApplication.class)
                .web(WebApplicationType.NONE)
                .properties(withoutRedisPassword)
                .run("--spring.flyway.enabled=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.password");
    }
}
