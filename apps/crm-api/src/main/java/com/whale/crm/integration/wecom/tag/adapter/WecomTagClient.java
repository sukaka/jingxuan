package com.whale.crm.integration.wecom.tag.adapter;

import java.util.List;

/**
 * 企业微信官方企业标签客户端抽象（官方接口 / 本地开发 Mock 切换；严禁个人微信）。
 */
public interface WecomTagClient {

    String name();

    String corpId();

    List<WecomCorpTag> listCorpTags();

    WecomCorpTag addCorpTag(String groupId, String groupName, String tagName, int order);

    void editCorpTag(String tagId, String tagName, int order);

    void deleteCorpTag(String tagId);

    void markCustomerTags(String wecomUserid, String externalUserid, List<String> addTagIds,
                          List<String> removeTagIds);
}
