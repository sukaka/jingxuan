package com.whale.crm.platform.bootstrap;

import com.whale.crm.CrmApiApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class BootstrapAdminCommand {
    private BootstrapAdminCommand() {
    }

    public static void main(String[] args) {
        String username = environment("BOOTSTRAP_ADMIN_USERNAME", "admin");
        String displayName = environment("BOOTSTRAP_ADMIN_DISPLAY_NAME", "系统管理员");
        String password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("必须通过 BOOTSTRAP_ADMIN_PASSWORD 环境变量提供初始密码");
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CrmApiApplication.class)
                .web(WebApplicationType.NONE)
                .properties("app.outbox.relay.enabled=false", "app.exports.worker.enabled=false",
                        "app.ai.worker.enabled=false", "app.ai.knowledge.sync.worker.enabled=false",
                        "app.ai.knowledge.sync.kafka.enabled=false")
                .run(args)) {
            BootstrapAdminResult result = context.getBean(BootstrapAdminService.class)
                    .initialize(username, displayName, password);
            System.out.printf("初始化管理员 %s：%s%n", username, result.created() ? "创建成功" : "已存在，未修改");
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
