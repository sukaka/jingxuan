package com.whale.crm.ai.application;

import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.ai.domain.AiChatSendFence;
import com.whale.crm.ai.domain.AiChatSendFenceRepository;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

@Service
public class AiChatSendFenceService {
    private final AiChatDelegationRepository delegationRepository;
    private final AiChatSendFenceRepository fenceRepository;
    private final CustomerRepository customerRepository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final long stopWaitMs;
    private final long pollMs;

    @Autowired
    public AiChatSendFenceService(AiChatDelegationRepository delegationRepository,
                                  AiChatSendFenceRepository fenceRepository, CustomerRepository customerRepository,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${app.ai.delegation.send-fence.stop-wait-ms:15000}") long stopWaitMs,
                                  @Value("${app.ai.delegation.send-fence.poll-ms:25}") long pollMs) {
        this(delegationRepository, fenceRepository, customerRepository, transactionManager,
                Clock.systemUTC(), stopWaitMs, pollMs);
    }

    public AiChatSendFenceService(AiChatDelegationRepository delegationRepository,
                                  AiChatSendFenceRepository fenceRepository, CustomerRepository customerRepository,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  long stopWaitMs, long pollMs) {
        this.delegationRepository = delegationRepository;
        this.fenceRepository = fenceRepository;
        this.customerRepository = customerRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.stopWaitMs = stopWaitMs;
        this.pollMs = pollMs;
    }

    public Decision authorize(AiChatDelegationRun candidate, String owner, SendGate gate) {
        return transactions.execute(status -> {
            AiChatDelegationPolicy policy = delegationRepository.findPolicyForUpdate(candidate.tenantId())
                    .orElseGet(() -> AiChatDelegationPolicy.defaults(candidate.tenantId()));
            Customer customer = customerRepository.findByIdForUpdate(candidate.tenantId(), candidate.customerId())
                    .orElse(null);
            String denied = gate.recheck(policy, customer);
            AiChatDelegationRun locked = delegationRepository.findRunForUpdate(candidate.tenantId(), candidate.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI托管流水不存在"));
            if (!"SENDING".equals(locked.status()) || !owner.equals(locked.leaseOwner())
                    || locked.version() != candidate.version()) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI托管发送租约已变化");
            }
            AiChatSendFence existing = fenceRepository.findForUpdate(candidate.tenantId(), candidate.id()).orElse(null);
            if (existing != null && existing.inFlight()) {
                return new Decision(null, "SEND_ALREADY_IN_FLIGHT");
            }
            if (existing != null && existing.blocked()) {
                return new Decision(null, "SEND_AUTHORIZATION_REVOKED");
            }
            if (existing != null && existing.finishedAt() != null) {
                return new Decision(null, "SEND_ALREADY_COMPLETED");
            }
            if (denied != null) {
                return new Decision(null, denied);
            }
            Instant now = clock.instant();
            if (existing == null) {
                fenceRepository.insert(new AiChatSendFence(null, candidate.tenantId(), candidate.id(),
                        candidate.customerId(), false, true, now, null, 0));
                return new Decision(new Authorization(candidate.tenantId(), candidate.id(), 0), null);
            }
            if (fenceRepository.markStarted(candidate.tenantId(), candidate.id(), existing.version(), now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "AI发送栅栏已变化");
            }
            return new Decision(new Authorization(candidate.tenantId(), candidate.id(), existing.version() + 1), null);
        });
    }

    public void finish(Authorization authorization) {
        if (authorization == null) {
            return;
        }
        fenceRepository.finish(authorization.tenantId(), authorization.runId(), authorization.version(), clock.instant());
    }

    public void recover(long tenantId, long runId) {
        fenceRepository.recover(tenantId, runId, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void blockTenant(long tenantId) {
        fenceRepository.blockTenant(tenantId, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void blockCustomer(long tenantId, long customerId) {
        fenceRepository.blockCustomer(tenantId, customerId, clock.instant());
    }

    public void awaitTenant(long tenantId) {
        await(() -> fenceRepository.countInFlightForTenant(tenantId));
    }

    public void awaitCustomer(long tenantId, long customerId) {
        await(() -> fenceRepository.countInFlightForCustomer(tenantId, customerId));
    }

    private void await(FlightCount count) {
        long deadline = System.nanoTime() + stopWaitMs * 1_000_000L;
        while (count.current() > 0) {
            if (System.nanoTime() >= deadline) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                        "停用已记录，但仍有不可撤销的发送中调用未结束；本次不能确认已完全停止");
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.CONFLICT, "停用等待被中断，请查询发送状态后重试");
            }
        }
    }

    @FunctionalInterface
    public interface SendGate {
        String recheck(AiChatDelegationPolicy policy, Customer customer);
    }

    @FunctionalInterface
    private interface FlightCount {
        long current();
    }

    public record Authorization(long tenantId, long runId, long version) { }

    public record Decision(Authorization authorization, String deniedReason) {
        public boolean accepted() {
            return authorization != null;
        }
    }
}
