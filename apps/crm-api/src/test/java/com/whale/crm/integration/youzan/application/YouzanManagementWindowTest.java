package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.adapter.YouzanPullPage;
import com.whale.crm.integration.youzan.adapter.YouzanSyncCursorValue;
import com.whale.crm.integration.youzan.adapter.YouzanWindowTooLargeException;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouzanManagementWindowTest {
    @Test
    void oversizedWindowSplitsAndKeepsSecondHalfCursor() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.empty());
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(eq(2L), eq("123"))).thenReturn(Optional.empty());
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-01T00:00:00Z");
        String cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start, end, end, 100));
        when(adapter.pullOrders(eq(2L), eq("123"), anyString(), eq(100)))
                .thenThrow(new YouzanWindowTooLargeException())
                .thenAnswer(invocation -> {
                    String value = invocation.getArgument(2);
                    return new YouzanPullPage(List.of(), value, false, Map.of());
                });
        YouzanManagementService service = new YouzanManagementService(repository, configs,
                mock(YouzanEventService.class), router, mock(OrderApplicationService.class),
                mock(CatalogApplicationService.class), mock(CustomerScopeSupport.class),
                mock(IdempotencyService.class), mock(AuditService.class), mock(OutboxService.class),
                mapper, mock(AesGcmCipher.class), transactionManager());
        CurrentUser user = new CurrentUser(1, 2, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("integration:youzan:manage"), Set.of());

        YouzanSyncResponse response = service.sync(user, new YouzanSyncRequest("123", cursor, 100), "window-test");

        YouzanSyncCursorValue next = mapper.readValue(response.nextCursor(), YouzanSyncCursorValue.class);
        assertThat(next.windowStart()).isEqualTo(Instant.parse("2026-01-30T12:00:00Z"));
        assertThat(next.windowEnd()).isEqualTo(end);
        assertThat(next.pageNo()).isEqualTo(1);
        assertThat(response.hasMore()).isTrue();
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
