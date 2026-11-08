package com.whale.crm.integration.youzan.api;

import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.application.YouzanOfficialMessageService;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/youzan")
public class YouzanOfficialWebhookController extends YouzanBaseController {
    private final YouzanOfficialMessageService messageService;

    public YouzanOfficialWebhookController(YouzanOfficialMessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/official")
    public ResponseEntity<ApiResponse<YouzanEventResponse>> receive(
            @RequestBody String rawBody,
            @RequestHeader(name = "Client-Id") String clientId,
            @RequestHeader(name = "Event-Sign") String eventSign,
            @RequestHeader(name = "Event-Type") String eventType,
            HttpServletRequest request) {
        YouzanEventResponse response = messageService.receive(clientId, eventSign, eventType, rawBody);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response, requestId(request)));
    }
}
