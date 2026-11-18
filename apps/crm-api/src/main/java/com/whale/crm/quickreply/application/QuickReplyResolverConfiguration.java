package com.whale.crm.quickreply.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class QuickReplyResolverConfiguration {
    @Bean
    List<QuickReplyVariableResolver> quickReplyVariableResolvers() {
        return List.of(
                new FactQuickReplyVariableResolver("customer_name"),
                new FactQuickReplyVariableResolver("manager_name"),
                new FactQuickReplyVariableResolver("project_name"),
                new FactQuickReplyVariableResolver("total_count"),
                new FactQuickReplyVariableResolver("used_count"),
                new FactQuickReplyVariableResolver("remaining_count"),
                new FactQuickReplyVariableResolver("last_visit_date"),
                new FactQuickReplyVariableResolver("last_writeoff_date"),
                new FactQuickReplyVariableResolver("appointment_date"),
                new FactQuickReplyVariableResolver("appointment_time"),
                new FactQuickReplyVariableResolver("institution_name"),
                new FactQuickReplyVariableResolver("institution_address"),
                new FactQuickReplyVariableResolver("doctor_name"),
                new FactQuickReplyVariableResolver("expire_date"),
                new FactQuickReplyVariableResolver("order_no"));
    }
}
