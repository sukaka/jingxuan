package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.api.dto.WecomSyncResponse;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.application.WecomContactSyncService;
import com.whale.crm.integration.wecom.application.WecomSyncScheduler;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicy;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicyRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomSyncSchedulerTest {
    @Test
    void oneFailedTypeDoesNotBlockTheOtherAndStatusIsPartial() {
        WecomSyncPolicyRepository repository = mock(WecomSyncPolicyRepository.class);
        WecomContactSyncService contacts = mock(WecomContactSyncService.class);
        WecomArchiveService archive = mock(WecomArchiveService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        WecomSyncScheduler scheduler = new WecomSyncScheduler(repository, contacts, archive, users);
        WecomSyncPolicy policy = new WecomSyncPolicy(1L, 2L, "corp-2", true, true, true, 300,
                Instant.now().minusSeconds(1), null, "NEVER", null, 0L);
        when(repository.findDue(any(Instant.class), eq(100))).thenReturn(List.of(policy));
        when(repository.claim(eq(2L), eq(1L), eq(0L), any(Instant.class), any(Instant.class))).thenReturn(1);
        CurrentUser admin = new CurrentUser(9, 2, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("wecom:monitor"), Set.of());
        when(users.loadFirstActiveByRole(2L, "ADMIN")).thenReturn(Optional.of(admin));
        when(contacts.corpId(2L)).thenReturn("corp-2");
        when(contacts.sync(admin, "RECONCILE")).thenThrow(new BusinessException(ErrorCode.CONFLICT, "模拟失败"));
        when(archive.sync(eq(admin), any())).thenReturn(new WecomSyncResponse("MOCK", 1, 1, 0, 0, 1, false, false));

        scheduler.runDuePolicies();

        verify(archive).sync(eq(admin), any());
        verify(repository).finish(eq(2L), eq(1L), eq(1L), eq("PARTIAL"), anyString());
    }
}
