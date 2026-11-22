package com.whale.crm.settlement.infrastructure;

import com.whale.crm.settlement.domain.ReconciliationIssue;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementLine;
import com.whale.crm.settlement.domain.SettlementPayment;
import com.whale.crm.settlement.domain.SettlementRedemption;
import com.whale.crm.settlement.domain.SettlementRepository;
import com.whale.crm.settlement.domain.SettlementScope;
import com.whale.crm.settlement.infrastructure.mapper.SettlementMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisSettlementRepository implements SettlementRepository {
    private final SettlementMapper mapper;

    public MybatisSettlementRepository(SettlementMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insertBatch(long tenantId, SettlementBatch batch) {
        SettlementMapper.BatchInsertCommand command = SettlementMapper.BatchInsertCommand.from(batch);
        mapper.insertBatch(tenantId, command);
        return command.getId();
    }

    @Override
    public Optional<SettlementBatch> findBatch(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectBatch(tenantId, id));
    }

    @Override
    public Optional<SettlementBatch> findBatchForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectBatchForUpdate(tenantId, id));
    }

    @Override
    public List<SettlementBatch> searchBatches(long tenantId, SettlementScope scope, Long institutionId,
                                               String type, String status, int offset, int size) {
        return mapper.searchBatches(tenantId, scope, institutionId, type, status, offset, size);
    }

    @Override
    public long countBatches(long tenantId, SettlementScope scope, Long institutionId, String type, String status) {
        return mapper.countBatches(tenantId, scope, institutionId, type, status);
    }

    @Override
    public void insertLine(long tenantId, long batchId, long redemptionId, String rateSnapshot) {
        mapper.insertLine(tenantId, batchId, redemptionId, rateSnapshot);
    }

    @Override
    public List<SettlementLine> findLines(long tenantId, long batchId) {
        return mapper.selectLines(tenantId, batchId);
    }

    @Override
    public Optional<SettlementLine> findLineByRedemption(long tenantId, long redemptionId) {
        return Optional.ofNullable(mapper.selectLineByRedemption(tenantId, redemptionId));
    }

    @Override
    public List<SettlementLine> findLinesByRedemptions(long tenantId, List<Long> redemptionIds) {
        if (redemptionIds == null || redemptionIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectLinesByRedemptions(tenantId, redemptionIds.stream().distinct().sorted().toList());
    }

    @Override
    public Optional<Long> lockLineIdByRedemption(long tenantId, long redemptionId) {
        return Optional.ofNullable(mapper.lockLineIdByRedemption(tenantId, redemptionId));
    }

    @Override
    public List<SettlementRedemption> lockRedemptions(long tenantId, List<Long> redemptionIds) {
        if (redemptionIds == null || redemptionIds.isEmpty()) {
            return List.of();
        }
        return mapper.lockRedemptions(tenantId, redemptionIds.stream().distinct().sorted().toList());
    }

    @Override
    public List<Long> findActiveReversalRedemptionIds(long tenantId, List<Long> redemptionIds) {
        if (redemptionIds == null || redemptionIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectActiveReversalRedemptionIds(tenantId, redemptionIds.stream().distinct().sorted().toList());
    }

    @Override
    public int updateVerification(long tenantId, long id, long version, long grossFen, long commissionFen,
                                  long netPayableFen, String ruleVersion, String verificationEvidence) {
        return mapper.updateVerification(tenantId, id, version, grossFen, commissionFen, netPayableFen,
                ruleVersion, verificationEvidence);
    }

    @Override
    public int updatePayment(long tenantId, long id, long version, long paidFen, String status) {
        return mapper.updatePayment(tenantId, id, version, paidFen, status);
    }

    @Override
    public void insertPayment(long tenantId, SettlementPayment payment) {
        mapper.insertPayment(tenantId, payment);
    }

    @Override
    public Optional<SettlementPayment> findPaymentByTradeNo(long tenantId, String bankTradeNo) {
        return Optional.ofNullable(mapper.selectPaymentByTradeNo(tenantId, bankTradeNo));
    }

    @Override
    public List<SettlementPayment> findPayments(long tenantId, long batchId) {
        return mapper.selectPayments(tenantId, batchId);
    }

    @Override
    public List<ReconciliationIssue> entitlementLedgerIssues(long tenantId) {
        return mapper.entitlementLedgerIssues(tenantId);
    }

    @Override
    public List<ReconciliationIssue> orderCashIssues(long tenantId) {
        return mapper.orderCashIssues(tenantId);
    }

    @Override
    public List<ReconciliationIssue> refundAllocationIssues(long tenantId) {
        return mapper.refundAllocationIssues(tenantId);
    }

    @Override
    public List<ReconciliationIssue> settlementPaymentIssues(long tenantId, SettlementScope scope, Long institutionId) {
        return mapper.settlementPaymentIssues(tenantId, scope, institutionId);
    }

    @Override
    public List<ReconciliationIssue> unbatchedRedemptionIssues(long tenantId, SettlementScope scope, Long institutionId) {
        return mapper.unbatchedRedemptionIssues(tenantId, scope, institutionId);
    }
}
