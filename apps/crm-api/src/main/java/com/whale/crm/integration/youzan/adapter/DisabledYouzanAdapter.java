package com.whale.crm.integration.youzan.adapter;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DisabledYouzanAdapter implements YouzanAdapter {
    private static final String DEFAULT_NAME = "REAL_YOUZAN_DISABLED";
    private static final String DEFAULT_REASON = "真实有赞接入未获Q11授权，当前FEATURE_REAL_YOUZAN已禁用";
    private static final DisabledYouzanAdapter SHOP_NOT_READY = new DisabledYouzanAdapter(
            "YOUZAN_SHOP_SYNC_DISABLED",
            "YOUZAN_SHOP_SYNC_DISABLED",
            ErrorCode.PENDING_BUSINESS_RULE,
            "该有赞店铺未配置或未启用同步，请先在店铺配置中完成授权测试并启用同步");
    private static final DisabledYouzanAdapter OFFICIAL_WEBHOOK_ONLY = new DisabledYouzanAdapter(
            "YOUZAN_OFFICIAL_WEBHOOK_ONLY",
            "OFFICIAL_SIGNATURE_REQUIRED",
            ErrorCode.PENDING_BUSINESS_RULE,
            "真实有赞接入已启用，请使用官方消息回调入口");

    private final String adapterName;
    private final String verifyReason;
    private final ErrorCode errorCode;
    private final String reason;

    @Autowired
    public DisabledYouzanAdapter() {
        this(DEFAULT_NAME, DEFAULT_NAME, ErrorCode.PENDING_BUSINESS_RULE, DEFAULT_REASON);
    }

    private DisabledYouzanAdapter(String adapterName, String verifyReason, ErrorCode errorCode, String reason) {
        this.adapterName = adapterName;
        this.verifyReason = verifyReason;
        this.errorCode = errorCode;
        this.reason = reason;
    }

    public static DisabledYouzanAdapter shopNotReady() {
        return SHOP_NOT_READY;
    }

    public static DisabledYouzanAdapter officialWebhookOnly() {
        return OFFICIAL_WEBHOOK_ONLY;
    }

    @Override
    public String name() {
        return adapterName;
    }

    @Override
    public YouzanVerification verifyWebhook(String rawBody, YouzanSignature signature) {
        return new YouzanVerification(false, verifyReason);
    }

    @Override
    public YouzanPullPage pullOrders(long tenantId, String shopId, String cursor, int limit) {
        throw new BusinessException(errorCode, reason);
    }

    @Override
    public YouzanOrderEnvelope fetchOrder(long tenantId, String shopId, String eventId, String orderNo) {
        throw new BusinessException(errorCode, reason);
    }
}
