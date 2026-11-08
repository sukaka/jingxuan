package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.application.PaymentApplicationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanEventMaskingTest {
    @Test
    void guideAndExternalIdentityFieldsAreMaskedInPublicPayload() {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findOrderMap(any(Long.class), any(), any())).thenReturn(Optional.empty());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        YouzanEventService service = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), mock(CurrentUserService.class), mock(AuditService.class),
                mock(OutboxService.class), transactionManager(), mock(TaskExecutor.class));
        YouzanOrderEvent event = MockYouzanAdapter.paidEvent();
        ObjectNode raw = mapper.valueToTree(event);
        raw.put("salesman_mobile", "13800000000");
        raw.put("service_guide_name", "虚构导购");
        raw.put("outer_user_id", "external-open-id");
        raw.put("buyer_message", "含个人联系方式的留言");
        raw.putObject("service_guide").put("service_guide_staff", "staff-private-1");
        java.util.concurrent.atomic.AtomicReference<YouzanEvent> inserted = new java.util.concurrent.atomic.AtomicReference<>();
        when(repository.findEventByKey(any(Long.class), any(), any())).thenAnswer(invocation ->
                inserted.get() == null ? Optional.empty() : Optional.of(inserted.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
            inserted.set(invocation.getArgument(1));
            return null;
        }).when(repository).insertEvent(any(Long.class), any());

        service.ingest(2L, event, "OFFICIAL_WEBHOOK", raw.toString());

        ArgumentCaptor<YouzanEvent> saved = ArgumentCaptor.forClass(YouzanEvent.class);
        verify(repository).insertEvent(any(Long.class), saved.capture());
        String masked = saved.getValue().payloadMaskedJson();
        assertThat(masked).doesNotContain("13800000000", "虚构导购", "external-open-id", "staff-private-1",
                "含个人联系方式的留言");
        assertThat(masked).contains("***");
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
            }

            public void rollback(TransactionStatus status) {
            }
        };
    }
}
