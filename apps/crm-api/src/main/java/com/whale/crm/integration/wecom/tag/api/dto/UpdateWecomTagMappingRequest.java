package com.whale.crm.integration.wecom.tag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手工维护 CRM ↔ 企微标签映射关系：action 支持 BIND / UNLINK。
 */
public record UpdateWecomTagMappingRequest(
        @NotBlank(message = "操作类型不能为空")
        @Size(max = 16)
        String action,
        @Size(max = 128)
        String wecomGroupId,
        @Size(max = 128)
        String wecomGroupName,
        @Size(max = 128)
        String wecomTagId,
        @Size(max = 128)
        String wecomTagName) {
}
