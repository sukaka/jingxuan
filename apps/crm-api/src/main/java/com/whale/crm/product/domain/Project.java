package com.whale.crm.product.domain;

import java.time.Instant;

public record Project(Long id, String projectCode, String projectName, String categoryCode,
                      Long listPriceFen, String status, Instant createdAt, Long version) {
}
