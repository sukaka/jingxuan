package com.whale.crm.fulfillment;

import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentHistory;
import com.whale.crm.fulfillment.domain.AppointmentItem;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.fulfillment.domain.RedemptionReversalHistory;
import com.whale.crm.fulfillment.domain.RedemptionStatusHistory;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementPayment;
import com.whale.crm.settlement.domain.SettlementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentSettlementMultiTenantRepositoryIT extends AbstractMysqlIT {
    @Autowired
    private FulfillmentRepository fulfillmentRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writesTargetExplicitTenant2ForAllFulfillmentAndSettlementMethodsWithEmptySecurityContext() {
        SecurityContextHolder.clearContext();
        assertThat(TenantContext.currentTenantId()).isEqualTo(1L);

        long tenant2 = 2L;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-10-20T08:00:00Z");

        String custNo = "C02-FC-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,nickname) VALUES (?,?,?,?)",
                tenant2, 1L, custNo, "多租户履约客户");
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=? AND customer_no=?",
                tenant2, custNo);

        String instNo = "C02-INST-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO crm_institution (tenant_id,institution_no,full_name,status,settlement_type,"
                        + "online_rate,offline_rate,platform_rate) VALUES (?,?,?,'ACTIVE','MONTHLY',0.18,0.12,0.15)",
                tenant2, instNo, "多租户机构");
        long institutionId = queryLong("SELECT id FROM crm_institution WHERE tenant_id=? AND institution_no=?",
                tenant2, instNo);

        String apptNo = "C02-A-" + suffix;
        fulfillmentRepository.insertAppointment(tenant2,
                new Appointment(null, apptNo, customerId, institutionId, 1L, 1L,
                        now.plusSeconds(3600), "c02", "SERVICE_DONE", 1L, null, null));
        Appointment appointment = fulfillmentRepository.findAppointmentByNo(tenant2, apptNo).orElseThrow();
        assertThat(fulfillmentRepository.findAppointmentByNo(1L, apptNo)).isEmpty();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_appointment WHERE tenant_id=1 AND id=?",
                appointment.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_appointment WHERE tenant_id=2 AND id=?",
                appointment.id())).isEqualTo(1);

        fulfillmentRepository.insertAppointmentItem(tenant2,
                new AppointmentItem(null, appointment.id(), 9001L, 101L, 2, null, null));
        List<AppointmentItem> apptItems = fulfillmentRepository.findAppointmentItems(tenant2, appointment.id());
        assertThat(apptItems).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_appointment_item WHERE tenant_id=1 AND appointment_id=?",
                appointment.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_appointment_item WHERE tenant_id=2 AND appointment_id=?",
                appointment.id())).isEqualTo(1);

        fulfillmentRepository.insertAppointmentHistory(tenant2,
                new AppointmentHistory(null, appointment.id(), "NONE", "PENDING_INSTITUTION", 1L, "c02", null));
        assertThat(fulfillmentRepository.findAppointmentHistory(tenant2, appointment.id())).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_appointment_history WHERE tenant_id=1 AND appointment_id=?",
                appointment.id())).isZero();

        String redKey = "c02-red-" + suffix;
        fulfillmentRepository.insertRedemption(tenant2,
                new Redemption(null, appointment.id(), customerId, institutionId, 1L, 1L, now, "c02-proof",
                        null, "POSTED", "PENDING_VERIFICATION", redKey, 1L, now, null, null));
        Redemption redemption = fulfillmentRepository.findRedemptionByKey(tenant2, redKey).orElseThrow();
        assertThat(fulfillmentRepository.findRedemptionByKey(1L, redKey)).isEmpty();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption WHERE tenant_id=1 AND id=?",
                redemption.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption WHERE tenant_id=2 AND id=?",
                redemption.id())).isEqualTo(1);

        fulfillmentRepository.insertRedemptionLine(tenant2,
                new RedemptionLine(null, redemption.id(), apptItems.get(0).id(), 9001L, 101L, 1, null));
        assertThat(fulfillmentRepository.findRedemptionLines(tenant2, redemption.id())).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_line WHERE tenant_id=1 AND redemption_id=?",
                redemption.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_line WHERE tenant_id=2 AND redemption_id=?",
                redemption.id())).isEqualTo(1);

        fulfillmentRepository.insertRedemptionHistory(tenant2,
                new RedemptionStatusHistory(null, redemption.id(), "PENDING_INSTITUTION", "POSTED", 1L, "c02", null));
        assertThat(fulfillmentRepository.findRedemptionHistory(tenant2, redemption.id())).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_status_history WHERE tenant_id=1 AND redemption_id=?",
                redemption.id())).isZero();

        String revKey = "c02-rev-" + suffix;
        fulfillmentRepository.insertReversal(tenant2,
                new RedemptionReversal(null, redemption.id(), customerId, institutionId, 1L, 1L, "REQUESTED",
                        "c02", 1L, now, null, null, null, revKey, null, redemption.id(), null, null));
        RedemptionReversal reversal = fulfillmentRepository.findReversalByRequestKey(tenant2, revKey).orElseThrow();
        assertThat(fulfillmentRepository.findReversalByRequestKey(1L, revKey)).isEmpty();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal WHERE tenant_id=1 AND id=?",
                reversal.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal WHERE tenant_id=2 AND id=?",
                reversal.id())).isEqualTo(1);

        fulfillmentRepository.insertReversalHistory(tenant2,
                new RedemptionReversalHistory(null, reversal.id(), "NONE", "REQUESTED", 1L, "c02", null));
        assertThat(fulfillmentRepository.findReversalHistory(tenant2, reversal.id())).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal_history WHERE tenant_id=1 AND reversal_id=?",
                reversal.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal_history WHERE tenant_id=2 AND reversal_id=?",
                reversal.id())).isEqualTo(1);

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(settlementRepository.lockRedemptions(tenant2, List.of(redemption.id()))).hasSize(1);
            assertThat(settlementRepository.findActiveReversalRedemptionIds(tenant2, List.of(redemption.id())))
                    .containsExactly(redemption.id());
        });

        long batchId = settlementRepository.insertBatch(tenant2,
                new SettlementBatch(null, institutionId, "MONTHLY", "2026-10", "LAST_DAY", "DEFAULT",
                        LocalDate.of(2026, 10, 31), "DRAFT", "PENDING_VERIFICATION",
                        0L, 0L, 0L, 0L, null, null, null, null, null));
        assertThat(settlementRepository.findBatch(1L, batchId)).isEmpty();
        assertThat(settlementRepository.findBatch(tenant2, batchId)).isPresent();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_batch WHERE tenant_id=1 AND id=?", batchId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_batch WHERE tenant_id=2 AND id=?", batchId))
                .isEqualTo(1);

        settlementRepository.insertLine(tenant2, batchId, redemption.id(), "{\"onlineRate\":0.18}");
        assertThat(settlementRepository.findLinesByRedemptions(tenant2, List.of(redemption.id()))).hasSize(1);
        assertThat(settlementRepository.findLinesByRedemptions(1L, List.of(redemption.id()))).isEmpty();
        transactionTemplate.executeWithoutResult(status ->
                assertThat(settlementRepository.lockLineIdByRedemption(tenant2, redemption.id())).isPresent());
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=1 AND settlement_batch_id=?",
                batchId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=2 AND settlement_batch_id=?",
                batchId)).isEqualTo(1);

        assertThat(settlementRepository.updateVerification(tenant2, batchId, 0L, 1000L, 100L, 900L,
                "RULE-2", "proof")).isEqualTo(1);
        String tradeNo = "C02-BANK-" + suffix;
        settlementRepository.insertPayment(tenant2,
                new SettlementPayment(null, batchId, 900L, tradeNo, now, 1L, "proof", 1L, null));
        assertThat(settlementRepository.updatePayment(tenant2, batchId, 1L, 900L, "PAID")).isEqualTo(1);
        assertThat(settlementRepository.findPaymentByTradeNo(1L, tradeNo)).isEmpty();
        assertThat(settlementRepository.findPaymentByTradeNo(tenant2, tradeNo)).isPresent();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_payment WHERE tenant_id=1 AND settlement_batch_id=?",
                batchId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_settlement_payment WHERE tenant_id=2 AND settlement_batch_id=?",
                batchId)).isEqualTo(1);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
