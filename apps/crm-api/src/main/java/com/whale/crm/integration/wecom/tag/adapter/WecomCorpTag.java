package com.whale.crm.integration.wecom.tag.adapter;

/**
 * 企微企业标签值对象（仅限官方企业标签 API 适配层产出，不含外部原始 JSON）。
 */
public record WecomCorpTag(
        String groupId,
        String groupName,
        String tagId,
        String tagName,
        int order) {
}
