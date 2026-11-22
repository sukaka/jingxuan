package com.whale.crm.platform.bootstrap;

import com.whale.crm.CrmApiApplication;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BootstrapAdminCommand {
    static final String RUNTIME_DISABLED_PROPERTY_SOURCE = "bootstrapRuntimeDisabled";
    static final String RUNTIME_DISABLING_POST_PROCESSOR_BEAN_NAME = "bootstrapRuntimeDisablingPostProcessor";
    static final String SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME =
            "org.springframework.scheduling.config.internalScheduledAnnotationProcessor";

    private BootstrapAdminCommand() {
    }

    public static void main(String[] args) {
        String username = environment("BOOTSTRAP_ADMIN_USERNAME", "admin");
        String displayName = environment("BOOTSTRAP_ADMIN_DISPLAY_NAME", "系统管理员");
        String password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("必须通过 BOOTSTRAP_ADMIN_PASSWORD 环境变量提供初始密码");
        }
        try (ConfigurableApplicationContext context = configureBuilder(new SpringApplicationBuilder(CrmApiApplication.class))
                .run(args)) {
            BootstrapAdminResult result = context.getBean(BootstrapAdminService.class)
                    .initialize(username, displayName, password);
            System.out.printf("初始化管理员 %s：%s%n", username, result.created() ? "创建成功" : "已存在，未修改");
        }
    }

    static SpringApplicationBuilder configureBuilder(SpringApplicationBuilder builder) {
        return builder
                .web(WebApplicationType.NONE)
                .properties(runtimeDisabledProperties())
                .initializers(runtimeDisabledInitializer());
    }

    static ApplicationContextInitializer<ConfigurableApplicationContext> runtimeDisabledInitializer() {
        return context -> {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource(RUNTIME_DISABLED_PROPERTY_SOURCE, runtimeDisabledPropertyMap()));
            if (context instanceof BeanDefinitionRegistry registry) {
                registry.registerBeanDefinition(RUNTIME_DISABLING_POST_PROCESSOR_BEAN_NAME,
                        new RootBeanDefinition(BootstrapRuntimeDisablingPostProcessor.class,
                                BootstrapRuntimeDisablingPostProcessor::new));
            } else {
                context.addBeanFactoryPostProcessor(new BootstrapRuntimeDisablingPostProcessor());
            }
        };
    }

    static Map<String, Object> runtimeDisabledPropertyMap() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("app.outbox.relay.enabled", "false");
        properties.put("app.exports.worker.enabled", "false");
        properties.put("app.ai.worker.enabled", "false");
        properties.put("app.ai.knowledge.sync.worker.enabled", "false");
        properties.put("app.ai.knowledge.sync.kafka.enabled", "false");
        properties.put("app.ai.delegation.worker.enabled", "false");
        properties.put("app.ai.delegation.kafka.enabled", "false");
        properties.put("app.notification.kafka.enabled", "false");
        properties.put("app.notification.delivery.worker-enabled", "false");
        properties.put("app.complaint.kafka.enabled", "false");
        properties.put("app.customer-tier.kafka.enabled", "false");
        properties.put("app.wecom.media.worker-enabled", "false");
        properties.put("app.wecom.official.legacy-reconcile-enabled", "false");
        properties.put("app.followup.automation.scheduler-enabled", "false");
        properties.put("app.batch-tag.recovery.enabled", "false");
        properties.put("app.security.auth.session-cleanup.enabled", "false");
        properties.put("app.scheduling.enabled", "false");
        properties.put("spring.task.scheduling.enabled", "false");
        properties.put("spring.kafka.listener.auto-startup", "false");
        properties.put("spring.kafka.admin.auto-create", "false");
        properties.put("spring.kafka.admin.fail-fast", "false");
        return Map.copyOf(properties);
    }

    static String[] runtimeDisabledProperties() {
        return runtimeDisabledPropertyMap().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static final class BootstrapRuntimeDisablingPostProcessor implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            disableSchedulingAndRunners(registry,
                    registry instanceof ConfigurableListableBeanFactory beanFactory ? beanFactory : null);
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            if (beanFactory instanceof BeanDefinitionRegistry registry) {
                disableSchedulingAndRunners(registry, beanFactory);
            }
        }

        private static void disableSchedulingAndRunners(BeanDefinitionRegistry registry,
                                                        ConfigurableListableBeanFactory beanFactory) {
            if (registry.containsBeanDefinition(SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                registry.removeBeanDefinition(SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            }
            if (beanFactory == null) {
                return;
            }
            removeBeanDefinitionsOfType(registry, beanFactory, ApplicationRunner.class);
            removeBeanDefinitionsOfType(registry, beanFactory, CommandLineRunner.class);
        }

        private static void removeBeanDefinitionsOfType(BeanDefinitionRegistry registry,
                                                        ConfigurableListableBeanFactory beanFactory,
                                                        Class<?> runnerType) {
            for (String beanName : beanFactory.getBeanNamesForType(runnerType, true, false)) {
                if (registry.containsBeanDefinition(beanName)) {
                    registry.removeBeanDefinition(beanName);
                }
            }
        }
    }
}
