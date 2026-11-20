package com.whale.crm.integration.youzan.api;

import com.whale.crm.integration.youzan.application.YouzanOfficialMessageService;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/webhooks/youzan")
public class YouzanOfficialWebhookController {
    private static final YouzanOfficialWebhookAck ACK_SUCCESS = new YouzanOfficialWebhookAck(0, "success");

    private final YouzanOfficialMessageService messageService;

    public YouzanOfficialWebhookController(YouzanOfficialMessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/official")
    public ResponseEntity<YouzanOfficialWebhookAck> receive(
            @RequestBody byte[] rawBodyBytes,
            @RequestHeader(name = "Client-Id") String clientId,
            @RequestHeader(name = "Event-Sign") String eventSign,
            @RequestHeader(name = "Event-Type") String eventType) {
        String rawBody = rawBodyBytes == null ? "" : new String(rawBodyBytes, StandardCharsets.UTF_8);
        messageService.receive(clientId, eventSign, eventType, rawBody);
        return ResponseEntity.ok(ACK_SUCCESS);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingHeader(MissingRequestHeaderException ex,
                                                           HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                ErrorCode.VALIDATION_FAILED.name(),
                "缺少有赞官方回调必填请求头: " + ex.getHeaderName(),
                null,
                requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }

    public record YouzanOfficialWebhookAck(int code, String msg) {
    }
}
