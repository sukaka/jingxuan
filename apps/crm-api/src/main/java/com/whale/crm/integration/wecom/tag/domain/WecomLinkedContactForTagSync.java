package com.whale.crm.integration.wecom.tag.domain;

/**
 * 已人工关联到未归档 CRM 客户且对应启用的企微员工映射的企微联系人投影。
 */
public record WecomLinkedContactForTagSync(
        long wecomContactId,
        String corpId,
        long customerId,
        long staffUserId,
        String wecomUserid,
        String externalUserid,
        String tagSnapshotJson) {
}
