package com.whale.crm.annualreport.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.config.AnnualH5Properties;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * C 端年终 H5 手机号验证码发送适配器（支持 DISABLED / MOCK / WEBHOOK）。
 * 日志严格脱敏，绝不打印明文手机号或明文验证码。
 */
public interface AnnualH5SmsSender {

    SendResult sendVerificationCode(String normalizedMobile, String code, int ttlSeconds);

    record SendResult(String channel, String devPreviewCode) {
    }

    @Component
    class ConfigurableAnnualH5SmsSender implements AnnualH5SmsSender {

        private static final Logger LOG = LoggerFactory.getLogger(ConfigurableAnnualH5SmsSender.class);
        private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

        private final AnnualH5Properties properties;
        private final ObjectMapper objectMapper;
        private final OkHttpClient httpClient;

        public ConfigurableAnnualH5SmsSender(AnnualH5Properties properties, ObjectMapper objectMapper) {
            this.properties = properties;
            this.objectMapper = objectMapper;
            this.httpClient = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(8))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
        }

        @Override
        public SendResult sendVerificationCode(String normalizedMobile, String code, int ttlSeconds) {
            String mode = properties.sms().mode() == null
                    ? "DISABLED" : properties.sms().mode().trim().toUpperCase(Locale.ROOT);
            if ("MOCK".equals(mode)) {
                LOG.info("Annual H5 SMS MOCK send to {} (ttl={}s)", MobileSupport.mask(normalizedMobile), ttlSeconds);
                return new SendResult("MOCK", code);
            }
            if ("WEBHOOK".equals(mode)) {
                String webhookUrl = properties.sms().webhookUrl();
                if (webhookUrl == null || webhookUrl.isBlank()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "短信网关地址未配置");
                }
                try {
                    String payload = objectMapper.writeValueAsString(Map.of(
                            "mobile", normalizedMobile,
                            "code", code,
                            "ttlSeconds", ttlSeconds,
                            "scene", "ANNUAL_REPORT_H5_LOGIN"
                    ));
                    Request.Builder builder = new Request.Builder()
                            .url(webhookUrl)
                            .post(RequestBody.create(payload, JSON));
                    String token = properties.sms().webhookToken();
                    if (token != null && !token.isBlank()) {
                        builder.header("Authorization", "Bearer " + token);
                    }
                    try (Response response = httpClient.newCall(builder.build()).execute()) {
                        if (!response.isSuccessful()) {
                            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "短信发送失败，请稍后重试");
                        }
                    }
                    LOG.info("Annual H5 SMS WEBHOOK sent to {}", MobileSupport.mask(normalizedMobile));
                    return new SendResult("WEBHOOK", null);
                } catch (BusinessException ex) {
                    throw ex;
                } catch (IOException ex) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "短信网关暂时不可用");
                }
            }
            throw new BusinessException(ErrorCode.CONFLICT, "当前未开启短信验证码通道，请联系管理员配置短信网关或通过专属链接访问");
        }
    }
}
