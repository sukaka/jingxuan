package com.whale.crm.integration.youzan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanSignature;
import com.whale.crm.integration.youzan.adapter.YouzanVerification;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class YouzanWebhookController extends YouzanBaseController {
    private final YouzanAdapterRouter adapterRouter;
    private final YouzanEventService eventService;
    private final ObjectMapper objectMapper;

    public YouzanWebhookController(YouzanAdapterRouter adapterRouter, YouzanEventService eventService,
                                   ObjectMapper objectMapper) {
        this.adapterRouter = adapterRouter;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/youzan")
    public ResponseEntity<ApiResponse<YouzanEventResponse>> receive(
            @RequestBody String rawBody,
            @RequestHeader(name = "X-Youzan-Mock-Signature", required = false) String signature,
            @RequestHeader(name = "X-Youzan-Mock-Timestamp", required = false) Long timestamp,
            HttpServletRequest request) {
        YouzanVerification verification = adapterRouter.adapter().verifyWebhook(rawBody,
                new YouzanSignature(signature, timestamp));
        if (!verification.valid()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "有赞Mock签名无效、过期或真实接入未启用");
        }
        try {
            YouzanOrderEvent event = objectMapper.readValue(rawBody, YouzanOrderEvent.class);
            long tenantId = TenantContext.currentTenantId();
            YouzanEventResponse response = eventService.ingest(tenantId, event, "WEBHOOK", rawBody);
            eventService.processAsync(tenantId, response.id());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response, requestId(request)));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞回调JSON格式或字段无效");
        }
    }
}
