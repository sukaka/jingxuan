package com.whale.crm.product;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.product.domain.CatalogRepository;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageSelectionGroup;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.ProductPackage;
import com.whale.crm.product.domain.Project;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderItemSelection;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.PaymentTxn;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogTradingMultiTenantRepositoryIT extends AbstractMysqlIT {

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TradingRepository tradingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writesTargetExplicitTenantEvenWhenSecurityContextIsEmpty() {
        SecurityContextHolder.clearContext();
        assertThat(TenantContext.currentTenantId()).isEqualTo(1L);

        long tenant2 = 2L;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        String projectCode = "C02-PRJ-" + suffix;
        catalogRepository.insertProject(tenant2,
                new Project(null, projectCode, "多租户项目", "CAT", 10000L, "ACTIVE", null, null));
        Project project = catalogRepository.findProjectByCode(tenant2, projectCode).orElseThrow();
        assertThat(catalogRepository.findProjectByCode(1L, projectCode)).isEmpty();
        assertThat(catalogRepository.updateProject(tenant2,
                new Project(project.id(), projectCode, "多租户项目更新", "CAT", 12000L, "ACTIVE", null, project.version())))
                .isEqualTo(1);

        String packageCode = "C02-PKG-" + suffix;
        catalogRepository.insertPackage(tenant2,
                new ProductPackage(null, packageCode, "多租户套餐", "ACTIVE", null, null));
        ProductPackage productPackage = catalogRepository.findPackageByCode(tenant2, packageCode).orElseThrow();
        assertThat(catalogRepository.findPackageByCode(1L, packageCode)).isEmpty();
        assertThat(catalogRepository.updatePackage(tenant2,
                new ProductPackage(productPackage.id(), packageCode, "多租户套餐更新", "ACTIVE", null,
                        productPackage.version())))
                .isEqualTo(1);

        catalogRepository.insertVersion(tenant2,
                new PackageVersion(null, productPackage.id(), 1, "CHOOSE_N", 1, null, "DRAFT", null, null));
        PackageVersion version = catalogRepository.findVersions(tenant2, productPackage.id()).get(0);
        assertThat(catalogRepository.updateVersionDraft(tenant2,
                new PackageVersion(version.id(), productPackage.id(), 1, "FIXED", 0, null, "DRAFT", null,
                        version.version())))
                .isEqualTo(1);
        catalogRepository.insertSelectionGroup(tenant2,
                new PackageSelectionGroup(null, version.id(), "G1", "组1", 1, 1, 1, null));
        catalogRepository.insertVersionProject(tenant2,
                new PackageProject(null, version.id(), project.id(), null, null, null, null, null,
                        2, 1, 10000L, "G1", 10000L, null));
        assertThat(catalogRepository.findSelectionGroups(tenant2, version.id())).hasSize(1);
        assertThat(catalogRepository.findVersionProjects(tenant2, version.id())).hasSize(1);

        String skuCode = "C02-SKU-" + suffix;
        catalogRepository.insertSku(tenant2,
                new Sku(null, skuCode, version.id(), null, null, null, null, "多租户规格", 10000L,
                        "INACTIVE", null, null, null, null));
        Sku sku = catalogRepository.findSkuByCode(tenant2, skuCode).orElseThrow();
        assertThat(catalogRepository.findSkuByCode(1L, skuCode)).isEmpty();
        assertThat(catalogRepository.updateSku(tenant2,
                new Sku(sku.id(), skuCode, version.id(), null, null, null, null, "多租户规格更新", 11000L,
                        "INACTIVE", null, null, null, sku.version())))
                .isEqualTo(1);

        String customerNo = "C02-CUST-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,nickname) VALUES (?,?,?,?)",
                tenant2, 1L, customerNo, "多租户客户");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_customer WHERE tenant_id=? AND customer_no=?", Long.class, tenant2, customerNo);

        String tmpOrderNo = "C02-TMP-" + suffix;
        tradingRepository.insertOrder(tenant2,
                new Order(null, tmpOrderNo, customerId, null, 1L, 1L, null, "CRM", null, "REGULAR", "常规订单",
                        null, null, null, null, null, null, null, null, "DRAFT", "PLATFORM",
                        10000L, 0L, 10000L, 0L, 0L, Instant.now(), null, null, null));
        Order order = tradingRepository.findOrderByNo(tenant2, tmpOrderNo).orElseThrow();
        assertThat(tradingRepository.findOrderByNo(1L, tmpOrderNo)).isEmpty();

        tradingRepository.insertOrderItem(tenant2,
                new OrderItem(null, order.id(), sku.id(), version.id(), skuCode, "多租户规格", 1, 10000L, 10000L, "{}",
                        null));
        List<OrderItem> items = tradingRepository.findOrderItems(tenant2, order.id());
        assertThat(items).hasSize(1);
        long itemId = items.get(0).id();
        tradingRepository.insertOrderItemSelection(tenant2,
                new OrderItemSelection(itemId, version.id(), "G1", "组1", 1, 1, project.id(), projectCode, "多租户项目",
                        2, 10000L, 1));

        String tradeNo = "C02-TRD-" + suffix;
        tradingRepository.insertPayment(tenant2,
                new PaymentTxn(null, order.id(), "BANK_TRANSFER", "PLATFORM", tradeNo, 10000L, "CONFIRMED",
                        Instant.now(), 1L, null, "C02-EV", "MANUAL_REVIEW", null, null));
        assertThat(tradingRepository.findPaymentByTradeNo(tenant2, "BANK_TRANSFER", tradeNo)).isPresent();
        assertThat(tradingRepository.findPaymentByTradeNo(1L, "BANK_TRANSFER", tradeNo)).isEmpty();

        tradingRepository.insertStatusHistory(tenant2,
                new OrderStatusHistory(null, order.id(), "NONE", "DRAFT", 1L, "CREATE", null, "C02", null));
        assertThat(tradingRepository.findStatusHistory(tenant2, order.id())).hasSize(1);

        tradingRepository.insertEntitlement(tenant2,
                new Entitlement(null, customerId, itemId, project.id(), null, 2, 2, 0,
                        LocalDate.of(2026, 11, 22), LocalDate.of(2027, 12, 31), "AVAILABLE", null, null));
        List<Entitlement> entitlements = tradingRepository.findEntitlementsByOrderItemIds(tenant2, List.of(itemId));
        assertThat(entitlements).hasSize(1);

        String ledgerKey = "c02.grant:" + suffix;
        tradingRepository.insertLedger(tenant2,
                new EntitlementLedgerEntry(null, entitlements.get(0).id(), "GRANT", 2, "PAYMENT", "1",
                        ledgerKey, 1L, "C02", null, null));
        assertThat(tradingRepository.findLedgerByKey(tenant2, ledgerKey)).isPresent();
        assertThat(tradingRepository.findLedgerByKey(1L, ledgerKey)).isEmpty();

        String eventId = "c02.event:" + suffix;
        tradingRepository.insertOutboxEvent(tenant2,
                new OutboxEvent(null, tenant2, eventId, "order.paid.v1", 1, "order", order.id().toString(),
                        order.id().toString(), "{}", "NEW", 0, null, null, null, null, null, Instant.now(), 0L));
        assertThat(tradingRepository.findOutboxByEventId(tenant2, eventId)).isPresent();
        assertThat(tradingRepository.findOutboxByEventId(1L, eventId)).isEmpty();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item_selection WHERE tenant_id=1 AND order_item_id=?",
                Integer.class, itemId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order_item_selection WHERE tenant_id=2 AND order_item_id=?",
                Integer.class, itemId)).isEqualTo(1);
    }
}
