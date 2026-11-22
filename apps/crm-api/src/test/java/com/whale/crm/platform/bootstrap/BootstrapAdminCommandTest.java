package com.whale.crm.platform.bootstrap;

import com.whale.crm.platform.config.SchedulingConfiguration;
import com.whale.crm.platform.config.StartupSecretValidator;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapAdminCommandTest {

    @Test
    void runtimeDisabledProperties_disablesAllBackgroundConsumersWorkersAndSchedulers() {
        String[] properties = BootstrapAdminCommand.runtimeDisabledProperties();
        assertThat(Arrays.asList(properties)).contains(
                "app.outbox.relay.enabled=false",
                "app.exports.worker.enabled=false",
                "app.ai.worker.enabled=false",
                "app.ai.knowledge.sync.worker.enabled=false",
                "app.ai.knowledge.sync.kafka.enabled=false",
                "app.ai.delegation.worker.enabled=false",
                "app.ai.delegation.kafka.enabled=false",
                "app.notification.kafka.enabled=false",
                "app.notification.delivery.worker-enabled=false",
                "app.complaint.kafka.enabled=false",
                "app.customer-tier.kafka.enabled=false",
                "app.wecom.media.worker-enabled=false",
                "app.wecom.official.legacy-reconcile-enabled=false",
                "app.followup.automation.scheduler-enabled=false",
                "app.batch-tag.recovery.enabled=false",
                "app.security.auth.session-cleanup.enabled=false",
                "app.scheduling.enabled=false",
                "spring.task.scheduling.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.admin.auto-create=false",
                "spring.kafka.admin.fail-fast=false");
    }

    @Test
    void configureBuilder_underProdProfileOverridesProdDefaultsAndSystemProperties() {
        String previousOutbox = System.getProperty("app.outbox.relay.enabled");
        String previousNotif = System.getProperty("app.notification.kafka.enabled");
        String previousScheduling = System.getProperty("app.scheduling.enabled");
        String previousReconcile = System.getProperty("app.wecom.official.legacy-reconcile-enabled");
        String previousKafkaAutoCreate = System.getProperty("spring.kafka.admin.auto-create");
        String previousKafkaFailFast = System.getProperty("spring.kafka.admin.fail-fast");
        System.setProperty("app.outbox.relay.enabled", "true");
        System.setProperty("app.notification.kafka.enabled", "true");
        System.setProperty("app.scheduling.enabled", "true");
        System.setProperty("app.wecom.official.legacy-reconcile-enabled", "true");
        System.setProperty("spring.kafka.admin.auto-create", "true");
        System.setProperty("spring.kafka.admin.fail-fast", "true");
        try (ConfigurableApplicationContext context = BootstrapAdminCommand
                .configureBuilder(new SpringApplicationBuilder(ProdProbeConfig.class))
                .web(WebApplicationType.NONE)
                .properties(prodProbeProperties())
                .run()) {
            ConfigurableEnvironment env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).contains("prod");
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(env.getPropertySources().iterator().next().getName())
                    .isEqualTo(BootstrapAdminCommand.RUNTIME_DISABLED_PROPERTY_SOURCE);

            for (Map.Entry<String, Object> entry : BootstrapAdminCommand.runtimeDisabledPropertyMap().entrySet()) {
                assertThat(env.getProperty(entry.getKey()))
                        .as("Property %s in prod bootstrap env", entry.getKey())
                        .isEqualTo("false");
                assertThat(env.getProperty(entry.getKey(), Boolean.class))
                        .as("Boolean property %s in prod bootstrap env", entry.getKey())
                        .isFalse();
            }
        } finally {
            restoreSystemProperty("app.outbox.relay.enabled", previousOutbox);
            restoreSystemProperty("app.notification.kafka.enabled", previousNotif);
            restoreSystemProperty("app.scheduling.enabled", previousScheduling);
            restoreSystemProperty("app.wecom.official.legacy-reconcile-enabled", previousReconcile);
            restoreSystemProperty("spring.kafka.admin.auto-create", previousKafkaAutoCreate);
            restoreSystemProperty("spring.kafka.admin.fail-fast", previousKafkaFailFast);
        }
    }

    @Test
    void configureBuilder_underProdProfileDisablesSchedulingRunnersAndKafkaAdminInit() throws Exception {
        AtomicInteger acceptedConnections = new AtomicInteger();
        AtomicBoolean listenerRunning = new AtomicBoolean(true);
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(50);
            Thread acceptThread = new Thread(() -> {
                while (listenerRunning.get() && !listener.isClosed()) {
                    try (Socket accepted = listener.accept()) {
                        acceptedConnections.incrementAndGet();
                    } catch (IOException ignored) {
                        // SoTimeout or listener closed
                    }
                }
            }, "kafka-probe-listener");
            acceptThread.setDaemon(true);
            acceptThread.start();

            long startNanos = System.nanoTime();
            try (ConfigurableApplicationContext context = BootstrapAdminCommand
                    .configureBuilder(new SpringApplicationBuilder(SchedulingAndRunnerProbeConfig.class))
                    .properties(prodProbeProperties())
                    .properties("spring.kafka.bootstrap-servers=127.0.0.1:" + listener.getLocalPort())
                    .run()) {
                Duration startupElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                assertThat(startupElapsed).isLessThan(Duration.ofSeconds(30));

                int scheduledTaskCount = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                        .mapToInt(holder -> holder.getScheduledTasks().size())
                        .sum();
                assertThat(scheduledTaskCount).isZero();
                assertThat(context.containsBeanDefinition(BootstrapAdminCommand.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                        .isFalse();
                assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();

                KafkaAdmin kafkaAdmin = context.getBean(KafkaAdmin.class);
                assertThat(ReflectionTestUtils.getField(kafkaAdmin, "autoCreate")).isEqualTo(Boolean.FALSE);
                assertThat(ReflectionTestUtils.getField(kafkaAdmin, "fatalIfBrokerNotAvailable")).isEqualTo(Boolean.FALSE);

                Thread.sleep(60L);
                ProbeCounters counters = context.getBean(ProbeCounters.class);
                assertThat(counters.scheduledRuns.get()).isZero();
                assertThat(counters.applicationRunnerRuns.get()).isZero();
                assertThat(counters.commandLineRunnerRuns.get()).isZero();
                assertThat(acceptedConnections.get()).isZero();
                assertThat(context.getBean(NonRunnerBootstrapTarget.class).ping()).isEqualTo("ok");
            } finally {
                listenerRunning.set(false);
                acceptThread.join(1000L);
            }
        }
    }

    @Test
    void configureBuilder_removesUnconditionalEnableSchedulingProcessorAndRunners() throws Exception {
        try (ConfigurableApplicationContext context = BootstrapAdminCommand
                .configureBuilder(new SpringApplicationBuilder(UnconditionalSchedulingProbeConfig.class))
                .properties(prodProbeProperties())
                .run()) {
            int scheduledTaskCount = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .mapToInt(holder -> holder.getScheduledTasks().size())
                    .sum();
            assertThat(scheduledTaskCount).isZero();
            assertThat(context.containsBeanDefinition(BootstrapAdminCommand.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isFalse();
            assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();

            Thread.sleep(60L);
            ProbeCounters counters = context.getBean(ProbeCounters.class);
            assertThat(counters.scheduledRuns.get()).isZero();
            assertThat(counters.applicationRunnerRuns.get()).isZero();
            assertThat(counters.commandLineRunnerRuns.get()).isZero();
        }
    }

    @Test
    void schedulingConfiguration_isEnabledByDefaultWithoutBootstrapOverride() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DefaultSchedulingProbeConfig.class)
                .web(WebApplicationType.NONE)
                .run()) {
            int scheduledTaskCount = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .mapToInt(holder -> holder.getScheduledTasks().size())
                    .sum();
            assertThat(scheduledTaskCount).isGreaterThanOrEqualTo(1);
            assertThat(context.containsBeanDefinition(BootstrapAdminCommand.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isTrue();
        }
    }

    @Test
    void configureBuilder_underProdProfileFailsClosedWhenAnySecretUnsetOrBlank() {
        try (ConfigurableApplicationContext valid = BootstrapAdminCommand
                .configureBuilder(new SpringApplicationBuilder(SecretValidatedProdProbeConfig.class))
                .properties(prodProbeProperties())
                .run()) {
            assertThat(valid.isActive()).isTrue();
        }

        for (String envVar : List.of("REDIS_PASSWORD", "APP_BLIND_INDEX_PEPPER", "JWT_SECRET",
                "APP_AES_KEY_BASE64", "MYSQL_PASSWORD", "AI_INTERNAL_TOKEN")) {
            String[] withoutVar = Arrays.stream(prodProbeProperties())
                    .filter(prop -> !prop.startsWith(envVar + "="))
                    .toArray(String[]::new);
            assertThatThrownBy(() -> BootstrapAdminCommand
                    .configureBuilder(new SpringApplicationBuilder(SecretValidatedProdProbeConfig.class))
                    .properties(withoutVar)
                    .run())
                    .as("Missing %s under prod must fail closed", envVar)
                    .isInstanceOf(IllegalStateException.class);

            List<String> withBlankVar = new ArrayList<>(Arrays.asList(withoutVar));
            withBlankVar.add(envVar + "=");
            assertThatThrownBy(() -> BootstrapAdminCommand
                    .configureBuilder(new SpringApplicationBuilder(SecretValidatedProdProbeConfig.class))
                    .properties(withBlankVar.toArray(String[]::new))
                    .run())
                    .as("Blank %s under prod must fail closed", envVar)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static String[] prodProbeProperties() {
        return new String[]{
                "spring.profiles.active=prod",
                "MYSQL_PASSWORD=probe-mysql",
                "REDIS_PASSWORD=probe-redis",
                "JWT_SECRET=probe-jwt-secret-0123456789abcdef0123456789",
                "APP_AES_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "APP_BLIND_INDEX_PEPPER=probe-pepper",
                "AI_INTERNAL_TOKEN=probe-ai-token",
                "NOTIFICATION_KAFKA_ENABLED=true",
                "NOTIFICATION_WORKER_ENABLED=true",
                "KNOWLEDGE_SYNC_KAFKA_ENABLED=true",
                "KNOWLEDGE_SYNC_WORKER_ENABLED=true",
                "AI_WORKER_ENABLED=true"
        };
    }

    private static void restoreSystemProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    static class ProdProbeConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(StartupSecretValidator.class)
    static class SecretValidatedProdProbeConfig {
    }

    static final class ProbeCounters {
        final AtomicInteger scheduledRuns = new AtomicInteger();
        final AtomicInteger applicationRunnerRuns = new AtomicInteger();
        final AtomicInteger commandLineRunnerRuns = new AtomicInteger();
    }

    static final class NonRunnerBootstrapTarget {
        String ping() {
            return "ok";
        }
    }

    static final class ScheduledProbeTask {
        private final ProbeCounters counters;

        ScheduledProbeTask(ProbeCounters counters) {
            this.counters = counters;
        }

        @Scheduled(fixedDelay = 10)
        void tick() {
            counters.scheduledRuns.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SchedulingConfiguration.class, KafkaAutoConfiguration.class})
    static class SchedulingAndRunnerProbeConfig {
        @Bean
        ProbeCounters probeCounters() {
            return new ProbeCounters();
        }

        @Bean
        ScheduledProbeTask scheduledProbeTask(ProbeCounters counters) {
            return new ScheduledProbeTask(counters);
        }

        @Bean
        ApplicationRunner probeApplicationRunner(ProbeCounters counters) {
            return args -> counters.applicationRunnerRuns.incrementAndGet();
        }

        @Bean
        CommandLineRunner probeCommandLineRunner(ProbeCounters counters) {
            return args -> counters.commandLineRunnerRuns.incrementAndGet();
        }

        @Bean
        NewTopic probeTopic() {
            return new NewTopic("crm.bootstrap.probe.v1", 1, (short) 1);
        }

        @Bean
        NonRunnerBootstrapTarget nonRunnerBootstrapTarget() {
            return new NonRunnerBootstrapTarget();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class UnconditionalSchedulingProbeConfig {
        @Bean
        ProbeCounters probeCounters() {
            return new ProbeCounters();
        }

        @Bean
        ScheduledProbeTask scheduledProbeTask(ProbeCounters counters) {
            return new ScheduledProbeTask(counters);
        }

        @Bean
        ApplicationRunner probeApplicationRunner(ProbeCounters counters) {
            return args -> counters.applicationRunnerRuns.incrementAndGet();
        }

        @Bean
        CommandLineRunner probeCommandLineRunner(ProbeCounters counters) {
            return args -> counters.commandLineRunnerRuns.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SchedulingConfiguration.class)
    static class DefaultSchedulingProbeConfig {
        @Bean
        ProbeCounters probeCounters() {
            return new ProbeCounters();
        }

        @Bean
        ScheduledProbeTask scheduledProbeTask(ProbeCounters counters) {
            return new ScheduledProbeTask(counters);
        }
    }
}
