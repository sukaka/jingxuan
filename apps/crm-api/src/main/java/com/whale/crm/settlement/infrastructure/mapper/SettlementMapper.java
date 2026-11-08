package com.whale.crm.settlement.infrastructure.mapper;

import com.whale.crm.settlement.domain.ReconciliationIssue;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementLine;
import com.whale.crm.settlement.domain.SettlementPayment;
import com.whale.crm.settlement.domain.SettlementRedemption;
import com.whale.crm.settlement.domain.SettlementScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SettlementMapper {
    void insertBatch(@Param("tenantId") long tenantId, @Param("entity") SettlementBatch entity);
    SettlementBatch selectBatchByPeriod(@Param("tenantId") long tenantId, @Param("institutionId") long institutionId,
                                        @Param("type") String type, @Param("periodKey") String periodKey);
    SettlementBatch selectBatch(@Param("tenantId") long tenantId, @Param("id") long id);
    SettlementBatch selectBatchForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<SettlementBatch> searchBatches(@Param("tenantId") long tenantId, @Param("scope") SettlementScope scope,
                                        @Param("institutionId") Long institutionId, @Param("type") String type,
                                        @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    long countBatches(@Param("tenantId") long tenantId, @Param("scope") SettlementScope scope,
                      @Param("institutionId") Long institutionId, @Param("type") String type, @Param("status") String status);
    void insertLine(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                    @Param("redemptionId") long redemptionId, @Param("rateSnapshot") String rateSnapshot);
    List<SettlementLine> selectLines(@Param("tenantId") long tenantId, @Param("batchId") long batchId);
    SettlementLine selectLineByRedemption(@Param("tenantId") long tenantId, @Param("redemptionId") long redemptionId);
    List<SettlementRedemption> lockRedemptions(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids);
    int updateVerification(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                           @Param("grossFen") long grossFen, @Param("commissionFen") long commissionFen,
                           @Param("netPayableFen") long netPayableFen, @Param("ruleVersion") String ruleVersion,
                           @Param("verificationEvidence") String verificationEvidence);
    int updatePayment(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                      @Param("paidFen") long paidFen, @Param("status") String status);
    void insertPayment(@Param("tenantId") long tenantId, @Param("entity") SettlementPayment entity);
    SettlementPayment selectPaymentByTradeNo(@Param("tenantId") long tenantId, @Param("bankTradeNo") String bankTradeNo);
    List<SettlementPayment> selectPayments(@Param("tenantId") long tenantId, @Param("batchId") long batchId);
    List<ReconciliationIssue> entitlementLedgerIssues(@Param("tenantId") long tenantId);
    List<ReconciliationIssue> orderCashIssues(@Param("tenantId") long tenantId);
    List<ReconciliationIssue> refundAllocationIssues(@Param("tenantId") long tenantId);
    List<ReconciliationIssue> settlementPaymentIssues(@Param("tenantId") long tenantId, @Param("scope") SettlementScope scope,
                                                       @Param("institutionId") Long institutionId);
    List<ReconciliationIssue> unbatchedRedemptionIssues(@Param("tenantId") long tenantId, @Param("scope") SettlementScope scope,
                                                         @Param("institutionId") Long institutionId);
}
