package com.whale.crm.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.notification.api.dto.CreateWecomMappingRequest;
import com.whale.crm.notification.api.dto.UpdateWecomMappingRequest;
import com.whale.crm.notification.application.WecomMappingApplicationService;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.WecomMapping;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomMappingApplicationServiceTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditService audit = mock(AuditService.class);
    private final WecomMappingApplicationService service = new WecomMappingApplicationService(
            repository, idempotency, audit, new ObjectMapper());

    @Test
    void retryOnlyUserCannotCreateOrUpdateMapping() {
        CurrentUser retryOnly = new CurrentUser(2L, 1L, "ops", "操作员", "EMPLOYEE", null,
                Set.of("OPERATION"), Set.of("notification:delivery:retry", "notification:delivery:read"), Set.of());

        assertThatThrownBy(() -> service.create(1L, retryOnly,
                new CreateWecomMappingRequest("ww-1", "u-1", 4L, true, "reason"), "k-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.update(1L, retryOnly, 10L,
                new UpdateWecomMappingRequest("ww-1", "u-1", 4L, false, 0L, "reason"), "k-2"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(repository, never()).insertWecomMapping(any());
        verify(repository, never()).updateWecomMapping(any());
    }

    @Test
    void enabledArchiveMappingRejectsIdentityChangeButAllowsMessageToggle() {
        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("wecom:mapping:manage"), Set.of());
        WecomMapping enabled = new WecomMapping(10L, 1L, "ww-1", "staff-2", 2L, true, true, 3L);
        WecomMapping toggled = new WecomMapping(10L, 1L, "ww-1", "staff-2", 2L, true, false, 4L);
        when(idempotency.lookup(anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.findWecomMappingForUpdate(1L, 10L)).thenReturn(Optional.of(enabled));
        when(repository.employeeActive(1L, 2L)).thenReturn(true);
        when(repository.updateWecomMapping(any())).thenReturn(1);
        when(repository.findWecomMapping(1L, 10L)).thenReturn(Optional.of(toggled));

        assertThatThrownBy(() -> service.update(1L, admin, 10L,
                new UpdateWecomMappingRequest("ww-1", "staff-2", 1L, true, 3L, "hijack"), "k-hijack"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        var response = service.update(1L, admin, 10L,
                new UpdateWecomMappingRequest("ww-1", "staff-2", 2L, false, 3L, "toggle"), "k-toggle");
        assertThat(response.messageEnabled()).isFalse();
        assertThat(response.version()).isEqualTo(4L);
    }

    @Test
    void createRejectsNonEmployeeAccountEvenIfActive() {
        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("wecom:mapping:manage"), Set.of());
        when(repository.recipientActive(1L, 8L)).thenReturn(true);
        when(repository.employeeActive(1L, 8L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(1L, admin,
                new CreateWecomMappingRequest("ww-1", "inst-1", 8L, true, "bad"), "k-inst"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).insertWecomMapping(any());
    }
}
