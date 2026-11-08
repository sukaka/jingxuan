package com.whale.crm.risk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCustomerLink;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskCaseResponse(
        Long id,
        String riskCaseNo,
        String riskType,
        String severity,
        String sourceType,
        String sourceRef,
        String status,
        Long ownerUserId,
        String evidence,
        String resolution,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        Long orderId,
        String orderNo,
        Long customerId,
        String customerNo,
        String customerName) {
    public static RiskCaseResponse of(RiskCase riskCase) {
        return of(riskCase, null);
    }

    public static RiskCaseResponse of(RiskCase riskCase, RiskCustomerLink customerLink) {
        return new RiskCaseResponse(riskCase.id(), riskCase.riskCaseNo(), riskCase.riskType(), riskCase.severity(),
                riskCase.sourceType(), riskCase.sourceRef(), riskCase.status(), riskCase.ownerUserId(),
                riskCase.evidence(), riskCase.resolution(), riskCase.resolvedAt(), riskCase.createdAt(),
                riskCase.updatedAt(), riskCase.version(), customerLink == null ? null : customerLink.orderId(),
                customerLink == null ? null : customerLink.orderNo(),
                customerLink == null ? null : customerLink.customerId(),
                customerLink == null ? null : customerLink.customerNo(),
                customerLink == null ? null : customerLink.customerName());
    }
}
