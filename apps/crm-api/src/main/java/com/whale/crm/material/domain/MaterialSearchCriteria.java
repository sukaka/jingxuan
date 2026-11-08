package com.whale.crm.material.domain;

public record MaterialSearchCriteria(String keyword, String categoryCode, Long institutionId, Long projectId,
                                     String cityCode, String status, Boolean globalOnly, int page, int size) {
}
