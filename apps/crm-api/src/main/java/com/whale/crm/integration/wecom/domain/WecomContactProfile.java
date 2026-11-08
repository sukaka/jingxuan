package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomContactProfile(String corpId, String externalUserid, String wecomUserid, String name,
                                  String avatar, Integer contactType, Integer gender, String unionid,
                                  String remark, String description, Integer addWay, String channelState,
                                  Instant followCreatedAt, String tagSnapshotJson) {
}
