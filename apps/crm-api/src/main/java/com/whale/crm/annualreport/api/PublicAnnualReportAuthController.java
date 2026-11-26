package com.whale.crm.annualreport.api;

import com.whale.crm.annualreport.api.dto.PublicAnnualAuthConfigResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualBookingResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualMeResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsSendRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsSendResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsVerifyRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualSmsVerifyResponse;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryRequest;
import com.whale.crm.annualreport.api.dto.PublicAnnualTelemetryResponse;
import com.whale.crm.annualreport.application.AnnualReportClientAuthService;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * C 端年终购买轨迹 H5 统一自动登录入口接口（微信服务号静默授权 + 预留手机号验证码兜底）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/annual-reports")
public class PublicAnnualReportAuthController {

    private final AnnualReportClientAuthService authService;

    public PublicAnnualReportAuthController(AnnualReportClientAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/auth/config")
    public ApiResponse<PublicAnnualAuthConfigResponse> config(HttpServletRequest request) {
        return ApiResponse.ok(authService.authConfig(), requestId(request));
    }

    @GetMapping("/auth/wechat/authorize")
    public ResponseEntity<Void> wechatAuthorize(
            @RequestParam(required = false) @Min(2020) @Max(2099) Integer year,
            @RequestParam(required = false) @Size(max = 64) String shopId,
            HttpServletResponse response) {
        String redirectUrl = authService.startWechatAuthorize(year, shopId, response);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    @GetMapping("/auth/wechat/callback")
    public ResponseEntity<Void> wechatCallback(
            @RequestParam @NotBlank @Size(max = 256) String code,
            @RequestParam @NotBlank @Size(max = 1024) String state,
            HttpServletRequest request,
            HttpServletResponse response) {
        String redirectUrl = authService.handleWechatCallback(code, state, request, response);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    @PostMapping("/auth/sms/send")
    public ApiResponse<PublicAnnualSmsSendResponse> sendSms(
            @Valid @RequestBody PublicAnnualSmsSendRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.sendSmsCode(body), requestId(request));
    }

    @PostMapping("/auth/sms/verify")
    public ApiResponse<PublicAnnualSmsVerifyResponse> verifySms(
            @Valid @RequestBody PublicAnnualSmsVerifyRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ApiResponse.ok(authService.verifySmsCode(body, request, response), requestId(request));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Boolean>> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(response);
        return ApiResponse.ok(Map.of("loggedOut", true), requestId(request));
    }

    @GetMapping("/me")
    public ApiResponse<PublicAnnualMeResponse> me(
            @RequestParam(required = false) @Min(2020) @Max(2099) Integer year,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.getMyReport(year, request), requestId(request));
    }

    @PostMapping("/me/telemetry")
    public ApiResponse<PublicAnnualTelemetryResponse> meTelemetry(
            @RequestParam(required = false) @Min(2020) @Max(2099) Integer year,
            @Valid @RequestBody PublicAnnualTelemetryRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.recordMyTelemetry(year, body, request), requestId(request));
    }

    @PostMapping("/me/booking-intent")
    public ApiResponse<PublicAnnualBookingResponse> meBookingIntent(
            @RequestParam(required = false) @Min(2020) @Max(2099) Integer year,
            @Valid @RequestBody PublicAnnualBookingRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(authService.submitMyBookingIntent(year, body, request), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
