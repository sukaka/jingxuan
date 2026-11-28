package com.whale.crm.platform.region.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 全国标准行政区划树节点（level: 1=省/直辖市/自治区/特别行政区，2=地级市/直辖市市级，3=区/县/镇街）。
 */
public record RegionNodeResponse(
        String code,
        String name,
        int level,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RegionNodeResponse> children) {
    public RegionNodeResponse {
        children = children == null || children.isEmpty() ? List.of() : List.copyOf(children);
    }
}
