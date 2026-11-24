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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class YouzanOfficialMessageService {
    public static final int MAX_AUTO_RETRIES = 10;

    private static final Logger log = LoggerFactory.getLogger(YouzanOfficialMessageService.class);

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
        if (message.test()) {
            YouzanShopConfig verified = verifyTestMessage(clientId, message.shopId(), rawBody, eventSign);
            log.info("忽略有赞官方测试消息 tenantId={} shopId={} eventType={}",
                    verified.tenantId(), verified.shopId(), message.eventType());
            return ignoredResponse(message);
        }
        YouzanShopConfig config = configRepository.findShopConfigByClientAndShop(clientId, message.shopId())
                .filter(shop -> Boolean.TRUE.equals(shop.syncEnabled()))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "有赞店铺未授权或已停用"));
        if (!runtimeConfig.youzanRealEnabled(config.tenantId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "真实有赞接入未启用");
        }
        if (!YouzanOfficialSignature.valid(clientId, cipher.decrypt(config.clientSecretCipher()), rawBody, eventSign)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "有赞官方消息签名无效");
        }
        if (isRefundEventType(message.eventType())) {
            log.warn("忽略暂不支持自动同步的有赞退款消息，请在CRM人工核实退款 tenantId={} shopId={} eventType={} messageId={}",
                    config.tenantId(), message.shopId(), message.eventType(), message.messageId());
            return ignoredResponse(message);
        }
        if (message.orderNo() == null || message.orderNo().isBlank()) {
            log.info("忽略无订单号的有赞官方消息 tenantId={} shopId={} eventType={} messageId={}",
                    config.tenantId(), message.shopId(), message.eventType(), message.messageId());
            return ignoredResponse(message);
        }
        YouzanOrderEvent placeholder = new YouzanOrderEvent(message.messageId(), message.eventType(), message.shopId(),
                message.orderNo(), null, null,
                "NOTIFICATION", null, null, Instant.EPOCH, "REGULAR", null, null, "PLATFORM", null, List.of());
        YouzanEventResponse response = eventService.ingest(config.tenantId(), placeholder, "OFFICIAL_WEBHOOK", rawBody);
        if (isPendingStatus(response.processStatus()) && attemptsOf(response.processAttempts()) < MAX_AUTO_RETRIES) {
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
                if (!isPendingStatus(event.processStatus())) {
                    continue;
                }
                if (attemptsOf(event.processAttempts()) >= MAX_AUTO_RETRIES) {
                    log.warn("有赞官方消息已达到最大自动重试次数，跳过自动恢复 tenantId={} eventId={} attempts={}",
                            shop.tenantId(), event.id(), event.processAttempts());
                    continue;
                }
                refreshAndProcess(shop.tenantId(), event.id());
            }
        }
    }

    public void refreshAndProcess(long tenantId, long eventId) {
        if (!runtimeConfig.youzanRealEnabled(tenantId)) {
            return;
        }
        int currentAttempts = 0;
        try {
            YouzanEvent raw = eventRepository.findEvent(tenantId, eventId).orElse(null);
            if (raw == null || !isPendingStatus(raw.processStatus())) {
                return;
            }
            currentAttempts = attemptsOf(raw.processAttempts());
            if (currentAttempts >= MAX_AUTO_RETRIES) {
                log.warn("有赞官方消息已达到最大自动重试次数，停止自动调度 tenantId={} eventId={} attempts={}",
                        tenantId, eventId, currentAttempts);
                return;
            }
            String orderNo = resolveOrderNo(raw);
            if (orderNo == null || orderNo.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞交易消息缺少订单号");
            }
            YouzanOrderEnvelope detail = realAdapter.fetchOrder(tenantId, raw.shopId(), raw.eventId(), orderNo);
            YouzanEventResponse processed = eventService.replacePayloadAndProcess(
                    tenantId, eventId, detail.event(), detail.providerPayload());
            if (processed != null && "RETRY".equals(processed.processStatus())) {
                int updatedAttempts = attemptsOf(processed.processAttempts());
                if (updatedAttempts < MAX_AUTO_RETRIES) {
                    taskScheduler.schedule(() -> refreshAndProcess(tenantId, eventId), Instant.now().plusSeconds(60));
                } else {
                    log.warn("有赞官方消息处理后达到最大自动重试次数，等待人工处理 tenantId={} eventId={} attempts={}",
                            tenantId, eventId, updatedAttempts);
                }
            }
        } catch (Exception ex) {
            YouzanEventResponse retried = eventService.retryLater(tenantId, eventId, ex);
            if (retried != null && !"RETRY".equals(retried.processStatus())) {
                return;
            }
            int updatedAttempts = retried != null && retried.processAttempts() != null
                    ? retried.processAttempts()
                    : currentAttempts + 1;
            if (updatedAttempts < MAX_AUTO_RETRIES) {
                taskScheduler.schedule(() -> refreshAndProcess(tenantId, eventId), Instant.now().plusSeconds(60));
            } else {
                log.warn("有赞官方消息重试失败并达到最大自动重试次数，等待人工处理 tenantId={} eventId={} attempts={}",
                        tenantId, eventId, updatedAttempts);
            }
        }
    }

    private String resolveOrderNo(YouzanEvent raw) {
        if (raw.externalOrderNo() != null && !raw.externalOrderNo().isBlank()) {
            return raw.externalOrderNo();
        }
        if (raw.providerPayloadCipher() == null || raw.providerPayloadCipher().isBlank()) {
            return null;
        }
        return parser.parse(cipher.decrypt(raw.providerPayloadCipher()), raw.eventType()).orderNo();
    }

    private YouzanShopConfig verifyTestMessage(String clientId, String shopId, String rawBody, String eventSign) {
        List<YouzanShopConfig> candidates = List.of();
        if (shopId != null && !shopId.isBlank()) {
            candidates = configRepository.findShopConfigByClientAndShop(clientId, shopId)
                    .map(List::of)
                    .orElse(List.of());
        }
        if (candidates.isEmpty()) {
            List<YouzanShopConfig> byClient = configRepository.findShopConfigsByClientId(clientId);
            candidates = byClient == null ? List.of() : byClient;
        }
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "有赞店铺未授权或已停用");
        }
        List<YouzanShopConfig> enabledTenants = candidates.stream()
                .filter(shop -> runtimeConfig.youzanRealEnabled(shop.tenantId()))
                .toList();
        if (enabledTenants.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "真实有赞接入未启用");
        }
        for (YouzanShopConfig shop : enabledTenants) {
            if (YouzanOfficialSignature.valid(clientId, cipher.decrypt(shop.clientSecretCipher()), rawBody, eventSign)) {
                return shop;
            }
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "有赞官方消息签名无效");
    }

    private static boolean isRefundEventType(String eventType) {
        return eventType != null && eventType.toLowerCase(Locale.ROOT).contains("refund");
    }

    private static YouzanEventResponse ignoredResponse(YouzanMessage message) {
        return new YouzanEventResponse(null, message.messageId(), message.eventType(), message.orderNo(),
                "IGNORED", 0, null, null, Instant.now(), null, false);
    }

    private static boolean isPendingStatus(String status) {
        return "RECEIVED".equals(status) || "RETRY".equals(status);
    }

    private static int attemptsOf(Integer attempts) {
        return attempts == null ? 0 : attempts;
    }
}
