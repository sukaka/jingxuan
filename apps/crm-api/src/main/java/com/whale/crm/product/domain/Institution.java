package com.whale.crm.product.domain;

public record Institution(Long id, String institutionNo, String fullName, String shortName,
                          String cityCode, String status, Long version) {
}
