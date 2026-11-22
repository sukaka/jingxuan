package com.whale.crm.campaign.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PartnerChannelResponse(Long id, String channelNo, String name, String channelType,
                                     String platformCode, BigDecimal commissionPercent,
                                     Long businessOwnerUserId, String businessOwnerName, String realName,
                                     String contactPhone, boolean contactPhoneConfigured,
                                     String idCardNumber, boolean idCardConfigured,
                                     String settlementMethod, LocalDate cooperationStartDate,
                                     LocalDate cooperationEndDate, String paymentAccountName,
                                     String paymentAccountNo, boolean paymentAccountConfigured,
                                     String paymentBankName, String status, String remarks,
                                     Instant createdAt, Long version) {
}
