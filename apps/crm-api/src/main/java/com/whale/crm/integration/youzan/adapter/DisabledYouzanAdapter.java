package com.whale.crm.integration.youzan.adapter;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class DisabledYouzanAdapter implements YouzanAdapter {
    @Override
    public String name() {
        return "REAL_YOUZAN_DISABLED";
    }

    @Override
    public YouzanVerification verifyWebhook(String rawBody, YouzanSignature signature) {
        return new YouzanVerification(false, "REAL_YOUZAN_DISABLED");
    }

    @Override
    public YouzanPullPage pullOrders(long tenantId, String shopId, String cursor, int limit) {
        throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                "真实有赞接入未获Q11授权，当前FEATURE_REAL_YOUZAN已禁用");
    }
}
