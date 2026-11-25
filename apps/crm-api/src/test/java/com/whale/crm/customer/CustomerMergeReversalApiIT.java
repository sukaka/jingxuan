package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.api.dto.MergeCandidateItemResponse;
import com.whale.crm.customer.api.dto.MergeReversalActionRequest;
import com.whale.crm.customer.api.dto.MergeReversalResponse;
import com.whale.crm.customer.application.MergeApplicationService;
import com.whale.crm.customer.application.MergeReversalApplicationService;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobActionRequest;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobDetailResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagSourceRequest;
import com.whale.crm.customer.batchtag.api.dto.CreateBatchTagJobRequest;
import com.whale.crm.customer.batchtag.api.dto.PreviewBatchTagJobRequest;
import com.whale.crm.customer.batchtag.application.BatchTagJobApplicationService;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.MergeCaseRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.api.dto.OrderItemRequest;
import com.whale.crm.trading.api.dto.OrderReviewRequest;
import com.whale.crm.trading.api.dto.PaymentConfirmRequest;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.PaymentApplicationService;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerMergeReversalApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MergeApplicationService mergeService;
    @Autowired
    private MergeReversalApplicationService mergeReversalService;
    @Autowired
    private BatchTagJobApplicationService batchTagJobService;
    @Autowired
    private OrderApplicationService orderService;
    @Autowired
    private PaymentApplicationService paymentService;
    @Autowired
    private CurrentUserService currentUserService;
    @MockitoSpyBean
    private TradingRepository tradingRepository;
    @MockitoSpyBean
    private CustomerRepository customerRepository;
    @MockitoSpyBean
    private MergeCaseRepository mergeCaseRepository;

    @Test
    void mergeRejectsExistingAppointmentWithoutChangingCustomers() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成预约主");
        long secondary = createCustomer(admin, "合成预约从");
        long order = orderWithEntitlement(secondary, "appointment-block");
        appointment(secondary, entitlementOf(order));
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict());
        assertThat(ownerOf("crm_order", order)).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(order))).isEqualTo(secondary);
        assertThat(mergeStatus(caseId)).isEqualTo("REVIEWING");
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(count("crm_merge_resource_snapshot", caseId)).isZero();
    }

    @Test
    void mergeSeesAppointmentCommittedWhileWaitingForEntitlementLock() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成并发主");
        long secondary = createCustomer(admin, "合成并发从");
        long order = orderWithEntitlement(secondary, "appointment-race");
        long entitlement = entitlementOf(order);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (invocation.getArgument(1).equals(secondary)) started.countDown();
            return invocation.callRealMethod();
        }).when(tradingRepository).findEntitlementIdsByCustomer(1L, secondary);
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult>> future =
                    new java.util.concurrent.atomic.AtomicReference<>();
            new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM crm_entitlement WHERE tenant_id=1 AND id=? FOR UPDATE", Long.class, entitlement);
                future.set(executor.submit(() -> mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"测试\"}")).andReturn()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                try {
                    while (!started.await(50, TimeUnit.MILLISECONDS)) {
                        if (future.get().isDone()) {
                            try {
                                throw new IllegalStateException("合并提前完成，HTTP "
                                        + future.get().get().getResponse().getStatus() + "："
                                        + future.get().get().getResponse().getContentAsString());
                            } catch (InterruptedException | java.util.concurrent.ExecutionException | java.io.UnsupportedEncodingException e) {
                                throw new IllegalStateException("合并线程异常", e);
                            }
                        }
                        if (System.nanoTime() >= deadline) {
                            throw new IllegalStateException("合并未在20秒内到达权益快照锁");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待合并锁被中断", e);
                }
                appointment(secondary, entitlement);
                return null;
            });
            assertThat(future.get().get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(ownerOf("crm_entitlement", entitlement)).isEqualTo(secondary);
        assertThat(customerDeleted(secondary)).isZero();
    }

    @Test
    void hBiz04_deterministicMergeDoesNotDeadlockOnOrderStatusSecondaryIndex() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");
        AuthCookies operator1 = login("operator1");
        long primary = createCustomer(admin, "合成索引锁主1");
        long secondary = createCustomer(admin, "合成索引锁从1");
        long orderId = createPaidOrderForMergeRace(admin, secondary, 301L, 181100L);
        long entitlementId = entitlementOf(orderId);
        long apptId = createServiceDoneAppointment(manager1, operator1, secondary, entitlementId, 3);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch beforeOrderStatusUpdate = new CountDownLatch(1);
        CountDownLatch mergeReady = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                beforeOrderStatusUpdate.countDown();
                mergeReady.await(5, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(tradingRepository).updateOrderStatus(eq(1L), eq(orderId), anyLong(),
                eq("PAID"), eq("FULFILLED"), isNull(), isNull());
        doAnswer(invocation -> {
            if (active.get() && beforeOrderStatusUpdate.getCount() == 0) {
                mergeReady.countDown();
            }
            return invocation.callRealMethod();
        }).when(tradingRepository).findOrdersForUpdate(eq(1L),
                argThat(ids -> ids != null && ids.contains(orderId)));

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<org.springframework.test.web.servlet.MvcResult> redeemFuture = executor.submit(() -> {
                String body = objectMapper.writeValueAsString(Map.of(
                        "appointmentVersion", 3L,
                        "serviceAt", Instant.now().toString(),
                        "evidenceRef", "HBIZ04-DET-ORD",
                        "lines", List.of(Map.of("entitlementId", entitlementId, "count", 3))));
                return mockMvc.perform(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/redemptions"))
                                .header("Idempotency-Key", "it-det-ord-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
            });
            assertThat(beforeOrderStatusUpdate.await(15, TimeUnit.SECONDS)).isTrue();
            Future<org.springframework.test.web.servlet.MvcResult> mergeFuture = executor.submit(() ->
                    mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"version\":1,\"reason\":\"并发锁序校验\"}"))
                            .andReturn());

            assertThat(redeemFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(mergeFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        } finally {
            active.set(false);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, orderId))
                .isEqualTo("FULFILLED");
        assertThat(ownerOf("crm_order", orderId)).isEqualTo(secondary);
        assertThat(customerDeleted(secondary)).isZero();
    }

    @Test
    void hBiz04_deterministicMergeDoesNotDeadlockOnEntitlementStatusSecondaryIndex() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");
        AuthCookies operator1 = login("operator1");
        long primary = createCustomer(admin, "合成索引锁主2");
        long secondary = createCustomer(admin, "合成索引锁从2");
        long orderId = createPaidOrderForMergeRace(admin, secondary, 301L, 181100L);
        long entitlementId = entitlementOf(orderId);
        long apptId = createServiceDoneAppointment(manager1, operator1, secondary, entitlementId, 3);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch beforeEntitlementUpdate = new CountDownLatch(1);
        CountDownLatch mergeReady = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                beforeEntitlementUpdate.countDown();
                mergeReady.await(5, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(tradingRepository).updateEntitlementBalance(eq(1L), eq(entitlementId), anyLong(),
                eq(0), eq("EXHAUSTED"), anyInt());
        doAnswer(invocation -> {
            if (active.get() && beforeEntitlementUpdate.getCount() == 0) {
                mergeReady.countDown();
            }
            return invocation.callRealMethod();
        }).when(tradingRepository).findOrdersForUpdate(eq(1L),
                argThat(ids -> ids != null && ids.contains(orderId)));

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<org.springframework.test.web.servlet.MvcResult> redeemFuture = executor.submit(() -> {
                String body = objectMapper.writeValueAsString(Map.of(
                        "appointmentVersion", 3L,
                        "serviceAt", Instant.now().toString(),
                        "evidenceRef", "HBIZ04-DET-ENT",
                        "lines", List.of(Map.of("entitlementId", entitlementId, "count", 3))));
                return mockMvc.perform(operator1.withAuth(post("/api/v1/appointments/" + apptId + "/redemptions"))
                                .header("Idempotency-Key", "it-det-ent-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
            });
            assertThat(beforeEntitlementUpdate.await(15, TimeUnit.SECONDS)).isTrue();
            Future<org.springframework.test.web.servlet.MvcResult> mergeFuture = executor.submit(() ->
                    mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"version\":1,\"reason\":\"并发锁序校验\"}"))
                            .andReturn());

            assertThat(redeemFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(mergeFuture.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        } finally {
            active.set(false);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_entitlement WHERE id=?", String.class, entitlementId))
                .isEqualTo("EXHAUSTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, orderId))
                .isEqualTo("FULFILLED");
        assertThat(ownerOf("crm_entitlement", entitlementId)).isEqualTo(secondary);
        assertThat(customerDeleted(secondary)).isZero();
    }

    @Test
    void mergeConfirmSeesOrderCommittedBeforeSecondaryLockUnderRepeatableRead() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        long primary = createCustomer(admin, "合成快照主");
        long secondary = createCustomer(admin, "合成快照从");
        assertThat(primary).isLessThan(secondary);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch waitingOnPrimaryLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                waitingOnPrimaryLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(customerRepository).findByIdForUpdate(eq(1L), eq(primary));

        long newOrderId;
        Future<MergeCandidateItemResponse> mergeFuture;
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.atomic.AtomicReference<Future<MergeCandidateItemResponse>> mergeRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            newOrderId = new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=1 AND id=? FOR UPDATE",
                        Long.class, primary);
                mergeRef.set(executor.submit(() ->
                        mergeService.confirm(1L, adminUser, caseId, 1L, "快照可见性测试")));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                try {
                    while (!waitingOnPrimaryLock.await(50, TimeUnit.MILLISECONDS)) {
                        if (mergeRef.get().isDone()) {
                            throw new IllegalStateException("合并提前结束: " + mergeRef.get().get());
                        }
                        if (System.nanoTime() >= deadline) {
                            throw new IllegalStateException("合并未在20秒内到达主客户行锁");
                        }
                    }
                    Future<OrderFulfillmentView> orderFuture = executor.submit(() ->
                            orderService.create(1L, adminUser, new CreateOrderRequest(
                                    secondary, "PLATFORM", "REGULAR", 1L, 1L, 181100L,
                                    LocalDate.of(2027, 12, 31), List.of(new OrderItemRequest(301L, 1)))));
                    return orderFuture.get(20, TimeUnit.SECONDS).order().order().id();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待合并主客户锁被中断", e);
                } catch (Exception e) {
                    throw new IllegalStateException("等待并发下单提交失败", e);
                }
            });
            mergeFuture = mergeRef.get();
            assertThat(mergeFuture.get(20, TimeUnit.SECONDS).status()).isEqualTo("CONFIRMED");
        } finally {
            active.set(false);
        }

        assertThat(ownerOf("crm_order", newOrderId)).isEqualTo(primary);
        assertThat(snapshotCount(caseId, "ORDER", newOrderId)).isEqualTo(1);
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1 AND customer_id=?",
                Integer.class, secondary)).isZero();
    }

    @Test
    void hBiz04_deterministicOrderCreateVsMergeConfirmSerializesWithoutDeadlock() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        long primary = createCustomer(admin, "合成下单锁主");
        long secondary = createCustomer(admin, "合成下单锁从");
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch orderReadyToInsert = new CountDownLatch(1);
        CountDownLatch mergeAtSecondaryLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                orderReadyToInsert.countDown();
                if (!mergeAtSecondaryLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("merge did not reach secondary lock");
                }
            }
            return invocation.callRealMethod();
        }).when(tradingRepository).insertOrder(eq(1L),
                argThat(o -> o != null && Long.valueOf(secondary).equals(o.customerId())));
        doAnswer(invocation -> {
            if (active.get() && orderReadyToInsert.getCount() == 0) {
                mergeAtSecondaryLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(customerRepository).findByIdForUpdate(eq(1L), eq(secondary));

        Outcome<OrderFulfillmentView> orderOutcome;
        Outcome<MergeCandidateItemResponse> mergeOutcome;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<OrderFulfillmentView>> orderFuture = executor.submit(() -> runSafe(() ->
                    orderService.create(1L, adminUser, new CreateOrderRequest(
                            secondary, "PLATFORM", "REGULAR", 1L, 1L, 181100L,
                            LocalDate.of(2027, 12, 31), List.of(new OrderItemRequest(301L, 1))))));
            assertThat(orderReadyToInsert.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Outcome<MergeCandidateItemResponse>> mergeFuture = executor.submit(() -> runSafe(() ->
                    mergeService.confirm(1L, adminUser, caseId, 1L, "确定性下单合并锁序")));
            orderOutcome = orderFuture.get(20, TimeUnit.SECONDS);
            mergeOutcome = mergeFuture.get(20, TimeUnit.SECONDS);
        } finally {
            active.set(false);
        }

        assertThat(mergeOutcome.error()).isNull();
        assertThat(mergeOutcome.value().status()).isEqualTo("CONFIRMED");
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1 AND customer_id=?",
                Integer.class, secondary)).isZero();
        if (orderOutcome.error() == null) {
            long orderId = orderOutcome.value().order().order().id();
            assertThat(ownerOf("crm_order", orderId)).isEqualTo(primary);
            assertThat(snapshotCount(caseId, "ORDER", orderId)).isEqualTo(1);
        } else {
            assertThat(orderOutcome.error())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                            .isIn(ErrorCode.NOT_FOUND, ErrorCode.CONFLICT));
        }
    }

    @Test
    void hBiz04_deterministicPaymentConfirmVsMergeConfirmSerializesWithoutDeadlock() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        long primary = createCustomer(admin, "合成支付锁主");
        long secondary = createCustomer(admin, "合成支付锁从");
        long orderId = orderService.create(1L, adminUser, new CreateOrderRequest(
                secondary, "PLATFORM", "REGULAR", 1L, 1L, 181100L,
                LocalDate.of(2027, 12, 31), List.of(new OrderItemRequest(301L, 1)))).order().order().id();
        orderService.submit(1L, adminUser, orderId, new OrderReviewRequest(0L, "待支付"));
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch paymentAtInsert = new CountDownLatch(1);
        CountDownLatch mergeAtSecondaryLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                paymentAtInsert.countDown();
                if (!mergeAtSecondaryLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("merge did not reach secondary lock");
                }
            }
            return invocation.callRealMethod();
        }).when(tradingRepository).insertPayment(eq(1L),
                argThat(p -> p != null && Long.valueOf(orderId).equals(p.orderId())));
        doAnswer(invocation -> {
            if (active.get() && paymentAtInsert.getCount() == 0) {
                mergeAtSecondaryLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(customerRepository).findByIdForUpdate(eq(1L), eq(secondary));

        String tradeNo = "IT-DET-PM-" + UUID.randomUUID();
        PaymentConfirmRequest payRequest = new PaymentConfirmRequest(tradeNo, "BANK_TRANSFER", 181100L,
                Instant.parse("2026-10-24T03:00:00Z"), "IT-DET-PAY-MERGE", null);
        Outcome<OrderFulfillmentView> payOutcome;
        Outcome<MergeCandidateItemResponse> mergeOutcome;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<OrderFulfillmentView>> payFuture = executor.submit(() -> runSafe(() ->
                    paymentService.confirm(1L, adminUser, orderId, payRequest, tradeNo)));
            assertThat(paymentAtInsert.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Outcome<MergeCandidateItemResponse>> mergeFuture = executor.submit(() -> runSafe(() ->
                    mergeService.confirm(1L, adminUser, caseId, 1L, "确定性支付合并锁序")));
            payOutcome = payFuture.get(20, TimeUnit.SECONDS);
            mergeOutcome = mergeFuture.get(20, TimeUnit.SECONDS);
        } finally {
            active.set(false);
        }

        assertThat(mergeOutcome.error()).isNull();
        assertThat(mergeOutcome.value().status()).isEqualTo("CONFIRMED");
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(ownerOf("crm_order", orderId)).isEqualTo(primary);
        assertThat(snapshotCount(caseId, "ORDER", orderId)).isEqualTo(1);
        if (payOutcome.error() == null) {
            assertThat(payOutcome.value().order().order().status()).isEqualTo("PAID");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, orderId))
                    .isEqualTo("PAID");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT customer_id FROM crm_entitlement WHERE order_item_id IN (SELECT id FROM crm_order_item WHERE order_id=?)",
                    Long.class, orderId)).isEqualTo(primary);
            java.sql.Timestamp primaryFirstOrderAt = jdbcTemplate.queryForObject(
                    "SELECT first_order_at FROM crm_customer WHERE id=?", java.sql.Timestamp.class, primary);
            java.sql.Timestamp secondaryFirstOrderAt = jdbcTemplate.queryForObject(
                    "SELECT first_order_at FROM crm_customer WHERE id=?", java.sql.Timestamp.class, secondary);
            assertThat(primaryFirstOrderAt != null || secondaryFirstOrderAt != null).isTrue();
        } else {
            assertThat(payOutcome.error())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                            .isIn(ErrorCode.NOT_FOUND, ErrorCode.CONFLICT));
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE id=?", String.class, orderId))
                    .isEqualTo("PENDING_PAYMENT");
        }
    }

    private <T> Outcome<T> runSafe(Callable<T> action) {
        try {
            return new Outcome<>(action.call(), null);
        } catch (Throwable ex) {
            return new Outcome<>(null, ex);
        }
    }

    private record Outcome<T>(T value, Throwable error) {
    }

    private long createPaidOrderForMergeRace(AuthCookies actor, long customerId, long skuId, long amountFen) throws Exception {
        String orderBody = objectMapper.writeValueAsString(Map.of(
                "customerId", customerId,
                "receiverType", "PLATFORM",
                "orderTypeCode", "REGULAR",
                "campaignId", 1,
                "campaignBatchId", 1,
                "payableFen", amountFen,
                "validTo", "2027-12-31",
                "items", List.of(Map.of("skuId", skuId, "quantity", 1))));
        long orderId = data(actor, post("/api/v1/orders"), orderBody, 201).at("/order/order/id").asLong();
        data(actor, post("/api/v1/orders/" + orderId + "/submit"), "{\"version\":0}", 200);
        String tradeNo = "IT-MERGE-PAY-" + UUID.randomUUID();
        String payBody = objectMapper.writeValueAsString(Map.of(
                "externalTradeNo", tradeNo,
                "paymentChannel", "BANK_TRANSFER",
                "amountFen", amountFen,
                "paidAt", "2026-10-24T03:00:00Z",
                "evidenceRef", "IT-MERGE-PAID"));
        data(actor, post("/api/v1/orders/" + orderId + "/payment-confirmations")
                .header("Idempotency-Key", tradeNo), payBody, 200);
        return orderId;
    }

    private long createServiceDoneAppointment(AuthCookies manager, AuthCookies operator,
                                              long customerId, long entitlementId, int count) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "customerId", customerId,
                "institutionId", 1,
                "scheduledAt", Instant.now().plusSeconds(86400).toString(),
                "remark", "IT-MERGE-RACE",
                "items", List.of(Map.of("entitlementId", entitlementId, "count", count))));
        long apptId = data(manager, post("/api/v1/appointments")
                .header("Idempotency-Key", "it-det-appt-" + UUID.randomUUID()), body, 201)
                .at("/appointment/id").asLong();
        data(operator, post("/api/v1/appointments/" + apptId + "/confirm")
                .header("Idempotency-Key", "it-det-conf-" + UUID.randomUUID()), "{\"version\":0,\"reason\":\"IT\"}", 200);
        data(operator, post("/api/v1/appointments/" + apptId + "/check-in")
                .header("Idempotency-Key", "it-det-chk-" + UUID.randomUUID()), "{\"version\":1,\"reason\":\"IT\"}", 200);
        data(operator, post("/api/v1/appointments/" + apptId + "/service-done")
                .header("Idempotency-Key", "it-det-done-" + UUID.randomUUID()), "{\"version\":2,\"reason\":\"IT\"}", 200);
        return apptId;
    }

    @Test
    void mergeRejectsExistingRefundWithoutChangingCustomers() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成退款主");
        long secondary = createCustomer(admin, "合成退款从");
        long order = orderWithEntitlement(secondary, "refund-block");
        refund(order, secondary);
        long caseId = createCase(admin, primary, secondary);
        data(admin, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(ownerOf("crm_order", order)).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(order))).isEqualTo(secondary);
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(mergeStatus(caseId)).isEqualTo("REVIEWING");
        assertThat(count("crm_merge_resource_snapshot", caseId)).isZero();
    }

    @Test
    void reversalRejectsRefundCreatedAfterMerge() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成撤销退款主");
        long secondary = createCustomer(admin, "合成撤销退款从");
        long order = orderWithEntitlement(secondary, "reversal-refund-block");
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        refund(order, primary);
        long reversalId = requestReversal(admin, caseId, "it-refund-reversal-request", "申请撤销");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-refund-reversal-approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(ownerOf("crm_order", order)).isEqualTo(primary);
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(reversalStatus(reversalId)).isEqualTo("REQUESTED");
    }

    @Test
    void reversalRejectsAppointmentCreatedAfterMerge() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成撤销预约主");
        long secondary = createCustomer(admin, "合成撤销预约从");
        long order = orderWithEntitlement(secondary, "reversal-appointment-block");
        long entitlement = entitlementOf(order);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        int snapshots = count("crm_merge_resource_snapshot", caseId);
        appointment(primary, entitlement);
        long reversalId = requestReversal(admin, caseId, "it-appointment-reversal-request", "申请撤销");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-appointment-reversal-approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(ownerOf("crm_order", order)).isEqualTo(primary);
        assertThat(ownerOf("crm_entitlement", entitlement)).isEqualTo(primary);
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(reversalStatus(reversalId)).isEqualTo("REQUESTED");
        assertThat(count("crm_merge_resource_snapshot", caseId)).isEqualTo(snapshots);
    }

    @Test
    void mergeAndReversalPreserveBothOrderLinkedAndCustomerOnlyRiskCasesSelectively() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long primary = createCustomer(admin, "合成风控合并主-" + suffix);
        long secondary = createCustomer(admin, "合成风控合并从-" + suffix);
        long primaryOrder = orderWithEntitlement(primary, "rsk-pri-" + suffix);
        long secondaryOrder = orderWithEntitlement(secondary, "rsk-sec-" + suffix);

        // Give secondary a followup with nextFollowupAt to verify M3 invariants stay intact alongside R2c
        mockMvc.perform(admin.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "it-rsk-fu-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + secondary + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"风控合并跟进\",\"nextFollowupAt\":\"2026-12-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        long primaryOrderRisk = insertRiskCase("RC-PO-" + suffix, "ORDER_PAYMENT_CASH", "ORDER",
                Long.toString(primaryOrder), primary, primaryOrder);
        long primaryYzRisk = insertRiskCase("RC-PYZ-" + suffix, "CUSTOMER_UNRESOLVED", "YOUZAN_SYNC_ISSUE",
                "910001-" + suffix, primary, null);
        long secondaryOrderRisk = insertRiskCase("RC-SO-" + suffix, "ORDER_PAYMENT_CASH", "ORDER",
                Long.toString(secondaryOrder), secondary, secondaryOrder);
        long secondaryYzRisk = insertRiskCase("RC-SYZ-" + suffix, "CUSTOMER_UNRESOLVED", "YOUZAN_SYNC_ISSUE",
                "910002-" + suffix, secondary, null);

        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);

        // Verify RISK_CASE snapshots & reassignment without bumping crm_risk_case.version or primary.version
        assertThat(snapshotCount(caseId, "RISK_CASE", secondaryOrderRisk)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "RISK_CASE", secondaryYzRisk)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "RISK_CASE", primaryOrderRisk)).isZero();
        assertThat(snapshotCount(caseId, "RISK_CASE", primaryYzRisk)).isZero();
        assertThat(ownerOf("crm_risk_case", secondaryOrderRisk)).isEqualTo(primary);
        assertThat(ownerOf("crm_risk_case", secondaryYzRisk)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_risk_case WHERE id=?",
                Long.class, secondaryOrderRisk)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_risk_case WHERE id=?",
                Long.class, secondaryYzRisk)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_customer WHERE id=?",
                Long.class, primary)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT next_followup_at FROM crm_customer WHERE id=?",
                java.sql.Timestamp.class, primary)).isNotNull();

        // Verify GET /api/v1/risks reflects primary customer after merge
        JsonNode afterMergeView = objectMapper.readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/risks/" + secondaryYzRisk).cookie(admin.cookies()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/riskCase");
        assertThat(afterMergeView.at("/customerId").asLong()).isEqualTo(primary);

        // Post-merge risk cases: one on secondaryOrder (temporarily owned by primary), one primary-only Youzan issue
        long postMergeSecondaryOrderRisk = insertRiskCase("RC-PMSO-" + suffix, "ORDER_REFUND_CASH", "ORDER",
                Long.toString(secondaryOrder), primary, secondaryOrder);
        long postMergePrimaryYzRisk = insertRiskCase("RC-PMPYZ-" + suffix, "PAYMENT_MISMATCH", "YOUZAN_SYNC_ISSUE",
                "910003-" + suffix, primary, null);

        long reversalId = requestReversal(admin, caseId, "it-rsk-rev-req-" + suffix, "误合并含风控单");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-rsk-rev-app-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"核实恢复风控单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));

        // Secondary's pre-merge & post-merge-on-secondary-order cases move back; primary's stay on primary
        assertThat(ownerOf("crm_risk_case", secondaryOrderRisk)).isEqualTo(secondary);
        assertThat(ownerOf("crm_risk_case", secondaryYzRisk)).isEqualTo(secondary);
        assertThat(ownerOf("crm_risk_case", postMergeSecondaryOrderRisk)).isEqualTo(secondary);
        assertThat(ownerOf("crm_risk_case", primaryOrderRisk)).isEqualTo(primary);
        assertThat(ownerOf("crm_risk_case", primaryYzRisk)).isEqualTo(primary);
        assertThat(ownerOf("crm_risk_case", postMergePrimaryYzRisk)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_risk_case WHERE id=?",
                Long.class, secondaryOrderRisk)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_risk_case WHERE id=?",
                Long.class, secondaryYzRisk)).isZero();
    }

    private long insertRiskCase(String caseNo, String riskType, String sourceType, String sourceRef,
                                Long customerId, Long orderId) {
        jdbcTemplate.update("INSERT INTO crm_risk_case (tenant_id,risk_case_no,risk_type,severity,source_type,source_ref,"
                        + "customer_id,order_id,status,evidence) VALUES (1,?,?,'HIGH',?,?,?,?, 'OPEN', '{}')",
                caseNo, riskType, sourceType, sourceRef, customerId, orderId);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_risk_case WHERE tenant_id=1 AND risk_case_no=?",
                Long.class, caseNo);
    }

    private void refund(long order, long customer) {
        jdbcTemplate.update("INSERT INTO crm_refund (tenant_id,refund_no,order_id,customer_id,requested_fen,reason_code,status,requested_by) VALUES (1,?,?,?,100,'TEST','REQUESTED',1)",
                "IT-REFUND-" + java.util.UUID.randomUUID(), order, customer);
    }

    private void appointment(long customer, long entitlement) {
        String no = "IT-APPT-" + java.util.UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,scheduled_at,status,created_by) VALUES (1,?,?,1,2,NOW(),'PENDING_INSTITUTION',1)", no, customer);
        long appointment = jdbcTemplate.queryForObject("SELECT id FROM crm_appointment WHERE appointment_no=?", Long.class, no);
        jdbcTemplate.update("INSERT INTO crm_appointment_item (tenant_id,appointment_id,entitlement_id,project_id,planned_count) SELECT 1,?,id,project_id,1 FROM crm_entitlement WHERE id=?", appointment, entitlement);
    }

    @Test
    void snapshotReversalOnlyRestoresMergedResourcesAndPreservesNewFacts() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成更正主");
        long secondary = createCustomer(admin, "合成更正从");
        long primaryIdentity = identity(primary, "primary-original-reversal");
        long secondaryIdentity = identity(secondary, "unique-reversal");
        tags(admin, primary, "VIP");
        tags(admin, secondary, "VIP", "RISK");
        adjustPoints(admin, primary, 5);
        adjustPoints(admin, secondary, 7);
        long secondaryPoints = pointsId(secondary);
        long followup = createFollowup(admin, secondary);
        long task = createTask(admin, secondary);
        long primaryOrder = orderWithEntitlement(primary, "primary-before");
        long secondaryOrder = orderWithEntitlement(secondary, "secondary-before");
        long automaticFollowupTask = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_task WHERE tenant_id=1 AND source_type='FOLLOWUP' AND source_ref=?",
                Long.class, "FOLLOWUP:" + followup);
        long secondaryOrderItem = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_order_item WHERE order_id=?", Long.class, secondaryOrder);
        jdbcTemplate.update("INSERT INTO crm_order_item_selection "
                        + "(tenant_id,order_item_id,package_version_id,group_code,group_name,min_selections,max_selections,"
                        + "project_id,project_code_snapshot,project_name_snapshot,grant_count,reference_price_fen,sort_order) "
                        + "VALUES (1,?,301,'A','A组',1,1,101,'PRJ-WATER','基础水光',2,80000,1)",
                secondaryOrderItem);

        long caseId = createCase(admin, primary, secondary);
        reversalRequest(admin, caseId, "not-confirmed", "尚未合并").andExpect(status().isConflict());
        confirmCase(admin, caseId);

        long postMergeIdentity = identity(primary, "post-merge-reversal");
        long postMergeFollowup = createFollowup(admin, primary);
        jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) VALUES (1,?,'POST_MERGE_TEST',1)", primary);
        long postMergeOrder = orderWithEntitlement(primary, "primary-after");
        refund(primaryOrder, primary);
        assertThat(snapshotCount(caseId, "IDENTITY", primaryIdentity)).isZero();
        assertThat(snapshotCount(caseId, "IDENTITY", secondaryIdentity)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "ORDER", primaryOrder)).isZero();
        assertThat(snapshotCount(caseId, "ORDER", secondaryOrder)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "POINTS", pointsId(primary))).isZero();
        assertThat(snapshotCount(caseId, "POINTS", secondaryPoints)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "TASK", task)).isEqualTo(1);
        assertThat(snapshotCount(caseId, "TASK", automaticFollowupTask)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT order_item_id FROM crm_order_item_selection WHERE order_item_id=?",
                Long.class, secondaryOrderItem)).isEqualTo(secondaryOrderItem);
        assertThat(count("crm_merge_resource_snapshot", caseId)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type='TAG' AND resource_key='VIP'", Integer.class, caseId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type='TAG' AND resource_key='RISK'", Integer.class, caseId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/customer-merge-candidates/" + caseId + "/reversal-requests")
                        .cookie(admin.cookies()).header("Idempotency-Key", "it-reversal-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"缺少CSRF\"}"))
                .andExpect(status().isForbidden());
        long reversalId = requestReversal(admin, caseId, "it-reversal-request-1", "误合并，申请恢复");
        JsonNode replay = data(admin, post("/api/v1/customer-merge-candidates/" + caseId + "/reversal-requests")
                .header("Idempotency-Key", "it-reversal-request-1"), "{\"reason\":\"误合并，申请恢复\"}", 200);
        assertThat(replay.at("/id").asLong()).isEqualTo(reversalId);
        reversalRequest(admin, caseId, "it-reversal-request-duplicate", "重复申请").andExpect(status().isConflict());
        mockMvc.perform(login("lead1").withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-reversal-no-permission")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"无权限审批\"}"))
                .andExpect(status().isForbidden());
        assertThat(customerDeleted(secondary)).isEqualTo(1);

        String approveBody = "{\"version\":0,\"reason\":\"核实误合并，同意按快照恢复\"}";
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-reversal-approve-1")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.warningMessage").value(org.hamcrest.Matchers.containsString("去重删除")));
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/approve")
                .header("Idempotency-Key", "it-reversal-approve-1"), approveBody, 200);

        assertThat(customerDeleted(secondary)).isZero();
        assertThat(ownerOf("crm_customer_identity", secondaryIdentity)).isEqualTo(secondary);
        assertThat(ownerOf("crm_customer_identity", postMergeIdentity)).isEqualTo(primary);
        assertThat(countByExternalId("primary-original-reversal")).isEqualTo(1);
        assertThat(ownerOf("crm_customer_identity", primaryIdentity)).isEqualTo(primary);
        assertThat(tagCount(primary, "VIP")).isEqualTo(1);
        assertThat(tagCount(primary, "POST_MERGE_TEST")).isEqualTo(1);
        assertThat(tagCount(secondary, "RISK")).isEqualTo(1);
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(secondary);
        assertThat(ownerOf("crm_followup", postMergeFollowup)).isEqualTo(primary);
        assertThat(ownerOf("crm_task", task)).isEqualTo(secondary);
        assertThat(ownerOf("crm_task", automaticFollowupTask)).isEqualTo(secondary);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order_item_selection WHERE order_item_id=?",
                Integer.class, secondaryOrderItem)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT group_code,reference_price_fen FROM crm_order_item_selection WHERE order_item_id=?",
                secondaryOrderItem)).containsEntry("group_code", "A").containsEntry("reference_price_fen", 80000L);
        assertThat(pointsBalance(primary)).isEqualTo(5);
        assertThat(pointsBalance(secondary)).isEqualTo(7);
        assertThat(ownerOf("crm_order", primaryOrder)).isEqualTo(primary);
        assertThat(ownerOf("crm_order", secondaryOrder)).isEqualTo(secondary);
        assertThat(ownerOf("crm_order", postMergeOrder)).isEqualTo(primary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(primaryOrder))).isEqualTo(primary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(secondaryOrder))).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementOf(postMergeOrder))).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT paid_fen FROM crm_order WHERE id=?", Long.class, secondaryOrder)).isEqualTo(1200L);
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("SELECT after_json FROM crm_audit_log WHERE action_code='MERGE_REVERSAL_APPROVE' AND target_id=?", String.class, Long.toString(reversalId)))
                .contains("EXECUTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='MERGE_REVERSAL_APPROVE' AND target_id=?", Integer.class, Long.toString(reversalId))).isEqualTo(1);
    }

    @Test
    void legacyReversalRequiresManualCompletionWithoutMovingUnknownResources() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成旧合并主");
        long secondary = createCustomer(admin, "合成旧合并从");
        long followup = createFollowup(admin, secondary);
        adjustPoints(admin, secondary, 7);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        jdbcTemplate.update("DELETE FROM crm_merge_resource_snapshot WHERE tenant_id=1 AND merge_case_id=?", caseId);

        long reversalId = requestReversal(admin, caseId, "it-reversal-legacy-request", "历史合并信息不全");
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/approve")
                .header("Idempotency-Key", "it-reversal-legacy-approve"), "{\"version\":0,\"reason\":\"先恢复客户，人工核对资源\"}", 200);
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(primary);
        assertThat(pointsBalance(primary)).isEqualTo(7);
        assertThat(pointsBalance(secondary)).isEqualTo(7);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(reversalStatus(reversalId)).isEqualTo("MANUAL_REVIEW");

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/complete"))
                        .header("Idempotency-Key", "it-reversal-legacy-stale")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"旧版本\"}"))
                .andExpect(status().isConflict());
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/complete")
                        .header("Idempotency-Key", "it-reversal-legacy-complete"),
                "{\"version\":1,\"reason\":\"已逐项核对身份、标签、跟进、任务、积分、订单和权益\"}", 200);
        assertThat(reversalStatus(reversalId)).isEqualTo("EXECUTED");
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
    }

    @Test
    void rejectAndOutOfScopeReviewDoNotChangeCustomers() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成驳回主", 3);
        long secondary = createCustomer(admin, "合成驳回从", 3);
        long followup = createFollowup(admin, secondary);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-reversal-reject-request", "申请后仍需核实");

        jdbcTemplate.update("INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id) "
                + "SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id "
                + "WHERE r.role_code='MANAGER_LEAD' AND p.permission_code='customer:merge_reverse'");
        try {
            AuthCookies lead = login("lead1");
            mockMvc.perform(lead.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                            .header("Idempotency-Key", "it-reversal-scope-approve")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"跨组审批\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(lead.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/reject"))
                            .header("Idempotency-Key", "it-reversal-scope-reject")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"跨组驳回\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            jdbcTemplate.update("DELETE rp FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id "
                    + "JOIN sys_permission p ON p.id=rp.permission_id "
                    + "WHERE r.role_code='MANAGER_LEAD' AND p.permission_code='customer:merge_reverse'");
        }
        data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/reject")
                .header("Idempotency-Key", "it-reversal-reject"), "{\"version\":0,\"reason\":\"核实后不执行更正\"}", 200);
        assertThat(reversalStatus(reversalId)).isEqualTo("REJECTED");
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(primary);
    }

    @Test
    void approve_outOfScopeCallerWithWrongVersionOrTerminalStatusReturns404() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成越权主", 3);
        long secondary = createCustomer(admin, "合成越权从", 3);
        orderWithEntitlement(secondary, "oos-precedence");
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-oos-req-" + UUID.randomUUID(), "越权错误码优先级");

        jdbcTemplate.update("INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id) "
                + "SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id "
                + "WHERE r.role_code='MANAGER_LEAD' AND p.permission_code='customer:merge_reverse'");
        try {
            AuthCookies lead = login("lead1");
            mockMvc.perform(lead.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                            .header("Idempotency-Key", "it-oos-wrong-ver-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":999,\"reason\":\"越权错版本\"}"))
                    .andExpect(status().isNotFound());

            data(admin, post("/api/v1/customer-merge-reversals/" + reversalId + "/approve")
                    .header("Idempotency-Key", "it-oos-admin-exec-" + UUID.randomUUID()),
                    "{\"version\":0,\"reason\":\"管理员同意更正\"}", 200);
            assertThat(reversalStatus(reversalId)).isEqualTo("EXECUTED");

            mockMvc.perform(lead.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                            .header("Idempotency-Key", "it-oos-terminal-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":999,\"reason\":\"越权终态探测\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            jdbcTemplate.update("DELETE rp FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id "
                    + "JOIN sys_permission p ON p.id=rp.permission_id "
                    + "WHERE r.role_code='MANAGER_LEAD' AND p.permission_code='customer:merge_reverse'");
        }
    }

    @Test
    void hBiz04_deterministicMergeReversalApproveVsMergeConfirmSerializesWithoutDeadlock() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        long primary = createCustomer(admin, "合成更正并发主");
        long secondary = createCustomer(admin, "合成更正并发从");
        long orderId = orderWithEntitlement(secondary, "det-reversal-confirm-race");
        long entitlementId = entitlementOf(orderId);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-det-rev-req-" + UUID.randomUUID(), "确定性更正并发锁序");

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch reversalHoldingCustomerLocks = new CountDownLatch(1);
        CountDownLatch mergeEnteredCaseLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                reversalHoldingCustomerLocks.countDown();
                if (!mergeEnteredCaseLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("merge confirm did not enter case lock");
                }
            }
            return invocation.callRealMethod();
        }).when(customerRepository).restore(eq(1L), eq(secondary), anyLong());
        doAnswer(invocation -> {
            if (active.get() && reversalHoldingCustomerLocks.getCount() == 0) {
                mergeEnteredCaseLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(mergeCaseRepository).findByIdForUpdate(eq(1L), eq(caseId));

        String approveKey = "it-det-rev-app-" + UUID.randomUUID();
        MergeReversalActionRequest approveReq = new MergeReversalActionRequest("确定性更正审批并发", 0L);
        Outcome<MergeReversalResponse> approveOutcome;
        Outcome<MergeCandidateItemResponse> confirmOutcome;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<MergeReversalResponse>> approveFuture = executor.submit(() -> runSafe(() ->
                    mergeReversalService.approve(adminUser, reversalId, approveReq, approveKey)));
            assertThat(reversalHoldingCustomerLocks.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Outcome<MergeCandidateItemResponse>> confirmFuture = executor.submit(() -> runSafe(() ->
                    mergeService.confirm(1L, adminUser, caseId, 2L, "确定性合并确认并发")));
            approveOutcome = approveFuture.get(20, TimeUnit.SECONDS);
            confirmOutcome = confirmFuture.get(20, TimeUnit.SECONDS);
        } finally {
            active.set(false);
        }

        assertThat(approveOutcome.error()).isNull();
        assertThat(approveOutcome.value().status()).isEqualTo("EXECUTED");
        assertThat(confirmOutcome.error())
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(reversalStatus(reversalId)).isEqualTo("EXECUTED");
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(ownerOf("crm_order", orderId)).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementId)).isEqualTo(secondary);
    }

    @Test
    void hBiz04_deterministicMergeConfirmVsMergeReversalApproveSerializesWithoutDeadlock() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        long primary = createCustomer(admin, "合成确认抢锁主");
        long secondary = createCustomer(admin, "合成确认抢锁从");
        long orderId = orderWithEntitlement(secondary, "det-confirm-reversal-race");
        long entitlementId = entitlementOf(orderId);
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-det-conf-req-" + UUID.randomUUID(), "确定性确认抢锁并发");

        long firstLock = Math.min(primary, secondary);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean confirmPaused = new AtomicBoolean(false);
        CountDownLatch confirmHoldingLocks = new CountDownLatch(1);
        CountDownLatch reversalEnteredCaseLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (active.get() && confirmPaused.compareAndSet(false, true)) {
                confirmHoldingLocks.countDown();
                if (!reversalEnteredCaseLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("merge reversal did not enter case lock");
                }
            }
            return result;
        }).when(customerRepository).findByIdForUpdate(eq(1L), eq(firstLock));
        doAnswer(invocation -> {
            if (active.get() && confirmHoldingLocks.getCount() == 0) {
                reversalEnteredCaseLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(mergeCaseRepository).findByIdForUpdate(eq(1L), eq(caseId));

        String approveKey = "it-det-conf-app-" + UUID.randomUUID();
        MergeReversalActionRequest approveReq = new MergeReversalActionRequest("确定性更正后发并发", 0L);
        Outcome<MergeCandidateItemResponse> confirmOutcome;
        Outcome<MergeReversalResponse> approveOutcome;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<MergeCandidateItemResponse>> confirmFuture = executor.submit(() -> runSafe(() ->
                    mergeService.confirm(1L, adminUser, caseId, 2L, "确定性确认抢先锁")));
            assertThat(confirmHoldingLocks.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Outcome<MergeReversalResponse>> approveFuture = executor.submit(() -> runSafe(() ->
                    mergeReversalService.approve(adminUser, reversalId, approveReq, approveKey)));
            confirmOutcome = confirmFuture.get(20, TimeUnit.SECONDS);
            approveOutcome = approveFuture.get(20, TimeUnit.SECONDS);
        } finally {
            active.set(false);
        }

        assertThat(confirmOutcome.error())
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(approveOutcome.error()).isNull();
        assertThat(approveOutcome.value().status()).isEqualTo("EXECUTED");
        assertThat(reversalStatus(reversalId)).isEqualTo("EXECUTED");
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(ownerOf("crm_order", orderId)).isEqualTo(secondary);
        assertThat(ownerOf("crm_entitlement", entitlementId)).isEqualTo(secondary);
    }

    @Test
    void hBiz04_deterministicBatchTagProcessItemVsMergeReversalApproveSerializesWithoutDeadlock() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        String cityCode = "REV_BT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,status) "
                        + "SELECT 1,id,?,'并发城市','ACTIVE' FROM sys_dictionary_type "
                        + "WHERE tenant_id=1 AND dict_code='cities' ON DUPLICATE KEY UPDATE status='ACTIVE'",
                cityCode);
        jdbcTemplate.update("INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,status) "
                + "SELECT 1,id,'VIP','贵宾客户','ACTIVE' FROM sys_dictionary_type "
                + "WHERE tenant_id=1 AND dict_code='customerTags' ON DUPLICATE KEY UPDATE status='ACTIVE'");

        long primary = createCustomer(admin, "合成打标并发主");
        long secondary = data(admin, post("/api/v1/customers"),
                "{\"nickname\":\"合成打标并发从\",\"ownerStaffId\":2,\"cityCode\":\"" + cityCode + "\"}", 201)
                .at("/id").asLong();
        String previewId = batchTagJobService.preview(1L, adminUser,
                new PreviewBatchTagJobRequest("ADD", List.of("VIP"),
                        new BatchTagSourceRequest("FILTER", null,
                                new CustomerSegmentCriteria(List.of(cityCode), null, null, null, null, null), null),
                        1, 20),
                "it-det-bt-prev-" + UUID.randomUUID()).previewId();
        long jobId = batchTagJobService.create(1L, adminUser,
                new CreateBatchTagJobRequest(previewId), "it-det-bt-create-" + UUID.randomUUID()).job().id();

        orderWithEntitlement(secondary, "det-bt-rev-race");
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-det-bt-req-" + UUID.randomUUID(), "确定性打标更正并发");

        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch reversalHoldingCustomerLocks = new CountDownLatch(1);
        CountDownLatch batchTagEnteredCustomerLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (active.get()) {
                reversalHoldingCustomerLocks.countDown();
                if (!batchTagEnteredCustomerLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("batch tag did not enter customer lock");
                }
            }
            return invocation.callRealMethod();
        }).when(customerRepository).restore(eq(1L), eq(secondary), anyLong());
        doAnswer(invocation -> {
            if (active.get() && reversalHoldingCustomerLocks.getCount() == 0) {
                batchTagEnteredCustomerLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(customerRepository).findByIdForUpdate(eq(1L), eq(secondary));

        String approveKey = "it-det-bt-app-" + UUID.randomUUID();
        MergeReversalActionRequest approveReq = new MergeReversalActionRequest("确定性打标与更正并发", 0L);
        Outcome<MergeReversalResponse> approveOutcome;
        Outcome<BatchTagJobDetailResponse> batchTagOutcome;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<MergeReversalResponse>> approveFuture = executor.submit(() -> runSafe(() ->
                    mergeReversalService.approve(adminUser, reversalId, approveReq, approveKey)));
            assertThat(reversalHoldingCustomerLocks.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Outcome<BatchTagJobDetailResponse>> batchTagFuture = executor.submit(() -> runSafe(() ->
                    batchTagJobService.execute(1L, adminUser, jobId, new BatchTagJobActionRequest(0L),
                            "it-det-bt-exec-" + UUID.randomUUID())));
            approveOutcome = approveFuture.get(20, TimeUnit.SECONDS);
            batchTagOutcome = batchTagFuture.get(20, TimeUnit.SECONDS);
        } finally {
            active.set(false);
        }

        assertThat(approveOutcome.error()).isNull();
        assertThat(approveOutcome.value().status()).isEqualTo("EXECUTED");
        assertThat(batchTagOutcome.error()).isNull();
        assertThat(batchTagOutcome.value().job().status()).isEqualTo("SUCCEEDED");
        assertThat(reversalStatus(reversalId)).isEqualTo("EXECUTED");
        assertThat(mergeStatus(caseId)).isEqualTo("REVERSED");
        assertThat(customerDeleted(secondary)).isZero();
        assertThat(tagCount(secondary, "VIP")).isEqualTo(1);
    }

    @Test
    void failedMigrationRollsBackRestoreAndWritesMaskedErrorAudit() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "合成回滚主");
        long secondary = createCustomer(admin, "合成回滚从");
        long followup = createFollowup(admin, secondary);
        tags(admin, primary, "VIP");
        tags(admin, secondary, "VIP", "RISK");
        long caseId = createCase(admin, primary, secondary);
        confirmCase(admin, caseId);
        long reversalId = requestReversal(admin, caseId, "it-reversal-failure-request", "验证失败回滚");
        jdbcTemplate.update("UPDATE crm_merge_resource_snapshot SET resource_key='INVALID' WHERE merge_case_id=? AND resource_type='FOLLOWUP'", caseId);

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalId + "/approve"))
                        .header("Idempotency-Key", "it-reversal-failure-approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"验证异常回滚\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(reversalStatus(reversalId)).isEqualTo("ERROR");
        assertThat(customerDeleted(secondary)).isEqualTo(1);
        assertThat(ownerOf("crm_followup", followup)).isEqualTo(primary);
        assertThat(mergeStatus(caseId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT error_masked FROM crm_merge_reversal WHERE id=?", String.class, reversalId))
                .doesNotContain("SQL", "VIP", "Duplicate");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='MERGE_REVERSAL_ERROR' AND target_id=?", Integer.class, Long.toString(reversalId))).isEqualTo(1);
    }

    @Test
    void reversalApproveRestoresNextFollowupAtAndSucceedsEvenAfterPostRequestFollowup() throws Exception {
        AuthCookies admin = login("admin");
        // Case A: 主客户原有跟进，申请更正后主客户又新增跟进，审批仍应成功（主客户 version 未变）并正确恢复各自 next_followup_at
        long primaryA = createCustomer(admin, "更正跟进主A");
        long secondaryA = createCustomer(admin, "更正跟进从A");
        createFollowupWithNextAt(admin, primaryA, "主A旧跟进", "2026-07-01T00:00:00Z");
        createFollowupWithNextAt(admin, secondaryA, "从A跟进", "2026-11-01T00:00:00Z");
        long caseA = createCase(admin, primaryA, secondaryA);
        confirmCase(admin, caseA);
        assertThat(nextFollowupOf(primaryA)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(nextFollowupOf(secondaryA)).isNull();

        long reversalA = requestReversal(admin, caseA, "it-nf-rev-req-a-" + UUID.randomUUID(), "误合并");
        createFollowupWithNextAt(admin, primaryA, "主A申请后新跟进", "2026-09-01T00:00:00Z");

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalA + "/approve"))
                        .header("Idempotency-Key", "it-nf-rev-app-a-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"同意更正\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));
        assertThat(nextFollowupOf(primaryA)).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(nextFollowupOf(secondaryA)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));

        // Case B: 主客户自身无跟进，更正后主客户 next_followup_at 归零为 NULL
        long primaryB = createCustomer(admin, "更正跟进主B");
        long secondaryB = createCustomer(admin, "更正跟进从B");
        createFollowupWithNextAt(admin, secondaryB, "从B跟进", "2026-11-01T00:00:00Z");
        long caseB = createCase(admin, primaryB, secondaryB);
        confirmCase(admin, caseB);
        assertThat(nextFollowupOf(primaryB)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));

        long reversalB = requestReversal(admin, caseB, "it-nf-rev-req-b-" + UUID.randomUUID(), "误合并B");
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-reversals/" + reversalB + "/approve"))
                        .header("Idempotency-Key", "it-nf-rev-app-b-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"同意更正B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));
        assertThat(nextFollowupOf(primaryB)).isNull();
        assertThat(nextFollowupOf(secondaryB)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    private void createFollowupWithNextAt(AuthCookies actor, long customerId, String summary, String nextFollowupAt)
            throws Exception {
        mockMvc.perform(actor.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "it-reversal-nf-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\",\"summary\":\""
                                + summary + "\",\"nextFollowupAt\":\"" + nextFollowupAt + "\"}"))
                .andExpect(status().isCreated());
    }

    private Instant nextFollowupOf(long customerId) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT next_followup_at FROM crm_customer WHERE id=?", java.sql.Timestamp.class, customerId);
        return ts == null ? null : ts.toInstant();
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        return createCustomer(actor, nickname, 2);
    }

    private long createCustomer(AuthCookies actor, String nickname, long ownerId) throws Exception {
        return data(actor, post("/api/v1/customers"), "{\"nickname\":\"" + nickname + "\",\"ownerStaffId\":" + ownerId + "}", 201).at("/id").asLong();
    }

    private long createCase(AuthCookies actor, long primary, long secondary) throws Exception {
        return data(actor, post("/api/v1/customer-merges"), "{\"primaryCustomerId\":" + primary + ",\"secondaryCustomerId\":" + secondary + ",\"reason\":\"合成测试候选\"}", 201).at("/id").asLong();
    }

    private void confirmCase(AuthCookies actor, long caseId) throws Exception {
        data(actor, post("/api/v1/customer-merges/" + caseId + "/review"), "{\"version\":0}", 200);
        data(actor, post("/api/v1/customer-merges/" + caseId + "/confirm"), "{\"version\":1,\"reason\":\"合成测试确认合并\"}", 200);
    }

    private org.springframework.test.web.servlet.ResultActions reversalRequest(AuthCookies actor, long caseId, String key, String reason) throws Exception {
        return mockMvc.perform(actor.withAuth(post("/api/v1/customer-merge-candidates/" + caseId + "/reversal-requests"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("reason", reason))));
    }

    private long requestReversal(AuthCookies actor, long caseId, String key, String reason) throws Exception {
        return reversalRequest(actor, caseId, key, reason).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString().transform(body -> {
                    try {
                        return objectMapper.readTree(body).at("/data/id").asLong();
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                });
    }

    private JsonNode data(AuthCookies actor, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                          String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(actor.withAuth(builder).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private long identity(long customerId, String externalId) {
        jdbcTemplate.update("INSERT INTO crm_customer_identity (tenant_id,customer_id,identity_type,source_app,external_id,status) VALUES (1,?,'WECOM','it-reversal',?,'LINKED')", customerId, externalId);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_customer_identity WHERE external_id=?", Long.class, externalId);
    }

    private void tags(AuthCookies actor, long customerId, String... codes) throws Exception {
        data(actor, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/customers/" + customerId + "/tags"),
                objectMapper.writeValueAsString(java.util.Map.of("tagCodes", codes)), 200);
    }

    private void adjustPoints(AuthCookies actor, long customerId, int delta) throws Exception {
        mockMvc.perform(actor.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", "it-reversal-points-" + customerId + "-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + ",\"reasonCode\":\"TEST\"}"))
                .andExpect(status().isOk());
    }

    private long createFollowup(AuthCookies actor, long customerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "it-reversal-followup-" + java.util.UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId
                                + ",\"channelCode\":\"WECOM\",\"summary\":\"合成更正测试\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createTask(AuthCookies actor, long customerId) throws Exception {
        return data(actor, post("/api/v1/tasks"), "{\"customerId\":" + customerId + ",\"taskType\":\"FOLLOWUP\",\"title\":\"合成更正测试\"}", 201).at("/id").asLong();
    }

    private long orderWithEntitlement(long customerId, String suffix) {
        String orderNo = "IT-REVERSAL-" + suffix + "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,source_system,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) VALUES (1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',1200,1200,0)", orderNo, customerId);
        long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE order_no=?", Long.class, orderNo);
        jdbcTemplate.update("INSERT INTO crm_order_item (tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,purchase_quantity,paid_allocated_fen) SELECT 1,?,id,package_version_id,'合成订单快照',1,1200 FROM crm_sku WHERE id=301", orderId);
        long itemId = jdbcTemplate.queryForObject("SELECT id FROM crm_order_item WHERE order_id=?", Long.class, orderId);
        jdbcTemplate.update("INSERT INTO crm_entitlement (tenant_id,customer_id,order_item_id,project_id,granted_count,available_count,version_no,status) SELECT 1,?,?,project_id,2,2,1,'AVAILABLE' FROM crm_package_project WHERE package_version_id=(SELECT package_version_id FROM crm_sku WHERE id=301) ORDER BY project_id LIMIT 1", customerId, itemId);
        return orderId;
    }

    private long snapshotCount(long caseId, String type, long resourceId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type=? AND resource_key=?", Long.class, caseId, type, Long.toString(resourceId));
    }

    private int count(String table, long caseId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE merge_case_id=?", Integer.class, caseId);
    }

    private long countByExternalId(String externalId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_identity WHERE external_id=?", Long.class, externalId);
    }

    private long ownerOf(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT customer_id FROM " + table + " WHERE id=?", Long.class, id);
    }

    private long tagCount(long customerId, String code) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=? AND tag_code=?", Long.class, customerId, code);
    }

    private long pointsId(long customerId) {
        return jdbcTemplate.queryForObject("SELECT id FROM crm_points_ledger WHERE customer_id=? ORDER BY id LIMIT 1", Long.class, customerId);
    }

    private long entitlementOf(long orderId) {
        return jdbcTemplate.queryForObject("SELECT e.id FROM crm_entitlement e JOIN crm_order_item i ON i.id=e.order_item_id WHERE i.order_id=?", Long.class, orderId);
    }

    private int customerDeleted(long customerId) {
        return jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?", Integer.class, customerId);
    }

    private int pointsBalance(long customerId) {
        return jdbcTemplate.queryForObject("SELECT points_cached FROM crm_customer WHERE id=?", Integer.class, customerId);
    }

    private String mergeStatus(long caseId) {
        return jdbcTemplate.queryForObject("SELECT status FROM crm_merge_case WHERE id=?", String.class, caseId);
    }

    private String reversalStatus(long reversalId) {
        return jdbcTemplate.queryForObject("SELECT status FROM crm_merge_reversal WHERE id=?", String.class, reversalId);
    }
}
