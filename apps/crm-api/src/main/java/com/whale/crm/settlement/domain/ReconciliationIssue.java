package com.whale.crm.settlement.domain;

public record ReconciliationIssue(String checkType, Long resourceId, Long institutionId,
                                  Long expectedCount, Long actualCount, Long expectedFen, Long actualFen,
                                  String detail) {
}
