package com.whale.crm.integration.youzan.adapter;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

public interface YouzanAdapter {
    String name();

    YouzanVerification verifyWebhook(String rawBody, YouzanSignature signature);

    YouzanPullPage pullOrders(long tenantId, String shopId, String cursor, int limit);

    default YouzanPullPage pullOrders(String shopId, String cursor, int limit) {
        return pullOrders(TenantContext.currentTenantId(), shopId, cursor, limit);
    }

    default YouzanOrderEnvelope fetchOrder(long tenantId, String shopId, String eventId, String orderNo) {
        throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "当前有赞适配器不支持订单详情查询");
    }
}
