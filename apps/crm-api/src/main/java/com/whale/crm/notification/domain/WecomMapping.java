package com.whale.crm.notification.domain;

public record WecomMapping(long id, long tenantId, String corpId, String wecomUserid, long staffUserId,
                           boolean archiveEnabled, boolean messageEnabled, long version) {
}
