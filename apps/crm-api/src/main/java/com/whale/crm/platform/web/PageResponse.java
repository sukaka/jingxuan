package com.whale.crm.platform.web;

import java.util.List;

/**
 * 统一分页列表结构：{items,total,page,size}。
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
    public PageResponse {
        items = items == null ? null : List.copyOf(items);
    }
}
