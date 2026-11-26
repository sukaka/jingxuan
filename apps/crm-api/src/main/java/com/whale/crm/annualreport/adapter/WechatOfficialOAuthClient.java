package com.whale.crm.annualreport.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.config.AnnualH5Properties;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 微信服务号官方网页静默授权客户端（snsapi_base）。
 * 重要安全与兼容约束：
 * 1. 仅调用用户级网页授权接口 /sns/oauth2/access_token，绝不调用全局 /cgi-bin/token，
 *    避免顶掉已授权给有赞第三方平台的服务号全局访问令牌；
 * 2. 不碰任何非官方个人微信协议。
 */
public interface WechatOfficialOAuthClient {

    OAuthIdentity exchangeMpCode(String code);

    record OAuthIdentity(String openid, String unionid) {
    }

    @Component
    class HttpWechatOfficialOAuthClient implements WechatOfficialOAuthClient {

        private final AnnualH5Properties properties;
        private final ObjectMapper objectMapper;
        private final OkHttpClient httpClient;

        public HttpWechatOfficialOAuthClient(AnnualH5Properties properties, ObjectMapper objectMapper) {
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
        public OAuthIdentity exchangeMpCode(String code) {
            if (!properties.isWechatMpConfigured()) {
                throw new BusinessException(ErrorCode.CONFLICT, "微信服务号静默授权尚未配置启用");
            }
            AnnualH5Properties.WechatMp mp = properties.wechatMp();
            String base = mp.apiBaseUrl().replaceAll("/+$", "");
            String url = base + "/sns/oauth2/access_token?appid=" + encode(mp.appId())
                    + "&secret=" + encode(mp.appSecret())
                    + "&code=" + encode(code)
                    + "&grant_type=authorization_code";
            return fetchIdentity(url, "微信服务号网页授权失败");
        }

        private OAuthIdentity fetchIdentity(String url, String errorPrefix) {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorPrefix + "（HTTP " + response.code() + "）");
                }
                JsonNode node = objectMapper.readTree(response.body().string());
                int errCode = node.path("errcode").asInt(0);
                if (errCode != 0) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED, errorPrefix + "（错误码 " + errCode + "）");
                }
                String openid = node.path("openid").asText("").trim();
                String unionid = node.path("unionid").asText("").trim();
                if (openid.isBlank()) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED, errorPrefix + "（未返回 openid）");
                }
                return new OAuthIdentity(openid, unionid.isBlank() ? null : unionid);
            } catch (BusinessException ex) {
                throw ex;
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorPrefix + "，网络暂时不可用");
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        }
    }
}
