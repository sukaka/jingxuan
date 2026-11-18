package com.whale.crm.customer.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 各条件组之间取 AND，同一组项目/代码取 OR；净核销阈值作用于任一选中项目。 */
public record CustomerSegmentCriteria(
        @Size(max = 20) List<@Size(max = 64) String> cityCodes,
        @Size(max = 20) List<@Size(max = 16) String> tierCodes,
        @Size(max = 20) List<Long> attentionProjectIds,
        @Size(max = 20) List<Long> purchasedProjectIds,
        @Size(max = 20) List<Long> redeemedProjectIds,
        @Min(1) @Max(10000) Integer redeemedMinCount) {
    public CustomerSegmentCriteria {
        cityCodes = cityCodes == null ? List.of() : List.copyOf(cityCodes);
        tierCodes = tierCodes == null ? List.of() : List.copyOf(tierCodes);
        attentionProjectIds = attentionProjectIds == null ? List.of() : List.copyOf(attentionProjectIds);
        purchasedProjectIds = purchasedProjectIds == null ? List.of() : List.copyOf(purchasedProjectIds);
        redeemedProjectIds = redeemedProjectIds == null ? List.of() : List.copyOf(redeemedProjectIds);
        redeemedMinCount = redeemedMinCount == null ? Integer.valueOf(1) : redeemedMinCount;
    }

    public static CustomerSegmentCriteria empty() {
        return new CustomerSegmentCriteria(null, null, null, null, null, null);
    }
}
