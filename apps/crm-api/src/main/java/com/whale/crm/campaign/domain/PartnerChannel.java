package com.whale.crm.campaign.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PartnerChannel(Long id, String channelNo, String name, String channelType, String platformCode,
                             BigDecimal commissionPercent, Long businessOwnerUserId, String businessOwnerName,
                             String realName,
                             String contactPhone, String idCardNumber, String settlementMethod,
                             LocalDate cooperationStartDate, LocalDate cooperationEndDate,
                             String paymentAccountName, String paymentAccountNo, String paymentBankName,
                             String status, String remarks, Instant createdAt, Long version) {
}
