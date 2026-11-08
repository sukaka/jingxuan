package com.whale.crm.integration.youzan.application;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanMessage;
import com.whale.crm.integration.youzan.adapter.YouzanOfficialMessageParser;
import com.whale.crm.integration.youzan.adapter.YouzanOfficialSignature;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEnvelope;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class YouzanOfficialMessageService {
    private final YouzanConfigRepository configRepository;
    private final YouzanRepository eventRepository;
    private final YouzanOfficialMessageParser parser;
    private final RealYouzanAdapter realAdapter;
    private final YouzanEventService eventService;
    private final AesGcmCipher cipher;
    private final TaskScheduler taskScheduler;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public YouzanOfficialMessageService(YouzanConfigRepository configRepository, YouzanRepository eventRepository,
                                        YouzanOfficialMessageParser parser, RealYouzanAdapter realAdapter,
                                        YouzanEventService eventService, AesGcmCipher cipher,
                                        @Qualifier("youzanTaskScheduler") TaskScheduler taskScheduler,
                                        IntegrationRuntimeConfigService runtimeConfig) {
        this.configRepository = configRepository;
        this.eventRepository = eventRepository;
        this.parser = parser;
        this.realAdapter = realAdapter;
        this.eventService = eventService;
        this.cipher = cipher;
        this.taskScheduler = taskScheduler;
        this.runtimeConfig = runtimeConfig;
    }

    public YouzanEventResponse receive(String clientId, String eventSign, String eventType, String rawBody) {
        YouzanMessage message = parser.parse(rawBody, eventType);
        YouzanShopConfig config = configRepository.findShopConfigByClientAndShop(clientId, message.shopId())
                .filter(shop -> Boolean.TRUE.equals(shop.syncEnabled()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "有赞店铺未授权或已停用"));
        if (!runtimeConfig.youzanRealEnabled(config.tenantId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "真实有赞接入未启用");
        }
        if (!YouzanOfficialSignature.valid(clientId, cipher.decrypt(config.clientSecretCipher()), rawBody, eventSign)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "有赞官方消息签名无效");
        }
        if (message.test()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "有赞测试消息不进入订单或支付处理");
        }
        if (message.orderNo() == null || message.orderNo().isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "当前仅支持包含订单号的有赞交易消息");
        }
        YouzanOrderEvent placeholder = new YouzanOrderEvent(message.messageId(), message.eventType(), message.shopId(),
                message.orderNo(), null, null,
                "NOTIFICATION", null, null, Instant.EPOCH, "REGULAR", null, null, "PLATFORM", null, List.of());
        YouzanEventResponse response = eventService.ingest(config.tenantId(), placeholder, "OFFICIAL_WEBHOOK", rawBody);
        if (!"PROCESSED".equals(response.processStatus())) {
            taskScheduler.schedule(() -> refreshAndProcess(config.tenantId(), response.id()), Instant.now().plusSeconds(30));
        }
        return response;
    }

    @org.springframework.context.annotation.Bean
    org.springframework.boot.ApplicationRunner youzanOfficialMessageRecoveryRunner() {
        return args -> taskScheduler.schedule(this::recoverPending, Instant.now());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${YOUZAN_MESSAGE_RECOVERY_MS:30000}")
    public void recoverPending() {
        Instant retryBefore = Instant.now().minusSeconds(30);
        for (YouzanShopConfig shop : configRepository.findSyncEnabledShopConfigs()) {
            if (!runtimeConfig.youzanRealEnabled(shop.tenantId())) {
                continue;
            }
            for (YouzanEvent event : eventRepository.findPendingEvents(shop.tenantId(), shop.shopId(), retryBefore, 100)) {
                refreshAndProcess(shop.tenantId(), event.id());
            }
        }
    }

    public void refreshAndProcess(long tenantId, long eventId) {
        if (!runtimeConfig.youzanRealEnabled(tenantId)) {
            return;
        }
        try {
            YouzanEvent raw = eventRepository.findEvent(tenantId, eventId).orElseThrow();
            if ("PROCESSED".equals(raw.processStatus())) {
                return;
            }
            YouzanMessage message = parser.parse(cipher.decrypt(raw.providerPayloadCipher()), raw.eventType());
            if (message.orderNo() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞交易消息缺少订单号");
            }
            YouzanOrderEnvelope detail = realAdapter.fetchOrder(tenantId, raw.shopId(), raw.eventId(), message.orderNo());
            eventService.replacePayloadAndProcess(tenantId, eventId, detail.event(), detail.providerPayload());
        } catch (Exception ex) {
            eventService.retryLater(tenantId, eventId, ex);
            taskScheduler.schedule(() -> refreshAndProcess(tenantId, eventId), Instant.now().plusSeconds(60));
        }
    }
}
