package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.api.dto.WecomRoomSaveRequest;
import com.whale.crm.integration.wecom.application.WecomRoomService;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomRoomRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomRoomServiceTest {
    @Test
    void concurrentRoomCreationReturnsConflictWithoutPartialRoster() {
        WecomRoomRepository repository = mock(WecomRoomRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AuditService audit = mock(AuditService.class);
        when(idempotency.lookup(anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.findRoomForUpdate(1L, "corp", "wr-room")).thenReturn(Optional.empty());
        doThrow(new DuplicateKeyException("synthetic duplicate")).when(repository)
                .insertRoom(anyLong(), any(), anyLong(), any());
        WecomRoomService service = new WecomRoomService(repository, mock(WecomContactSyncRepository.class),
                mock(CustomerScopeSupport.class), idempotency, audit, new ObjectMapper());
        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "测试管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("wecom:monitor"), Set.of());
        WecomRoomSaveRequest request = new WecomRoomSaveRequest("corp", "测试群", true,
                List.of("staff-2", "wm-synthetic"), "synthetic://roster", 0L, "核对成员");

        assertThatThrownBy(() -> service.save(admin, "wr-room", request, "room-key"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("已创建");
        verify(repository, never()).insertParticipant(anyLong(), any(), any());
        verify(audit, never()).record(anyLong(), any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }
}
