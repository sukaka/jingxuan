package com.whale.crm.integration.youzan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class YouzanOfficialPayloadFilter extends OncePerRequestFilter {
    private static final String OFFICIAL_PATH = "/api/v1/webhooks/youzan/official";
    private final ObjectMapper objectMapper;

    public YouzanOfficialPayloadFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !OFFICIAL_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > 1_000_000) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(
                    ErrorCode.VALIDATION_FAILED.name(), "有赞消息体超过1MB限制", null,
                    requestId instanceof String id ? id : null)));
            return;
        }
        chain.doFilter(request, response);
    }
}
