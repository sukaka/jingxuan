package com.whale.crm.platform.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期密钥与占位符 fail-closed 校验：
 * 1) 所有环境下，若关键密钥仍保留未解析的 {@code ${...}} 占位符，立即拒绝启动；
 * 2) {@code prod} profile 下，全部核心密钥必须显式提供且不可为空白。
 */
@Component
public class StartupSecretValidator implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    static final List<String> REQUIRED_PROD_SECRET_KEYS = List.of(
            "spring.datasource.password",
            "spring.data.redis.password",
            "app.security.auth.jwt-secret",
            "app.security.crypto.aes-key-base64",
            "app.security.crypto.blind-index-pepper",
            "app.ai.worker.internal-token");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        validate(environment);
    }

    public static void validate(Environment environment) {
        if (environment == null) {
            return;
        }
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        for (String key : REQUIRED_PROD_SECRET_KEYS) {
            String value;
            try {
                value = environment.getProperty(key);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("关键配置存在未解析占位符，拒绝启动: " + key, ex);
            }
            if (containsUnresolvedPlaceholder(value)) {
                throw new IllegalStateException("关键配置存在未解析占位符，拒绝启动: " + key);
            }
            if (prod && (value == null || value.isBlank())) {
                throw new IllegalStateException("生产环境缺少必需密钥配置，拒绝启动: " + key);
            }
        }
    }

    static boolean containsUnresolvedPlaceholder(String value) {
        return value != null && value.contains("${");
    }
}
