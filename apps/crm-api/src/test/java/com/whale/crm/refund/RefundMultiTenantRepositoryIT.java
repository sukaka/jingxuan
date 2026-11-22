package com.whale.crm.refund;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.product.domain.CatalogRepository;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.ProductPackage;
import com.whale.crm.product.domain.Project;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.refund.domain.Refund;
import com.whale.crm.refund.domain.RefundItem;
import com.whale.crm.refund.domain.RefundPayment;
import com.whale.crm.refund.domain.RefundRepository;
import com.whale.crm.refund.domain.RefundStatusHistory;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
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

class RefundMultiTenantRepositoryIT extends AbstractMysqlIT {
    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TradingRepository tradingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writesTargetExplicitTenant2ForAllRefundRepositoryMethodsWithEmptySecurityContext() {
        SecurityContextHolder.clearContext();
        assertThat(TenantContext.currentTenantId()).isEqualTo(1L);

        long tenant2 = 2L;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-10-20T08:00:00Z");

        String custNo = "C02-RFC-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,nickname) VALUES (?,?,?,?)",
                tenant2, 1L, custNo, "多租户退款客户");
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=? AND customer_no=?",
                tenant2, custNo);

        String projectCode = "C02-RFPRJ-" + suffix;
        catalogRepository.insertProject(tenant2,
                new Project(null, projectCode, "退款项目", "CAT", 1000L, "ACTIVE", null, null));
        Project project = catalogRepository.findProjectByCode(tenant2, projectCode).orElseThrow();

        String pkgCode = "C02-RFP-" + suffix;
        catalogRepository.insertPackage(tenant2, new ProductPackage(null, pkgCode, "退款套餐", "ACTIVE", null, null));
        ProductPackage pkg = catalogRepository.findPackageByCode(tenant2, pkgCode).orElseThrow();
        catalogRepository.insertVersion(tenant2,
                new PackageVersion(null, pkg.id(), 1, "FIXED", 0, null, "ACTIVE", null, null));
        PackageVersion version = catalogRepository.findVersions(tenant2, pkg.id()).get(0);

        String skuCode = "C02-RFS-" + suffix;
        catalogRepository.insertSku(tenant2,
                new Sku(null, skuCode, version.id(), null, null, null, null, "标准", 1000L,
                        "ACTIVE", null, null, null, null));
        Sku sku = catalogRepository.findSkuByCode(tenant2, skuCode).orElseThrow();

        String orderNo = "C02-RFO-" + suffix;
        tradingRepository.insertOrder(tenant2,
                new Order(null, orderNo, customerId, null, 1L, 1L, null, "CRM", null, "REGULAR", "常规订单",
                        null, null, null, null, null, null, null, null, "PAID", "PLATFORM",
                        1000L, 0L, 1000L, 1000L, 0L, now, now, null, null));
        Order order = tradingRepository.findOrderByNo(tenant2, orderNo).orElseThrow();

        tradingRepository.insertOrderItem(tenant2,
                new OrderItem(null, order.id(), sku.id(), version.id(), skuCode, "标准", 1, 1000L, 1000L, "{}", null));
        OrderItem orderItem = tradingRepository.findOrderItems(tenant2, order.id()).get(0);

        tradingRepository.insertEntitlement(tenant2,
                new Entitlement(null, customerId, orderItem.id(), project.id(), null, 3, 3, 1,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AVAILABLE", null, null));
        long entitlementId = tradingRepository.findEntitlementsByOrderItemIds(tenant2, List.of(orderItem.id()))
                .get(0).id();

        String refundNo = "C02-RF-" + suffix;
        refundRepository.insertRefund(tenant2, new Refund(null, 1L, refundNo, order.id(), customerId,
                600L, 0L, 0L, "CUSTOMER_REQUEST", "t2", "REQUESTED",
                1L, null, null, null, null, null, null));
        Refund refund = refundRepository.findByNo(tenant2, refundNo).orElseThrow();
        assertThat(refundRepository.findByNo(1L, refundNo)).isEmpty();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund WHERE tenant_id=1 AND id=?", refund.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund WHERE tenant_id=2 AND id=?", refund.id())).isEqualTo(1);

        refundRepository.insertItem(tenant2, new RefundItem(null, 1L, refund.id(), entitlementId, 2, 600L));
        List<RefundItem> items = refundRepository.findItems(tenant2, refund.id());
        assertThat(items).hasSize(1);
        assertThat(refundRepository.findItems(1L, refund.id())).isEmpty();
        assertThat(refundRepository.updateItemAllocatedFen(tenant2, items.get(0).id(), refund.id(), 500L)).isEqualTo(1);
        assertThat(refundRepository.updateItemAllocatedFen(1L, items.get(0).id(), refund.id(), 400L)).isZero();

        assertThat(refundRepository.sumInFlightAmountByOrder(tenant2, order.id(), null)).isEqualTo(600L);
        assertThat(refundRepository.sumInFlightAmountByOrder(1L, order.id(), null)).isZero();
        assertThat(refundRepository.sumInFlightRevokeCountsByOrder(tenant2, order.id(), null))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.entitlementId()).isEqualTo(entitlementId);
                    assertThat(c.revokeCount()).isEqualTo(2L);
                });
        assertThat(refundRepository.sumInFlightRevokeCountsByOrder(1L, order.id(), null)).isEmpty();

        String extNo = "C02-EXT-" + suffix;
        refundRepository.insertPayment(tenant2, new RefundPayment(null, 1L, refund.id(), "MOCK", extNo,
                500L, "PENDING", "PROVIDER_CALLBACK", "EXEC-REF", null, null, null, null, null));
        assertThat(refundRepository.findPaymentByExternal(1L, "MOCK", extNo)).isEmpty();
        RefundPayment payment = refundRepository.findPaymentByExternal(tenant2, "MOCK", extNo).orElseThrow();
        assertThat(refundRepository.findPaymentsByExternal("MOCK", extNo))
                .singleElement()
                .satisfies(p -> assertThat(p.tenantId()).isEqualTo(tenant2));

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(refundRepository.findForUpdate(tenant2, refund.id())).isPresent();
            assertThat(refundRepository.findPaymentForUpdate(tenant2, payment.id())).isPresent();
        });

        assertThat(refundRepository.updatePaymentStatus(tenant2, payment.id(), payment.version(),
                "PENDING", "SUCCEEDED", now, null, 777L)).isEqualTo(1);
        RefundPayment updatedPayment = refundRepository.findPayments(tenant2, refund.id()).get(0);
        assertThat(updatedPayment.evidenceRef()).isEqualTo("EXEC-REF");
        assertThat(updatedPayment.voucherFileId()).isEqualTo(777L);

        refundRepository.insertHistory(tenant2, new RefundStatusHistory(null, 1L, refund.id(),
                "NONE", "REQUESTED", 1L, "t2", null));
        assertThat(refundRepository.findHistory(tenant2, refund.id())).hasSize(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund_status_history WHERE tenant_id=1 AND refund_id=?",
                refund.id())).isZero();
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
