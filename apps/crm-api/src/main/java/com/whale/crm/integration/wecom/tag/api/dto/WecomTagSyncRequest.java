package com.whale.crm.integration.wecom.tag.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 触发企微标签同步请求体：direction 支持 PULL / PUSH / BIDIRECTIONAL（缺省 BIDIRECTIONAL）。
 */
public record WecomTagSyncRequest(
        @Size(max = 32)
        String direction) {
}
