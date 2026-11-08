package com.whale.crm.settlement.domain;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository {
    void insertBatch(long tenantId, SettlementBatch batch);
    Optional<SettlementBatch> findBatchByPeriod(long tenantId, long institutionId, String type, String periodKey);
    Optional<SettlementBatch> findBatch(long tenantId, long id);
    Optional<SettlementBatch> findBatchForUpdate(long tenantId, long id);
    List<SettlementBatch> searchBatches(long tenantId, SettlementScope scope, Long institutionId,
                                        String type, String status, int offset, int size);
    long countBatches(long tenantId, SettlementScope scope, Long institutionId, String type, String status);

    void insertLine(long tenantId, long batchId, long redemptionId, String rateSnapshot);
    List<SettlementLine> findLines(long tenantId, long batchId);
    Optional<SettlementLine> findLineByRedemption(long tenantId, long redemptionId);

    List<SettlementRedemption> lockRedemptions(long tenantId, List<Long> redemptionIds);

    int updateVerification(long tenantId, long id, long version, long grossFen, long commissionFen,
                           long netPayableFen, String ruleVersion, String verificationEvidence);
    int updatePayment(long tenantId, long id, long version, long paidFen, String status);

    void insertPayment(long tenantId, SettlementPayment payment);
    Optional<SettlementPayment> findPaymentByTradeNo(long tenantId, String bankTradeNo);
    List<SettlementPayment> findPayments(long tenantId, long batchId);

    List<ReconciliationIssue> entitlementLedgerIssues(long tenantId);
    List<ReconciliationIssue> orderCashIssues(long tenantId);
    List<ReconciliationIssue> refundAllocationIssues(long tenantId);
    List<ReconciliationIssue> settlementPaymentIssues(long tenantId, SettlementScope scope, Long institutionId);
    List<ReconciliationIssue> unbatchedRedemptionIssues(long tenantId, SettlementScope scope, Long institutionId);
}
