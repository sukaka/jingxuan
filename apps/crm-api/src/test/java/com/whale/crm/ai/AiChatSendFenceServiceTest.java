package com.whale.crm.ai;

import com.whale.crm.ai.application.AiChatSendFenceService;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.ai.domain.AiChatSendFence;
import com.whale.crm.ai.domain.AiChatSendFenceRepository;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatSendFenceServiceTest {
    @Mock AiChatDelegationRepository delegationRepository;
    @Mock CustomerRepository customerRepository;
    @Mock PlatformTransactionManager transactionManager;

    @Test
    @Timeout(10)
    void stopSuccessWaitsForInflightAndPreventsAnyLaterSenderInvocation() throws Exception {
        Instant now = Instant.parse("2026-01-01T01:00:00Z");
        InMemoryFenceRepository fences = new InMemoryFenceRepository();
        AiChatSendFenceService service = new AiChatSendFenceService(delegationRepository, fences, customerRepository,
                new TestTransactionManager(), Clock.fixed(now, ZoneOffset.UTC), 2000, 5);
        AiChatDelegationRun run = run(now);
        when(delegationRepository.findPolicyForUpdate(1L))
                .thenReturn(Optional.of(AiChatDelegationPolicy.defaults(1L)));
        when(delegationRepository.findRunForUpdate(1L, 11L)).thenReturn(Optional.of(run));
        when(customerRepository.findByIdForUpdate(1L, 101L)).thenReturn(Optional.of(customer()));
        CountDownLatch senderEntered = new CountDownLatch(1);
        CountDownLatch releaseSender = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicInteger senderCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread sender = new Thread(() -> {
            try {
                AiChatSendFenceService.Decision decision = service.authorize(run, "worker-a", (policy, customer) -> null);
                if (decision.accepted()) {
                    senderCalls.incrementAndGet();
                    senderEntered.countDown();
                    if (!releaseSender.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("sender release timeout");
                    }
                    service.finish(decision.authorization());
                }
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        });
        sender.start();
        assertThat(senderEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread stop = new Thread(() -> {
            try {
                service.blockCustomer(1L, 101L);
                service.awaitCustomer(1L, 101L);
                stopReturned.countDown();
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        });
        stop.start();
        assertThat(stopReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        releaseSender.countDown();
        assertThat(stopReturned.await(2, TimeUnit.SECONDS)).isTrue();

        AiChatSendFenceService.Decision afterStop = service.authorize(run, "worker-a", (policy, customer) -> null);
        if (afterStop.accepted()) {
            senderCalls.incrementAndGet();
        }
        sender.join(2000);
        stop.join(2000);
        assertThat(failure.get()).isNull();
        assertThat(afterStop.accepted()).isFalse();
        assertThat(afterStop.deniedReason()).isEqualTo("SEND_AUTHORIZATION_REVOKED");
        assertThat(senderCalls.get()).isEqualTo(1);
    }

    @Test
    void releaseForRetryClearsInFlightAndFinishedAtAllowingRetryWhileFinishLocksOut() {
        Instant now = Instant.parse("2026-01-01T01:00:00Z");
        InMemoryFenceRepository fences = new InMemoryFenceRepository();
        AiChatSendFenceService service = new AiChatSendFenceService(delegationRepository, fences, customerRepository,
                new TestTransactionManager(), Clock.fixed(now, ZoneOffset.UTC), 2000, 5);
        AiChatDelegationRun firstRun = run(now);
        AiChatDelegationRun secondRun = new AiChatDelegationRun(11L, 1L, 91L, 101L, 8L, 3L, "corp", "external",
                "conversation", "SENDING", null, "cipher", "GENERAL", "[]", "mock", "mock-v1", null, null,
                now, 1, 2, null, "worker-a", now.plusSeconds(30), now, now, 3L);
        when(delegationRepository.findPolicyForUpdate(1L))
                .thenReturn(Optional.of(AiChatDelegationPolicy.defaults(1L)));
        when(delegationRepository.findRunForUpdate(1L, 11L)).thenReturn(Optional.of(firstRun), Optional.of(secondRun), Optional.of(secondRun));
        when(customerRepository.findByIdForUpdate(1L, 101L)).thenReturn(Optional.of(customer()));

        AiChatSendFenceService.Decision first = service.authorize(firstRun, "worker-a", (p, c) -> null);
        assertThat(first.accepted()).isTrue();
        service.releaseForRetry(first.authorization());
        assertThat(fences.fence.inFlight()).isFalse();
        assertThat(fences.fence.finishedAt()).isNull();

        AiChatSendFenceService.Decision retry = service.authorize(secondRun, "worker-a", (p, c) -> null);
        assertThat(retry.accepted()).isTrue();
        service.finish(retry.authorization());
        assertThat(fences.fence.inFlight()).isFalse();
        assertThat(fences.fence.finishedAt()).isNotNull();

        AiChatSendFenceService.Decision afterFinish = service.authorize(secondRun, "worker-a", (p, c) -> null);
        assertThat(afterFinish.accepted()).isFalse();
        assertThat(afterFinish.deniedReason()).isEqualTo("SEND_ALREADY_COMPLETED");
    }

    private AiChatDelegationRun run(Instant now) {
        return new AiChatDelegationRun(11L, 1L, 91L, 101L, 8L, 3L, "corp", "external", "conversation",
                "SENDING", null, "cipher", "GENERAL", "[]", "mock", "mock-v1", null, null,
                now, 1, 1, null, "worker-a", now.plusSeconds(30), now, now, 3L);
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(101L);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(8L);
        customer.setTeamId(3L);
        return customer;
    }

    private static final class InMemoryFenceRepository implements AiChatSendFenceRepository {
        private AiChatSendFence fence;

        @Override
        public synchronized Optional<AiChatSendFence> findForUpdate(long tenantId, long runId) {
            return Optional.ofNullable(fence);
        }

        @Override
        public synchronized void insert(AiChatSendFence value) {
            fence = new AiChatSendFence(1L, value.tenantId(), value.runId(), value.customerId(), value.blocked(),
                    value.inFlight(), value.startedAt(), value.finishedAt(), value.version());
        }

        @Override
        public synchronized int markStarted(long tenantId, long runId, long expectedVersion, Instant now) {
            if (fence == null || fence.version() != expectedVersion || fence.blocked() || fence.inFlight()) {
                return 0;
            }
            fence = new AiChatSendFence(fence.id(), tenantId, runId, fence.customerId(), false, true,
                    now, null, fence.version() + 1);
            return 1;
        }

        @Override
        public synchronized int blockTenant(long tenantId, Instant now) {
            return block();
        }

        @Override
        public synchronized int blockCustomer(long tenantId, long customerId, Instant now) {
            return block();
        }

        private int block() {
            if (fence == null || fence.blocked()) {
                return 0;
            }
            fence = new AiChatSendFence(fence.id(), fence.tenantId(), fence.runId(), fence.customerId(), true,
                    fence.inFlight(), fence.startedAt(), fence.finishedAt(), fence.version() + 1);
            return 1;
        }

        @Override
        public synchronized long countInFlightForTenant(long tenantId) {
            return fence != null && fence.inFlight() ? 1 : 0;
        }

        @Override
        public synchronized long countInFlightForCustomer(long tenantId, long customerId) {
            return countInFlightForTenant(tenantId);
        }

        @Override
        public synchronized int finish(long tenantId, long runId, long expectedVersion, Instant now) {
            if (fence == null || !fence.inFlight()) {
                return 0;
            }
            fence = new AiChatSendFence(fence.id(), tenantId, runId, fence.customerId(), fence.blocked(), false,
                    fence.startedAt(), now, fence.version() + 1);
            return 1;
        }

        @Override
        public synchronized int releaseForRetry(long tenantId, long runId, long expectedVersion, Instant now) {
            if (fence == null || !fence.inFlight()) {
                return 0;
            }
            fence = new AiChatSendFence(fence.id(), tenantId, runId, fence.customerId(), fence.blocked(), false,
                    fence.startedAt(), null, fence.version() + 1);
            return 1;
        }

        @Override
        public synchronized int recover(long tenantId, long runId, Instant now) {
            if (fence == null || !fence.inFlight()) {
                return 0;
            }
            fence = new AiChatSendFence(fence.id(), tenantId, runId, fence.customerId(), fence.blocked(), false,
                    fence.startedAt(), now, fence.version() + 1);
            return 1;
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
