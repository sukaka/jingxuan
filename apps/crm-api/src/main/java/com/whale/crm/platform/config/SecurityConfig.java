package com.whale.crm.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.security.AuthSecurityProperties;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.CsrfValidationFilter;
import com.whale.crm.platform.security.JwtAuthenticationFilter;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 安全默认拒绝：除登录、健康检查与 API 文档外，一切请求要求认证。
 * 认证链：JwtAuthenticationFilter（CRM_TOKEN Cookie + 服务端会话 + 实时权限解析）；
 * 写方法另有 CsrfValidationFilter 双提交校验。权限经 @PreAuthorize 在方法级强制，
 * 不得用前端菜单隐藏替代本配置。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({AuthSecurityProperties.class, CryptoProperties.class})
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证/CSRF 过滤器只挂在 Spring Security 链上，禁止 servlet 容器二次注册（避免重复执行）。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<CsrfValidationFilter> csrfValidationFilterRegistration(
            CsrfValidationFilter filter) {
        FilterRegistrationBean<CsrfValidationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   CsrfValidationFilter csrfValidationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfValidationFilter, JwtAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/youzan", "/api/v1/webhooks/youzan/official").permitAll()
                        .requestMatchers("/api/v1/integrations/wecom/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/integrations/refunds/*/callback").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/login", "/api/v1/auth/captcha",
                                "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, request, HttpServletResponse.SC_UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED.name(), "未认证或凭证已失效"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, request, HttpServletResponse.SC_FORBIDDEN,
                                        ErrorCode.FORBIDDEN.name(), "没有访问该资源的权限")));
        return http.build();
    }

    private void writeJson(HttpServletResponse response, HttpServletRequest request,
                           int status, String code, String message) throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(code, message, null, requestId instanceof String s ? s : null)));
    }
}
