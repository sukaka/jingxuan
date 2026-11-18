package com.whale.crm.integration.wecom.media;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.media.adapter.WecomMediaProperties;
import com.whale.crm.integration.wecom.media.application.WecomMediaService;
import com.whale.crm.integration.wecom.media.application.WecomMediaWorker;
import com.whale.crm.integration.wecom.media.domain.WecomMediaAsset;
import com.whale.crm.integration.wecom.media.domain.WecomMediaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomMediaWorkerTest {
    @Test
    void claimsOnlyRealEnabledTenantAndRecordsDownloadFailure() {
        WecomMediaRepository repository = mock(WecomMediaRepository.class);
        WecomMediaService service = mock(WecomMediaService.class);
        IntegrationRuntimeConfigService runtime = mock(IntegrationRuntimeConfigService.class);
        WecomMediaAsset candidate = candidate();
        when(repository.findPendingTenantIds(any())).thenReturn(List.of(1L));
        when(repository.claimable(org.mockito.ArgumentMatchers.eq(1L), any(), anyInt())).thenReturn(List.of(candidate));
        when(repository.claim(anyLong(), anyLong(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(runtime.wecomArchiveRealEnabled(1L)).thenReturn(true);
        doThrow(new IllegalStateException("synthetic failure")).when(service)
                .download(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(9L), anyString());

        int processed = new WorkerFixture(repository, service, runtime, true).worker().runOnce();

        assertThat(processed).isEqualTo(1);
        verify(service).fail(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(9L),
                anyString(), any(IllegalStateException.class));
    }

    @Test
    void disabledWorkerAndLostLeaseDoNotCallDownload() {
        WecomMediaRepository repository = mock(WecomMediaRepository.class);
        WecomMediaService service = mock(WecomMediaService.class);
        IntegrationRuntimeConfigService runtime = mock(IntegrationRuntimeConfigService.class);
        assertThat(new WorkerFixture(repository, service, runtime, false).worker().runOnce()).isZero();
        verify(service, never()).download(anyLong(), anyLong(), anyString());

        when(repository.findPendingTenantIds(any())).thenReturn(List.of(1L));
        when(repository.claimable(org.mockito.ArgumentMatchers.eq(1L), any(), anyInt())).thenReturn(List.of(candidate()));
        when(repository.claim(anyLong(), anyLong(), anyLong(), anyString(), any(), any())).thenReturn(0);
        when(runtime.wecomArchiveRealEnabled(1L)).thenReturn(true);
        assertThat(new WorkerFixture(repository, service, runtime, true).worker().runOnce()).isZero();
        verify(service, never()).download(anyLong(), anyLong(), anyString());
    }

    private WecomMediaAsset candidate() {
        return new WecomMediaAsset(9L, 1L, 8L, 0, "sdk", "file", null, 1L, "test.txt",
                "text/plain", "PENDING", null, null, null, null, 0, null, null,
                null, null, null, 0);
    }

    private record WorkerFixture(WecomMediaRepository repository, WecomMediaService service,
                                 IntegrationRuntimeConfigService runtime, boolean enabled) {
        WecomMediaWorker worker() {
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            return new WecomMediaWorker(repository, service,
                    new WecomMediaProperties(enabled, 10, 30000, 5, 1024, 10, 0), runtime, manager);
        }
    }
}
